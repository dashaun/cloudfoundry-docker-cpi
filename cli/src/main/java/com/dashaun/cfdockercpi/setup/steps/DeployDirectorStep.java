package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.ManifestVersions;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import com.dashaun.cfdockercpi.tooling.ToolingVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Component
public class DeployDirectorStep implements SetupStep {

    static final String NAME = "deploy-director";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final String DOCKER_NETWORK_NAME = "cf-docker-cpi-net";
    static final String DOCKER_NETWORK_GATEWAY = "10.245.0.1";
    // dockerd TLS endpoint reachable from inside cf-docker-cpi-net via the bridge gateway.
    // dockerd runs with --tlsverify; the CPI must present a client cert signed by the CA in tls/.
    static final String IN_CONTAINER_DOCKER_HOST = "tcp://" + DOCKER_NETWORK_GATEWAY + ":2376";

    private static final String BOSH_LINUX_AMD64_URL =
            "https://github.com/cloudfoundry/bosh-cli/releases/download/v" + ToolingVersions.BOSH_VERSION
                    + "/bosh-cli-" + ToolingVersions.BOSH_VERSION + "-linux-amd64";
    private static final String BOSH_LINUX_AMD64_SHA =
            "e9847375ba5397589e7b070305defc70321ad0e62d18b67a70a330efcab6e526";

    private final StatusStore statusStore;
    private final ObjectMapper json = new ObjectMapper();

