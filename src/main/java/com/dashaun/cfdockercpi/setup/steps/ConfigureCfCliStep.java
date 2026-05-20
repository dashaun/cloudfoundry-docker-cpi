package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import com.dashaun.cfdockercpi.tooling.ToolingVersions;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Targets cf at the CF deployed by deploy-cf, running cf *on the docker host* (not the laptop).
//
// Why on the docker host: the cf-deployment haproxy/router only honors hostnames mapped to
// 10.245.0.34 (the bridge IP) on default ports (80/443). The laptop-side design (SSH local-
// forward to localhost:8443, /etc/hosts to 127.0.0.1) breaks at `cf auth` because cf
// rediscovers the UAA URL from /v2/info without our `:8443` port → connection refused. The
// docker host can reach 10.245.0.34:443 directly with no tunnel. Issue #20.
@Component
public class ConfigureCfCliStep implements SetupStep {

    static final String NAME = "configure-cf-cli";
    static final String CF_ORG = "system";
    static final String CF_SPACE = "dev";
    static final String HAPROXY_VM_IP = "10.245.0.34";  // matches DeployCfStep.ROUTER_STATIC_IP
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final String REMOTE_CF_BIN = REMOTE_WORK_DIR + "/bin/cf";
    static final String REMOTE_CF_HOME = REMOTE_WORK_DIR + "/cf-home";
    // cf-deployment routes by Host: header through haproxy on :443, so each hostname that the
    // local cf and `cf push`'d apps need to reach must resolve to the bridge IP.
    static final String[] HOST_PREFIXES = {
            "api", "login", "uaa", "cf-smoke", "log-cache", "doppler"
    };
    static final String HOSTS_MARKER_BEGIN = "# cf-docker-cpi (configure-cf-cli)";
    static final String HOSTS_MARKER_END = "# end cf-docker-cpi";

