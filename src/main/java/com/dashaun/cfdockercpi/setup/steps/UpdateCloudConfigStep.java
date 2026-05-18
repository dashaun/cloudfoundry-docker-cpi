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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UpdateCloudConfigStep implements SetupStep {

    static final String NAME = "update-cloud-config";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final Path LOCAL_CLOUD_CONFIG_REL =
            Path.of("cf-deployment", "iaas-support", "bosh-lite", "cloud-config.yml");
    static final String REMOTE_CLOUD_CONFIG = "cloud-config.yml";
    static final String REMOTE_CLOUD_CONFIG_OVERRIDES = "cloud-config-docker-cpi-overrides.yml";
    static final String REMOTE_CLOUD_CONFIG_RENDERED = "cloud-config-rendered.yml";
    static final String APPLIED_SHA_MARKER = "[applied-sha]";

    // bosh-docker-cpi 0.2.12 quirks vs cf-deployment v56.4.0 bosh-lite cloud-config:
    //  * `ports` must be []string ("80", "443") — cf-deployment emits [{host: 80}, ...] (modern shape).
    //  * Port range syntax "1024-1123" is rejected by docker's port parser; drop it (tcp routing
    //    isn't exercised by smoke-push, and bosh-lite doesn't bind it anyway).
    //  * Network `cloud_properties.name: random` creates a per-deploy isolated docker network,
    //    which the director (on cf-docker-cpi-net) can't reach. Force VMs onto cf-docker-cpi-net
    //    so the director can speak NATS to their agents; subnet/static range collapses to /24.
    private static final String CLOUD_CONFIG_OVERRIDES = ""
            + "- type: replace\n"
            + "  path: /networks/name=default/subnets\n"
            + "  value:\n"
            + "  - azs: [z1, z2, z3]\n"
            + "    cloud_properties:\n"
            + "      name: cf-docker-cpi-net\n"
            + "    gateway: 10.245.0.1\n"
            + "    range: 10.245.0.0/24\n"
            + "    reserved:\n"
            + "    - 10.245.0.1\n"
            + "    - 10.245.0.11\n"
            + "    static:\n"
            + "    - 10.245.0.12 - 10.245.0.99\n"
            + "- type: replace\n"
            + "  path: /vm_extensions/name=ssh-proxy-and-router-lb/cloud_properties/ports\n"
            + "  value: [\"80\", \"443\", \"2222\"]\n"
            + "- type: replace\n"
            + "  path: /vm_extensions/name=cf-tcp-router-network-properties/cloud_properties?\n"
            + "  value:\n"
            + "    ports: []\n";

    private static final Pattern DETAIL_FILE_SHA = Pattern.compile("file_sha=([0-9a-f]{8,64})");
    private static final Pattern DETAIL_APPLIED_SHA = Pattern.compile("applied_sha=([0-9a-f]{8,64})");

    private final StatusStore statusStore;

    public UpdateCloudConfigStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Apply cf-deployment's bosh-lite cloud-config to the director.";
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
        String detail = recorded.get().detail();
        Path local = ctx.stateDir().resolve(LOCAL_CLOUD_CONFIG_REL);
        if (!Files.isRegularFile(local)) return StepCheck.NEEDS_RUN;

        String currentFileSha;
        try {
            currentFileSha = sha256(local);
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }
        if (!shaMatches(detail, DETAIL_FILE_SHA, currentFileSha)) {
            return StepCheck.NEEDS_RUN;
        }

        if (ctx.verify()) {
            if (!ctx.target().isSsh()) return StepCheck.NEEDS_RUN;
            try {
                String applied = remoteAppliedSha(ctx);
                if (applied == null) return StepCheck.NEEDS_RUN;
                if (!shaMatches(detail, DETAIL_APPLIED_SHA, applied)) return StepCheck.NEEDS_RUN;
            } catch (IOException | InterruptedException e) {
                return StepCheck.NEEDS_RUN;
            }
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("update-cloud-config v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path local = ctx.stateDir().resolve(LOCAL_CLOUD_CONFIG_REL);
        if (!Files.isRegularFile(local)) {
            return StepResult.failed(local + " not found — run fetch-manifests first");
        }
        String fileSha = sha256(local);

        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        scpTo(userHost, sshPort, local, REMOTE_WORK_DIR + "/" + REMOTE_CLOUD_CONFIG);

        String appliedSha;
        String bootstrap = bootstrapScript(alias);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, userHost, sshPort, alias, local, fileSha);
            CapturedRun result = streamRemote(userHost, sshPort, bootstrap, logOut);
            if (result.exit != 0) {
                String detail = "bosh update-cloud-config failed (ssh exit " + result.exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
            appliedSha = result.appliedSha;
            if (appliedSha == null) {
                String detail = "bosh update-cloud-config succeeded but applied SHA was not emitted";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String detail = "file_sha=" + shortSha(fileSha) + " applied_sha=" + shortSha(appliedSha);
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
            + "if [ ! -f " + REMOTE_CLOUD_CONFIG + " ]; then\n"
            + "  echo \"ERROR: " + REMOTE_CLOUD_CONFIG + " missing on remote — scp from laptop failed\"; exit 78\n"
            + "fi\n"
            + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "\n"
            + "cat > " + REMOTE_CLOUD_CONFIG_OVERRIDES + " <<'OPS'\n"
            + CLOUD_CONFIG_OVERRIDES
            + "OPS\n"
            + "\n"
            + "echo \"[cloud-config] rendering " + REMOTE_CLOUD_CONFIG + " + "
                    + REMOTE_CLOUD_CONFIG_OVERRIDES + "\"\n"
            + "./bin/bosh interpolate " + REMOTE_CLOUD_CONFIG
                    + " -o " + REMOTE_CLOUD_CONFIG_OVERRIDES
                    + " > " + REMOTE_CLOUD_CONFIG_RENDERED + "\n"
            + "\n"
            + "echo \"[cloud-config] applying " + REMOTE_CLOUD_CONFIG_RENDERED + "\"\n"
            + "./bin/bosh -e " + alias + " update-cloud-config " + REMOTE_CLOUD_CONFIG_RENDERED
                    + " --no-color --non-interactive\n"
            + "\n"
            + "echo \"[cloud-config] verifying applied config on director\"\n"
            + "APPLIED_SHA=\"$(./bin/bosh -e " + alias + " cloud-config --no-color | sha256sum | awk '{print $1}')\"\n"
            + "echo \"" + APPLIED_SHA_MARKER + " ${APPLIED_SHA}\"\n";
    }

    private CapturedRun streamRemote(String userHost, int sshPort, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        Process p = startSshBash(userHost, sshPort);
        try (var stdin = p.getOutputStream()) {
            stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        }
        String appliedSha = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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
            + "./bin/bosh -e " + alias + " cloud-config --no-color | sha256sum | awk '{print $1}'\n";
        Process p = startSshBash(ctx.target().sshUserHost(), ctx.target().sshPort());
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String sha = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.matches("[0-9a-f]{64}")) sha = trimmed;
            }
        }
        if (p.waitFor() != 0) return null;
        return sha;
    }

    private Process startSshBash(String userHost, int sshPort) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(sshPort),
                userHost,
                "bash -s");
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private void scpTo(String userHost, int sshPort, Path localPath, String remotePath)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "scp",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-P", String.valueOf(sshPort),
                localPath.toString(),
                userHost + ":" + remotePath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("scp " + localPath + " -> " + userHost + ":" + remotePath
                    + " failed (exit " + exit + "): " + new String(out, StandardCharsets.UTF_8).trim());
        }
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

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8);
    }

    private void header(BufferedWriter logOut, String userHost, int sshPort, String alias,
                        Path local, String fileSha) throws IOException {
        logOut.write("Timestamp:   " + Instant.now() + "\n");
        logOut.write("Target:      ssh://" + userHost + ":" + sshPort + "\n");
        logOut.write("Alias:       " + alias + "\n");
        logOut.write("Local file:  " + local + "\n");
        logOut.write("File SHA:    " + fileSha + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    private record CapturedRun(int exit, String appliedSha) {}
}
