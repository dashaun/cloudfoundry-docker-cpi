package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.docker.SshLocalForward;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConfigureCfCliStep implements SetupStep {

    static final String NAME = "configure-cf-cli";
    static final String CF_ORG = "system";
    static final String CF_SPACE = "dev";
    static final String HAPROXY_VM_IP = "10.245.0.34";  // matches DeployCfStep.ROUTER_STATIC_IP
    static final int HAPROXY_PORT = 443;
    static final int PREFERRED_LOCAL_PORT = 8443;
    static final String SMOKE_APP_HOSTNAME = "cf-smoke";
    static final String[] HOST_PREFIXES = {"api", "login", "uaa", SMOKE_APP_HOSTNAME};

    private static final Pattern CF_ADMIN_PW =
            Pattern.compile("(?m)^cf_admin_password:\\s+(\\S+)");
    private static final Pattern TARGET_API =
            Pattern.compile("(?im)^\\s*api endpoint:\\s+(\\S+)");
    private static final Pattern TARGET_ORG =
            Pattern.compile("(?im)^\\s*org:\\s+(\\S+)");
    private static final Pattern TARGET_SPACE =
            Pattern.compile("(?im)^\\s*space:\\s+(\\S+)");

    private final StatusStore statusStore;

    public ConfigureCfCliStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Point cf at the new Cloud Foundry, log in as admin, create the system/dev org & space.";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        Optional<StepStatus> recorded;
        try {
            recorded = statusStore.get(ctx.statusFile(), NAME);
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }
        if (recorded.isEmpty() || recorded.get().status() != StepStatus.Status.PASS) {
            return StepCheck.NEEDS_RUN;
        }
        if (!Files.isDirectory(ctx.cfHome())) {
            return StepCheck.NEEDS_RUN;
        }
        if (ctx.verify()) {
            Path cfBin = ctx.binDir().resolve("cf");
            if (!Files.isRegularFile(cfBin)) return StepCheck.NEEDS_RUN;
            try {
                CfResult out = runCf(cfBin, ctx.cfHome(), Map.of(), null, "target");
                if (out.exit != 0) return StepCheck.NEEDS_RUN;
                if (!targetMatches(out.stdout, ctx)) return StepCheck.NEEDS_RUN;
            } catch (IOException | InterruptedException e) {
                return StepCheck.NEEDS_RUN;
            }
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("configure-cf-cli v1 supports ssh:// targets only; got " + ctx.target().uri());
        }

        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Path cfBin = ctx.binDir().resolve("cf");
        if (!Files.isRegularFile(cfBin) || !Files.isExecutable(cfBin)) {
            return failPrecheck(ctx, logFile, "cf binary missing or not executable at "
                    + cfBin + " — run install-tools");
        }
        Path creds = ctx.stateDir().resolve("cf-creds.yml");
        String adminPw = readAdminPassword(creds);
        if (adminPw == null) {
            return failPrecheck(ctx, logFile, "cf_admin_password not found in " + creds
                    + " — run deploy-cf");
        }

        List<String> hostnames = expandHostnames(ctx.systemDomain());
        List<String> unresolved = unresolvedHostnames(hostnames);
        if (!unresolved.isEmpty()) {
            String hostsLine = "127.0.0.1 " + String.join(" ", hostnames);
            if (ctx.writeHosts()) {
                try {
                    writeHostsEntries(hostnames, logFile);
                } catch (IOException e) {
                    return failPrecheck(ctx, logFile, "--write-hosts failed: " + e.getMessage()
                            + "\n  Add manually: " + hostsLine);
                }
                unresolved = unresolvedHostnames(hostnames);
                if (!unresolved.isEmpty()) {
                    return failPrecheck(ctx, logFile, "wrote /etc/hosts but " + unresolved
                            + " still don't resolve (DNS cache?). Try again.");
                }
            } else {
                return failPrecheck(ctx, logFile, "hostnames not resolvable: " + unresolved
                        + "\n  Add to /etc/hosts:  " + hostsLine
                        + "\n  Or re-run with --write-hosts");
            }
        }

        Files.createDirectories(ctx.cfHome());

        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, cfBin);
            try (SshLocalForward fwd = SshLocalForward.open(ctx.target(),
                    HAPROXY_VM_IP, HAPROXY_PORT, PREFERRED_LOCAL_PORT, Duration.ofSeconds(10))) {
                logOut.write("Tunnel:    " + fwd.description() + "\n\n");
                logOut.flush();

                int port = fwd.localPort();
                String apiUrl = "https://api." + ctx.systemDomain() + ":" + port;

                exec(cfBin, ctx.cfHome(), Map.of(), null, logOut,
                        "api", apiUrl, "--skip-ssl-validation");

                Map<String, String> authEnv = new LinkedHashMap<>();
                authEnv.put("CF_USERNAME", "admin");
                authEnv.put("CF_PASSWORD", adminPw);
                exec(cfBin, ctx.cfHome(), authEnv, null, logOut, "auth");

                ensureOrg(cfBin, ctx, logOut, CF_ORG);
                ensureSpace(cfBin, ctx, logOut, CF_ORG, CF_SPACE);

                exec(cfBin, ctx.cfHome(), Map.of(), null, logOut,
                        "target", "-o", CF_ORG, "-s", CF_SPACE);
            }
        } catch (StepFailure e) {
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(e.detail));
            return StepResult.failed(e.detail + " (log: " + logFile + ")");
        }

        String summary = "cf targeted https://api." + ctx.systemDomain() + ":" + PREFERRED_LOCAL_PORT
                + " (org=" + CF_ORG + " space=" + CF_SPACE + " CF_HOME=" + ctx.cfHome() + ")";
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private void ensureOrg(Path cfBin, SetupContext ctx, BufferedWriter logOut, String org)
            throws IOException, InterruptedException, StepFailure {
        CfResult orgs = runCf(cfBin, ctx.cfHome(), Map.of(), null, "orgs");
        if (orgs.exit != 0) {
            logOut.write(orgs.stdout);
            logOut.flush();
            throw new StepFailure("cf orgs failed (exit " + orgs.exit + ")");
        }
        if (containsLineEqualling(orgs.stdout, org)) {
            logOut.write("[org] '" + org + "' already exists; skipping create-org\n");
            logOut.flush();
            return;
        }
        exec(cfBin, ctx.cfHome(), Map.of(), null, logOut, "create-org", org);
    }

    private void ensureSpace(Path cfBin, SetupContext ctx, BufferedWriter logOut,
                             String org, String space)
            throws IOException, InterruptedException, StepFailure {
        CfResult spaces = runCf(cfBin, ctx.cfHome(), Map.of(), null, "spaces", "-o", org);
        if (spaces.exit != 0) {
            logOut.write(spaces.stdout);
            logOut.flush();
            throw new StepFailure("cf spaces -o " + org + " failed (exit " + spaces.exit + ")");
        }
        if (containsLineEqualling(spaces.stdout, space)) {
            logOut.write("[space] '" + space + "' already exists in '" + org + "'; skipping create-space\n");
            logOut.flush();
            return;
        }
        exec(cfBin, ctx.cfHome(), Map.of(), null, logOut, "create-space", space, "-o", org);
    }

    private void exec(Path cfBin, Path cfHome, Map<String, String> env, String stdin,
                      BufferedWriter logOut, String... args)
            throws IOException, InterruptedException, StepFailure {
        logOut.write("$ cf " + String.join(" ", args) + "\n");
        logOut.flush();
        CfResult r = runCf(cfBin, cfHome, env, stdin, args);
        logOut.write(r.stdout);
        if (!r.stdout.endsWith("\n")) logOut.write("\n");
        logOut.flush();
        if (r.exit != 0) {
            throw new StepFailure("cf " + args[0] + " failed (exit " + r.exit + ")");
        }
    }

    private CfResult runCf(Path cfBin, Path cfHome, Map<String, String> env, String stdin,
                           String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfBin.toString());
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("CF_HOME", cfHome.toString());
        pb.environment().put("CF_COLOR", "false");
        pb.environment().putAll(env);
        Process p = pb.start();
        if (stdin != null) {
            try (var out = p.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            p.getOutputStream().close();
        }
        byte[] body;
        try (InputStream in = p.getInputStream()) {
            body = in.readAllBytes();
        }
        return new CfResult(p.waitFor(), new String(body, StandardCharsets.UTF_8));
    }

    static List<String> expandHostnames(String systemDomain) {
        List<String> out = new ArrayList<>();
        for (String pref : HOST_PREFIXES) out.add(pref + "." + systemDomain);
        return out;
    }

    private List<String> unresolvedHostnames(List<String> hostnames) {
        List<String> missing = new ArrayList<>();
        for (String h : hostnames) {
            try {
                InetAddress.getByName(h);
            } catch (UnknownHostException e) {
                missing.add(h);
            }
        }
        return missing;
    }

    // Appends the consolidated hosts line via `sudo tee -a /etc/hosts`. Interactive sudo is
    // expected (macOS will prompt for the user's password). We add a marker line on each side
    // so future runs / removal is easy to spot.
    private void writeHostsEntries(List<String> hostnames, Path logFile) throws IOException {
        String marker = "# cf-docker-cpi (configure-cf-cli)";
        StringBuilder block = new StringBuilder();
        block.append('\n').append(marker).append('\n');
        block.append("127.0.0.1 ").append(String.join(" ", hostnames)).append('\n');
        block.append("# end cf-docker-cpi\n");

        ProcessBuilder pb = new ProcessBuilder("sudo", "tee", "-a", "/etc/hosts");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var out = p.getOutputStream()) {
            out.write(block.toString().getBytes(StandardCharsets.UTF_8));
        }
        byte[] resp;
        try (InputStream in = p.getInputStream()) {
            resp = in.readAllBytes();
        }
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for sudo tee", e);
        }
        if (exit != 0) {
            throw new IOException("sudo tee -a /etc/hosts exited " + exit + ": "
                    + new String(resp, StandardCharsets.UTF_8).trim());
        }
    }

    private String readAdminPassword(Path creds) {
        if (!Files.isRegularFile(creds)) return null;
        try {
            String body = Files.readString(creds);
            Matcher m = CF_ADMIN_PW.matcher(body);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean targetMatches(String cfTargetOutput, SetupContext ctx) {
        Matcher api = TARGET_API.matcher(cfTargetOutput);
        if (!api.find() || !api.group(1).contains("api." + ctx.systemDomain())) return false;
        Matcher org = TARGET_ORG.matcher(cfTargetOutput);
        if (!org.find() || !CF_ORG.equals(org.group(1))) return false;
        Matcher space = TARGET_SPACE.matcher(cfTargetOutput);
        return space.find() && CF_SPACE.equals(space.group(1));
    }

    private static boolean containsLineEqualling(String text, String needle) {
        for (String line : text.split("\\R")) {
            if (line.trim().equals(needle)) return true;
        }
        return false;
    }

    private StepResult failPrecheck(SetupContext ctx, Path logFile, String detail) throws IOException {
        Files.writeString(logFile, "Timestamp: " + Instant.now() + "\n" + detail + "\n");
        statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
        return StepResult.failed(detail + " (log: " + logFile + ")");
    }

    private void header(BufferedWriter logOut, SetupContext ctx, Path cfBin) throws IOException {
        logOut.write("Timestamp:     " + Instant.now() + "\n");
        logOut.write("Target:        " + ctx.target().uri() + "\n");
        logOut.write("cf binary:     " + cfBin + "\n");
        logOut.write("CF_HOME:       " + ctx.cfHome() + "\n");
        logOut.write("system_domain: " + ctx.systemDomain() + "\n");
        logOut.write("Org/Space:     " + CF_ORG + " / " + CF_SPACE + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    private record CfResult(int exit, String stdout) {}

    private static final class StepFailure extends Exception {
        final String detail;
        StepFailure(String detail) { super(detail); this.detail = detail; }
    }
}
