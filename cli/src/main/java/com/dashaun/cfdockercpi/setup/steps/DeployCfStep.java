package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.ManifestVersions;
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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DeployCfStep implements SetupStep {

    static final String NAME = "deploy-cf";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final String DEPLOYMENT_NAME = "cf";
    static final String REMOTE_DEPLOY_OVERRIDES = "cf-deployment-docker-cpi-overrides.yml";
    static final String ROUTER_STATIC_IP = "10.245.0.34";
    static final long MIN_MEM_BYTES = 16L * 1024 * 1024 * 1024;   // 16 GiB
    static final long MIN_DISK_BYTES = 50L * 1024 * 1024 * 1024;  // 50 GiB

    private static final Pattern ADMIN_PW_PRESENT =
            Pattern.compile("(?m)^cf_admin_password:\\s+\\S+");

    // cf-deployment's operations/bosh-lite.yml hard-codes 10.244.0.34 for the haproxy router
    // (BOSH bosh-lite's historic IP). Our cloud-config moved the subnet to 10.245.0.0/24
    // (see UpdateCloudConfigStep), so the static IP and the load_balancer security group
    // rule both need to track that. Applied AFTER bosh-lite.yml in the deploy chain.
    private static final String DEPLOY_OVERRIDES = ""
            + "- type: replace\n"
            + "  path: /instance_groups/name=router/networks/name=default/static_ips\n"
            + "  value: [" + ROUTER_STATIC_IP + "]\n"
            + "- type: replace\n"
            + "  path: /instance_groups/name=api/jobs/name=cloud_controller_ng/properties"
                    + "/cc/security_group_definitions/name=load_balancer/rules/0/destination\n"
            + "  value: " + ROUTER_STATIC_IP + "\n";

    private final StatusStore statusStore;

    public DeployCfStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "bosh deploy cf-deployment with bosh-lite + use-compiled-releases ops files (the long step).";
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
        Path creds = ctx.stateDir().resolve("cf-creds.yml");
        if (!Files.isRegularFile(creds)) return StepCheck.NEEDS_RUN;
        try {
            String body = Files.readString(creds);
            if (!ADMIN_PW_PRESENT.matcher(body).find()) return StepCheck.NEEDS_RUN;
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }

        if (ctx.verify()) {
            if (!ctx.target().isSsh()) return StepCheck.NEEDS_RUN;
            try {
                if (!remoteDeploymentRegistered(ctx)) return StepCheck.NEEDS_RUN;
            } catch (IOException | InterruptedException e) {
                return StepCheck.NEEDS_RUN;
            }
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("deploy-cf v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        ResourceProbe probe;
        try {
            probe = probeRemoteResources(ctx);
        } catch (IOException | InterruptedException e) {
            String detail = "resource precheck failed to query remote: " + e.getMessage();
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }
        String resourceSummary = "MemTotal=" + formatGiB(probe.memBytes)
                + " DiskFree=" + formatGiB(probe.diskFreeBytes) + " @ " + probe.dockerRootDir;
        if (!ctx.ignoreResourceCheck()) {
            String resourceFailure = enforceResources(probe);
            if (resourceFailure != null) {
                Files.writeString(logFile, "Timestamp: " + Instant.now() + "\n"
                        + resourceSummary + "\n" + resourceFailure + "\n");
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(resourceFailure));
                return StepResult.failed(resourceFailure + " (log: " + logFile + ")");
            }
        }

        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String bootstrap = bootstrapScript(ctx);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, userHost, sshPort, resourceSummary);
            int exit = streamRemote(userHost, sshPort, bootstrap, logOut);
            if (exit != 0) {
                String detail = "bosh deploy failed (ssh exit " + exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
            logOut.write("\n[scp] pulling cf-creds.yml back to laptop\n");
            logOut.flush();
            scpBack(userHost, sshPort, REMOTE_WORK_DIR + "/cf-creds.yml",
                    ctx.stateDir().resolve("cf-creds.yml"));
        }

        Path creds = ctx.stateDir().resolve("cf-creds.yml");
        String credsBody = Files.readString(creds);
        if (!ADMIN_PW_PRESENT.matcher(credsBody).find()) {
            String detail = "bosh deploy returned 0 but cf-creds.yml has no cf_admin_password";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        String summary = DEPLOYMENT_NAME + " deployed (system_domain=" + ctx.systemDomain() + ")";
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String bootstrapScript(SetupContext ctx) {
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "if [ ! -f director-creds.yml ]; then\n"
            + "  echo \"ERROR: director-creds.yml missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "if ! [ -x ./bin/bosh ]; then\n"
            + "  echo \"ERROR: ./bin/bosh missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "\n"
            + "# Clone cf-deployment at pinned SHA (idempotent).\n"
            + "if [ ! -d cf-deployment/.git ]; then\n"
            + "  echo \"[bootstrap] cloning cf-deployment\"\n"
            + "  git clone --quiet " + ManifestVersions.CF_DEPLOYMENT_REPO + "\n"
            + "fi\n"
            + "( cd cf-deployment && git fetch --quiet origin && git checkout --quiet "
                    + ManifestVersions.CF_DEPLOYMENT_SHA + " )\n"
            + "\n"
            + "cat > " + REMOTE_DEPLOY_OVERRIDES + " <<'OPS'\n"
            + DEPLOY_OVERRIDES
            + "OPS\n"
            + "\n"
            + "echo \"[deploy-cf] bosh -d " + DEPLOYMENT_NAME + " deploy (system_domain="
                    + ctx.systemDomain() + ", router_ip=" + ROUTER_STATIC_IP + ")\"\n"
            + "./bin/bosh -e " + alias + " -d " + DEPLOYMENT_NAME + " deploy \\\n"
            + "  cf-deployment/cf-deployment.yml \\\n"
            + "  -o cf-deployment/operations/bosh-lite.yml \\\n"
            + "  -o cf-deployment/operations/use-compiled-releases.yml \\\n"
            + "  -o " + REMOTE_DEPLOY_OVERRIDES + " \\\n"
            + "  --vars-store cf-creds.yml \\\n"
            + "  -v system_domain=" + ctx.systemDomain() + " \\\n"
            + "  --no-color --non-interactive\n"
            + "\n"
            + "echo \"[deploy-cf] post-deploy snapshot\"\n"
            + "./bin/bosh -e " + alias + " -d " + DEPLOYMENT_NAME + " deployment --no-color\n";
    }

    private boolean remoteDeploymentRegistered(SetupContext ctx) throws IOException, InterruptedException {
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        String script = "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "./bin/bosh -e " + alias + " -d " + DEPLOYMENT_NAME + " deployment --no-color >/dev/null\n";
        return runRemote(ctx, script).exit == 0;
    }

    private ResourceProbe probeRemoteResources(SetupContext ctx) throws IOException, InterruptedException {
        String script = "set -euo pipefail\n"
            + "MEM=$(docker info --format '{{.MemTotal}}' 2>/dev/null)\n"
            + "ROOT=$(docker info --format '{{.DockerRootDir}}' 2>/dev/null)\n"
            + "AVAIL=$(df -B1 --output=avail \"$ROOT\" 2>/dev/null | tail -n1 | tr -d ' ')\n"
            + "echo \"MEM=$MEM\"\n"
            + "echo \"ROOT=$ROOT\"\n"
            + "echo \"AVAIL=$AVAIL\"\n";
        CapturedRun r = runRemote(ctx, script);
        if (r.exit != 0) {
            throw new IOException("remote resource probe exited " + r.exit + ": " + r.output.trim());
        }
        long mem = 0;
        long avail = 0;
        String root = "";
        for (String line : r.output.split("\n")) {
            Matcher m;
            if ((m = Pattern.compile("^MEM=(\\d+)$").matcher(line.trim())).matches()) {
                mem = Long.parseLong(m.group(1));
            } else if ((m = Pattern.compile("^ROOT=(.+)$").matcher(line.trim())).matches()) {
                root = m.group(1);
            } else if ((m = Pattern.compile("^AVAIL=(\\d+)$").matcher(line.trim())).matches()) {
                avail = Long.parseLong(m.group(1));
            }
        }
        if (mem == 0 || avail == 0 || root.isEmpty()) {
            throw new IOException("remote resource probe parsed incomplete output:\n" + r.output);
        }
        return new ResourceProbe(mem, avail, root);
    }

    private String enforceResources(ResourceProbe probe) {
        if (probe.memBytes < MIN_MEM_BYTES) {
            return "host has " + formatGiB(probe.memBytes)
                    + " RAM; deploy-cf needs >= " + formatGiB(MIN_MEM_BYTES)
                    + " (override with --ignore-resource-check)";
        }
        if (probe.diskFreeBytes < MIN_DISK_BYTES) {
            return "host has " + formatGiB(probe.diskFreeBytes)
                    + " free at " + probe.dockerRootDir
                    + "; deploy-cf needs >= " + formatGiB(MIN_DISK_BYTES)
                    + " (override with --ignore-resource-check)";
        }
        return null;
    }

    private CapturedRun runRemote(SetupContext ctx, String script) throws IOException, InterruptedException {
        Process p = startSshBash(ctx.target().sshUserHost(), ctx.target().sshPort());
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        return new CapturedRun(p.waitFor(), new String(out, StandardCharsets.UTF_8));
    }

    private int streamRemote(String userHost, int sshPort, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        Process p = startSshBash(userHost, sshPort);
        try (var stdin = p.getOutputStream()) {
            stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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

    private void scpBack(String userHost, int sshPort, String remotePath, Path localPath)
            throws IOException, InterruptedException {
        Files.createDirectories(localPath.getParent());
        Path tmp = Files.createTempFile(localPath.getFileName().toString(), ".scp");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "scp",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=10",
                    "-P", String.valueOf(sshPort),
                    userHost + ":" + remotePath,
                    tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("scp " + remotePath + " failed (exit " + exit + "): "
                        + new String(out, StandardCharsets.UTF_8).trim());
            }
            Files.move(tmp, localPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void header(BufferedWriter logOut, SetupContext ctx, String userHost, int sshPort,
                        String resourceSummary) throws IOException {
        logOut.write("Timestamp:     " + Instant.now() + "\n");
        logOut.write("Target:        ssh://" + userHost + ":" + sshPort + "\n");
        logOut.write("Remote dir:    ~/" + REMOTE_WORK_DIR + "\n");
        logOut.write("Director:      " + SetupContext.DEFAULT_DIRECTOR_NAME + " @ " + ctx.directorIp() + "\n");
        logOut.write("Deployment:    " + DEPLOYMENT_NAME + "\n");
        logOut.write("system_domain: " + ctx.systemDomain() + "\n");
        logOut.write("cf-deployment: " + ManifestVersions.CF_DEPLOYMENT_SHA + " (" + ManifestVersions.CF_DEPLOYMENT_TAG + ")\n");
        logOut.write("Resources:     " + resourceSummary
                + (ctx.ignoreResourceCheck() ? "  [precheck bypassed]" : "") + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    private static String formatGiB(long bytes) {
        double gib = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format("%.1f GiB", gib);
    }

    private record ResourceProbe(long memBytes, long diskFreeBytes, String dockerRootDir) {}

    private record CapturedRun(int exit, String output) {}
}