    // Pinned linux/amd64 cf 8.x archive. verify-docker validates the host is linux/x86_64,
    // so hardcoding the linux tarball is safe.
    private static final String CF_LINUX_AMD64_URL =
            "https://github.com/cloudfoundry/cli/releases/download/v" + ToolingVersions.CF_VERSION
                    + "/cf8-cli_" + ToolingVersions.CF_VERSION + "_linux_x86-64.tgz";
    private static final String CF_LINUX_AMD64_SHA =
            "8942e2c3c98e83c7e14edbce939876bba7ff12a26f0f722c5aa5b079d357d50b";

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
        return "On the docker host: download cf, seed /etc/hosts, target the new CF, log in as "
                + "admin, create the system/dev org & space.";
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
        if (ctx.verify()) {
            if (!ctx.target().isSsh()) return StepCheck.NEEDS_RUN;
            try {
                CapturedRun r = runRemote(ctx, verifyScript());
                if (r.exit != 0) return StepCheck.NEEDS_RUN;
                if (!targetMatches(r.output, ctx)) return StepCheck.NEEDS_RUN;
            } catch (IOException | InterruptedException e) {
                return StepCheck.NEEDS_RUN;
            }
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("configure-cf-cli v2 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Path creds = ctx.stateDir().resolve("cf-creds.yml");
        String adminPw = readAdminPassword(creds);
        if (adminPw == null) {
            return failPrecheck(ctx, logFile, "cf_admin_password not found in " + creds
                    + " — run deploy-cf");
        }

        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx);
            String script = bootstrapScript(ctx, adminPw);
            int exit = streamRemote(ctx, script, logOut);
            if (exit != 0) {
                String detail = "configure-cf-cli failed (ssh exit " + exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String summary = "cf targeted https://api." + ctx.systemDomain()
                + " (org=" + CF_ORG + " space=" + CF_SPACE
                + " remote CF_HOME=~/" + REMOTE_CF_HOME + ")";
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String bootstrapScript(SetupContext ctx, String adminPw) {
        String hostsLine = HAPROXY_VM_IP + " "
                + String.join(" ", expandHostnames(ctx.systemDomain()));
        String hostsBlock = HOSTS_MARKER_BEGIN + "\n" + hostsLine + "\n" + HOSTS_MARKER_END;
        String apiUrl = "https://api." + ctx.systemDomain();
        // --write-hosts gates a `sudo tee -a /etc/hosts`. Without it, the step prints the line
        // the user should add by hand and exits 78. (Same pattern as host-setup's sudo recipe.)
        return "set -euo pipefail\n"
            + "mkdir -p ~/" + REMOTE_WORK_DIR + "/bin ~/" + REMOTE_CF_HOME + "\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + installCfStanza()
            + "\n"
            + ensureHostsStanza(ctx, hostsBlock)
            + "\n"
            + "export CF_HOME=\"$(pwd)/cf-home\"\n"
            + "export CF_COLOR=false\n"
            + "echo \"[cf] api " + apiUrl + " --skip-ssl-validation\"\n"
            + "./bin/cf api " + apiUrl + " --skip-ssl-validation >/dev/null\n"
            + "\n"
            + "echo \"[cf] auth admin (CF_PASSWORD piped via env from local cf-creds.yml)\"\n"
            // The admin password gets to the remote via the bash script body, never on the
            // `ssh` argv, so it doesn't show up in `ps`. CF_PASSWORD is briefly in this bash
            // process's environment until `unset` after `cf auth` returns.
            + "export CF_USERNAME=admin\n"
            + "export CF_PASSWORD='" + escapeSingleQuoted(adminPw) + "'\n"
            + "./bin/cf auth >/dev/null\n"
            + "unset CF_PASSWORD CF_USERNAME\n"
            + "\n"
            // cf 8.x dropped the global `--no-color` flag (CF_COLOR=false handles it now)
            // and `cf spaces -o ORG` (you target the org first, then `cf spaces` lists that
            // org's spaces only). Target → list → create-if-missing → final target.
            + "if ./bin/cf orgs | awk '{print $1}' | grep -qx " + CF_ORG + "; then\n"
            + "  echo \"[cf] org " + CF_ORG + " already exists\"\n"
            + "else\n"
            + "  echo \"[cf] create-org " + CF_ORG + "\"\n"
            + "  ./bin/cf create-org " + CF_ORG + " >/dev/null\n"
            + "fi\n"
            + "./bin/cf target -o " + CF_ORG + " >/dev/null\n"
            + "\n"
            + "if ./bin/cf spaces | awk '{print $1}' | grep -qx " + CF_SPACE + "; then\n"
            + "  echo \"[cf] space " + CF_SPACE + " already exists in " + CF_ORG + "\"\n"
            + "else\n"
            + "  echo \"[cf] create-space " + CF_SPACE + " in " + CF_ORG + "\"\n"
            + "  ./bin/cf create-space " + CF_SPACE + " >/dev/null\n"
            + "fi\n"
            + "\n"
            + "echo \"[cf] target -o " + CF_ORG + " -s " + CF_SPACE + "\"\n"
            + "./bin/cf target -o " + CF_ORG + " -s " + CF_SPACE + "\n";
    }

    private String installCfStanza() {
        return ""
            + "CF_PINNED='" + ToolingVersions.CF_VERSION + "'\n"
            + "if ! [ -x ./bin/cf ] || ! ./bin/cf --version 2>/dev/null | grep -q \"$CF_PINNED\"; then\n"
            + "  echo \"[bootstrap] downloading cf-cli " + ToolingVersions.CF_VERSION + "\"\n"
            + "  ( tmpdir=$(mktemp -d) && cd \"$tmpdir\" \\\n"
            + "    && curl -fsSL -o cf.tgz '" + CF_LINUX_AMD64_URL + "' \\\n"
            + "    && echo '" + CF_LINUX_AMD64_SHA + "  cf.tgz' | sha256sum -c - \\\n"
            + "    && tar -xzf cf.tgz cf8 \\\n"
            + "    && install -m 0755 cf8 \"$OLDPWD/bin/cf\" \\\n"
            + "    && cd \"$OLDPWD\" && rm -rf \"$tmpdir\" )\n"
            + "fi\n"
            + "echo \"[bootstrap] cf: $(./bin/cf --version)\"\n";
    }

    private String ensureHostsStanza(SetupContext ctx, String hostsBlock) {
        // grep -F + the canonical line; only act when it's missing AND --write-hosts was passed.
        String canonical = HAPROXY_VM_IP + " api." + ctx.systemDomain();
        if (ctx.writeHosts()) {
            return ""
                + "if ! grep -Fq '" + canonical + "' /etc/hosts; then\n"
                + "  echo \"[hosts] adding cf-docker-cpi block to /etc/hosts (sudo)\"\n"
                + "  printf '\\n%s\\n' '" + escapeSingleQuoted(hostsBlock) + "' | sudo -n tee -a /etc/hosts >/dev/null\n"
                + "else\n"
                + "  echo \"[hosts] /etc/hosts already contains '" + canonical + "'\"\n"
                + "fi\n";
        }
        // No write flag: probe and bail with the exact line if missing.
        return ""
            + "if ! grep -Fq '" + canonical + "' /etc/hosts; then\n"
            + "  echo \"ERROR: /etc/hosts on the docker host is missing the cf hostname mapping.\"\n"
            + "  echo \"       Re-run configure-cf-cli with --write-hosts (passwordless sudo required\"\n"
            + "  echo \"       on the docker host), or add manually:\"\n"
            + "  echo\n"
            + "  echo \"  sudo tee -a /etc/hosts >/dev/null <<'HOSTS'\"\n"
            + "  echo\n"
            + "  echo '" + escapeSingleQuoted(hostsBlock) + "'\n"
            + "  echo\n"
            + "  echo \"HOSTS\"\n"
            + "  exit 78\n"
            + "else\n"
            + "  echo \"[hosts] /etc/hosts on the docker host already maps " + canonical + "\"\n"
            + "fi\n";
    }

    private String verifyScript() {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "[ -x ./bin/cf ] || exit 64\n"
            + "[ -d cf-home ] || exit 65\n"
            + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
            + "./bin/cf target\n";
    }

    static java.util.List<String> expandHostnames(String systemDomain) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String pref : HOST_PREFIXES) out.add(pref + "." + systemDomain);
        return out;
    }