    public DeployDirectorStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Run `bosh create-env` on the remote docker host to spin up the BOSH director container "
                + "(with UAA + CredHub colocated so runtime-config variables can be generated).";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        Path state = ctx.stateDir().resolve("director-state.json");
        if (!Files.isRegularFile(state)) return StepCheck.NEEDS_RUN;
        try {
            JsonNode node = json.readTree(state.toFile());
            JsonNode cid = node.get("current_vm_cid");
            if (cid == null || cid.asText("").isBlank()) return StepCheck.NEEDS_RUN;
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("deploy-director v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        String bootstrap = bootstrapScript(ctx);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, userHost, sshPort);

            int exit = streamRemote(userHost, sshPort, bootstrap, logOut);
            if (exit != 0) {
                String detail = "bosh create-env failed (ssh exit " + exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }

            logOut.write("\n[scp] pulling state files back to laptop\n");
            logOut.flush();
            scpBack(userHost, sshPort, REMOTE_WORK_DIR + "/director-state.json",
                    ctx.stateDir().resolve("director-state.json"));
            scpBack(userHost, sshPort, REMOTE_WORK_DIR + "/director-creds.yml",
                    ctx.stateDir().resolve("director-creds.yml"));
        }

        String cid = readVmCid(ctx.stateDir().resolve("director-state.json"));
        if (cid.isBlank()) {
            String detail = "deploy completed but director-state.json has no current_vm_cid";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }
        String summary = "director container " + shortCid(cid) + " up at " + ctx.directorIp();
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String bootstrapScript(SetupContext ctx) {
        return "set -euo pipefail\n"
            + "mkdir -p ~/" + REMOTE_WORK_DIR + "\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "# bosh create-env shells out to ruby to render ERB templates\n"
            + "if ! command -v ruby >/dev/null 2>&1; then\n"
            + "  echo \"ERROR: ruby is required by bosh create-env but is not on PATH on this host.\"\n"
            + "  echo \"Install it (Ubuntu/WSL2):  sudo apt-get update && sudo apt-get install -y ruby\"\n"
            + "  exit 78\n"
            + "fi\n"
            + "echo \"[bootstrap] ruby: $(ruby --version)\"\n"
            + "\n"
            + "# Install bosh CLI (idempotent)\n"
            + "BOSH_PINNED='version " + ToolingVersions.BOSH_VERSION + "'\n"
            + "if ! [ -x ./bin/bosh ] || ! ./bin/bosh --version 2>/dev/null | grep -q \"$BOSH_PINNED\"; then\n"
            + "  mkdir -p bin\n"
            + "  echo \"[bootstrap] downloading bosh\"\n"
            + "  curl -fsSL -o bin/bosh '" + BOSH_LINUX_AMD64_URL + "'\n"
            + "  echo '" + BOSH_LINUX_AMD64_SHA + "  bin/bosh' | sha256sum -c\n"
            + "  chmod +x bin/bosh\n"
            + "fi\n"
            + "\n"
            + "# Clone bosh-deployment at pinned SHA (idempotent)\n"
            + "if [ ! -d bosh-deployment/.git ]; then\n"
            + "  echo \"[bootstrap] cloning bosh-deployment\"\n"
            + "  git clone --quiet " + ManifestVersions.BOSH_DEPLOYMENT_REPO + "\n"
            + "fi\n"
            + "( cd bosh-deployment && git fetch --quiet origin && git checkout --quiet "
                    + ManifestVersions.BOSH_DEPLOYMENT_SHA + " )\n"
            + "\n"
            + "# Ensure docker network exists\n"
            + "if ! docker network inspect " + DOCKER_NETWORK_NAME + " >/dev/null 2>&1; then\n"
            + "  echo \"[bootstrap] creating docker network " + DOCKER_NETWORK_NAME + "\"\n"
            + "  docker network create -d bridge --subnet=" + ctx.internalCidr()
                    + " --gateway=" + DOCKER_NETWORK_GATEWAY + " " + DOCKER_NETWORK_NAME + "\n"
            + "fi\n"
            + "\n"
            + "# Write director-vars.yml (regenerated each run)\n"
            + "cat > director-vars.yml <<EOF\n"
            + GenerateDirectorVarsStep.renderVars(ctx)
            + "EOF\n"
            + "\n"
            + "# Precheck: dockerd TLS material must be on the host. We don't generate it here\n"
            + "# (cert lifecycle is a separate host-setup concern); see docs/setup-pipeline.md §5.\n"
            + "for f in tls/ca.pem tls/client-cert.pem tls/client-key.pem; do\n"
            + "  if [ ! -f \"$f\" ]; then\n"
            + "    echo \"ERROR: $(pwd)/$f missing. Generate dockerd TLS certs into ~/" + REMOTE_WORK_DIR + "/tls/\"\n"
            + "    echo \"       (CA + client cert/key signed by the same CA that dockerd uses).\"\n"
            + "    exit 78\n"
            + "  fi\n"
            + "done\n"
            + "\n"
            + "# Custom ops:\n"
            + "#  * instance_groups CPI (runs inside the director container) talks to dockerd over\n"
            + "#    TLS-on-TCP via the bridge gateway. dockerd has --tlsverify enabled, so we inject\n"
            + "#    the real CA + client cert/key (signed by that CA) via --var-file.\n"
            + "#  * cloud_provider CPI (invoked by `bosh create-env` directly on the docker host)\n"
            + "#    keeps the unix socket. The tls block is required by bosh-docker-cpi 0.2.12's\n"
            + "#    cpi.json.erb (which reads p('docker_cpi.docker.tls.ca') unconditionally) but\n"
            + "#    is ignored at runtime because unix:// never negotiates TLS — dummy auto-\n"
            + "#    generated certs are fine there.\n"
            + "cat > docker-cpi-overrides.yml <<'OPS'\n"
            + "- type: replace\n"
            + "  path: /variables?/-\n"
            + "  value:\n"
            + "    name: cf_docker_cpi_dummy_ca\n"
            + "    type: certificate\n"
            + "    options:\n"
            + "      is_ca: true\n"
            + "      common_name: cf-docker-cpi-dummy-ca\n"
            + "- type: replace\n"
            + "  path: /variables?/-\n"
            + "  value:\n"
            + "    name: cf_docker_cpi_dummy_tls\n"
            + "    type: certificate\n"
            + "    options:\n"
            + "      ca: cf_docker_cpi_dummy_ca\n"
            + "      common_name: cf-docker-cpi-dummy\n"
            + "- type: replace\n"
            + "  path: /instance_groups/name=bosh/properties/docker_cpi/docker/host\n"
            + "  value: " + IN_CONTAINER_DOCKER_HOST + "\n"
            + "- type: replace\n"
            + "  path: /instance_groups/name=bosh/properties/docker_cpi/docker/tls?\n"
            + "  value:\n"
            + "    ca: ((cf_docker_cpi_tls_ca))\n"
            + "    certificate: ((cf_docker_cpi_tls_cert))\n"
            + "    private_key: ((cf_docker_cpi_tls_key))\n"
            + "- type: replace\n"
            + "  path: /cloud_provider/properties/docker_cpi/docker/host\n"
            + "  value: unix:///var/run/docker.sock\n"
            + "- type: replace\n"
            + "  path: /cloud_provider/properties/docker_cpi/docker/tls?\n"
            + "  value:\n"
            + "    ca: ((cf_docker_cpi_dummy_ca.certificate))\n"
            + "    certificate: ((cf_docker_cpi_dummy_tls.certificate))\n"
            + "    private_key: ((cf_docker_cpi_dummy_tls.private_key))\n"
            + "OPS\n"
            + "\n"
            + "echo \"[bootstrap] starting bosh create-env\"\n"
            + "./bin/bosh create-env bosh-deployment/bosh.yml \\\n"
            + "  -o bosh-deployment/docker/cpi.yml \\\n"
            + "  -o docker-cpi-overrides.yml \\\n"
            + "  -o bosh-deployment/jumpbox-user.yml \\\n"
            + "  -o bosh-deployment/uaa.yml \\\n"
            + "  -o bosh-deployment/credhub.yml \\\n"
            + "  --vars-store director-creds.yml \\\n"
            + "  --state director-state.json \\\n"
            + "  --vars-file director-vars.yml \\\n"
            + "  --var-file cf_docker_cpi_tls_ca=tls/ca.pem \\\n"
            + "  --var-file cf_docker_cpi_tls_cert=tls/client-cert.pem \\\n"
            + "  --var-file cf_docker_cpi_tls_key=tls/client-key.pem \\\n"
            + "  -v docker_host=/var/run/docker.sock \\\n"
            + "  -v network=" + DOCKER_NETWORK_NAME + "\n";
    }

    private int streamRemote(String userHost, int sshPort, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(sshPort),
                userHost,
                "bash -s");
        pb.redirectErrorStream(true);
        Process p = pb.start();
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

    private String readVmCid(Path state) {
        try {
            JsonNode node = json.readTree(state.toFile());
            JsonNode cid = node.get("current_vm_cid");
            return cid == null ? "" : cid.asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private String shortCid(String cid) {
        return cid.length() > 12 ? cid.substring(0, 12) : cid;
    }

    private void header(BufferedWriter logOut, SetupContext ctx, String userHost, int sshPort) throws IOException {
        logOut.write("Timestamp:   " + Instant.now() + "\n");
        logOut.write("Target:      ssh://" + userHost + ":" + sshPort + "\n");
        logOut.write("Remote dir:  ~/" + REMOTE_WORK_DIR + "\n");
        logOut.write("Director IP: " + ctx.directorIp() + "\n");
        logOut.write("Internal:    " + ctx.internalCidr() + "\n");
        logOut.write("Network:     " + DOCKER_NETWORK_NAME + "\n");
        logOut.write("\n");
        logOut.flush();
    }
}
