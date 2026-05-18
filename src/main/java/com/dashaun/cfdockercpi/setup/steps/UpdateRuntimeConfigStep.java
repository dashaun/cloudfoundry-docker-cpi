package com.dashaun.cfdockercpi.setup.steps;

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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UpdateRuntimeConfigStep implements SetupStep {

    static final String NAME = "update-runtime-config";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final String CONFIG_NAME = "dns";
    static final String REMOTE_DNS_YML = "bosh-deployment/runtime-configs/dns.yml";
    static final String APPLIED_SHA_MARKER = "[applied-sha]";

    private static final Pattern DETAIL_APPLIED_SHA = Pattern.compile("applied_sha=([0-9a-f]{8,64})");

    private final StatusStore statusStore;

    public UpdateRuntimeConfigStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Apply bosh-deployment's `dns` runtime-config so bosh-dns is installed as an addon on every VM (cf-deployment needs *.service.cf.internal resolution).";
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
                String remoteSha = remoteAppliedSha(ctx);
                if (remoteSha == null) return StepCheck.NEEDS_RUN;
                if (!shaMatches(recorded.get().detail(), DETAIL_APPLIED_SHA, remoteSha)) {
                    return StepCheck.NEEDS_RUN;
                }
            } catch (IOException | InterruptedException e) {
                return StepCheck.NEEDS_RUN;
            }
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("update-runtime-config v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        String bootstrap = bootstrapScript(alias);
        String appliedSha;
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, alias);
            CapturedRun result = streamRemote(ctx, bootstrap, logOut);
            if (result.exit != 0) {
                String detail = "bosh update-runtime-config failed (ssh exit " + result.exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
            appliedSha = result.appliedSha;
            if (appliedSha == null) {
                String detail = "bosh update-runtime-config succeeded but applied SHA was not emitted";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String detail = "name=" + CONFIG_NAME + " applied_sha=" + shortSha(appliedSha);
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(detail));
        return StepResult.ran(detail + " (log: " + logFile + ")");
    }

    private String bootstrapScript(String alias) {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "if [ ! -f director-creds.yml ]; then\n"
            + "  echo \"ERROR: director-creds.yml missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "if ! [ -x ./bin/bosh ]; then\n"
            + "  echo \"ERROR: ./bin/bosh missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "if [ ! -f " + REMOTE_DNS_YML + " ]; then\n"
            + "  echo \"ERROR: " + REMOTE_DNS_YML + " missing — deploy-director should have cloned bosh-deployment\"; exit 78\n"
            + "fi\n"
            + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "\n"
            + "echo \"[runtime-config] applying " + REMOTE_DNS_YML + " as name=" + CONFIG_NAME + "\"\n"
            + "./bin/bosh -e " + alias + " update-runtime-config " + REMOTE_DNS_YML
                    + " --name " + CONFIG_NAME + " --no-color --non-interactive\n"
            + "\n"
            + "echo \"[runtime-config] verifying applied config on director\"\n"
            + "APPLIED_SHA=\"$(./bin/bosh -e " + alias + " runtime-config --name " + CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}')\"\n"
            + "echo \"" + APPLIED_SHA_MARKER + " ${APPLIED_SHA}\"\n";
    }

    private CapturedRun streamRemote(SetupContext ctx, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        }
        String appliedSha = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                logOut.write(line);
                logOut.newLine();
                logOut.flush();
                String captured = extractAppliedSha(line);
                if (captured != null) appliedSha = captured;
            }
        }
        return new CapturedRun(p.waitFor(), appliedSha);
    }

    private String remoteAppliedSha(SetupContext ctx) throws IOException, InterruptedException {
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        String script = "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "./bin/bosh -e " + alias + " runtime-config --name " + CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}'\n";
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String sha = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.matches("[0-9a-f]{64}")) sha = trimmed;
            }
        }
        if (p.waitFor() != 0) return null;
        return sha;
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

    private static String extractAppliedSha(String line) {
        int idx = line.indexOf(APPLIED_SHA_MARKER);
        if (idx < 0) return null;
        String rest = line.substring(idx + APPLIED_SHA_MARKER.length()).trim();
        return rest.matches("[0-9a-f]{64}") ? rest : null;
    }

    private static boolean shaMatches(String detail, Pattern pattern, String currentSha) {
        if (detail == null) return false;
        Matcher m = pattern.matcher(detail);
        if (!m.find()) return false;
        String recorded = m.group(1);
        return currentSha.regionMatches(true, 0, recorded, 0, recorded.length());
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8);
    }

    private void header(BufferedWriter logOut, SetupContext ctx, String alias) throws IOException {
        logOut.write("Timestamp:   " + Instant.now() + "\n");
        logOut.write("Target:      " + ctx.target().uri() + "\n");
        logOut.write("Alias:       " + alias + "\n");
        logOut.write("Source:      ~/" + REMOTE_WORK_DIR + "/" + REMOTE_DNS_YML + "\n");
        logOut.write("Config name: " + CONFIG_NAME + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    private record CapturedRun(int exit, String appliedSha) {}
}