    private CapturedRun runRemote(SetupContext ctx, String script)
            throws IOException, InterruptedException {
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        return new CapturedRun(p.waitFor(), new String(out, StandardCharsets.UTF_8));
    }

    private int streamRemote(SetupContext ctx, String script, BufferedWriter logOut)
            throws IOException, InterruptedException {
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                logOut.write(line);
                logOut.newLine();
                logOut.flush();
            }
        }
        return p.waitFor();
    }

    private Process startSshBash(SetupContext ctx) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(ctx.target().sshPort()),
                ctx.target().sshUserHost(),
                "bash -s");
        pb.redirectErrorStream(true);
        return pb.start();
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

    private StepResult failPrecheck(SetupContext ctx, Path logFile, String detail) throws IOException {
        Files.writeString(logFile, "Timestamp: " + Instant.now() + "\n" + detail + "\n");
        statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
        return StepResult.failed(detail + " (log: " + logFile + ")");
    }

    private void header(BufferedWriter logOut, SetupContext ctx) throws IOException {
        logOut.write("Timestamp:     " + Instant.now() + "\n");
        logOut.write("Target:        " + ctx.target().uri() + "\n");
        logOut.write("Remote dir:    ~/" + REMOTE_WORK_DIR + "\n");
        logOut.write("Remote cf:     ~/" + REMOTE_CF_BIN + " (" + ToolingVersions.CF_VERSION + ")\n");
        logOut.write("Remote CF_HOME: ~/" + REMOTE_CF_HOME + "\n");
        logOut.write("system_domain: " + ctx.systemDomain() + "\n");
        logOut.write("Org/Space:     " + CF_ORG + " / " + CF_SPACE + "\n");
        logOut.write("--write-hosts: " + ctx.writeHosts() + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    // Bash single-quote escape: ' -> '\''
    private static String escapeSingleQuoted(String s) {
        return s.replace("'", "'\\''");
    }

    private record CapturedRun(int exit, String output) {}
}
