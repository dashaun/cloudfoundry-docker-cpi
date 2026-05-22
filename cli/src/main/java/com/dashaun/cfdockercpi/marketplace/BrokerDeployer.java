package com.dashaun.cfdockercpi.marketplace;

import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deploys the cf-docker-cpi-broker app into the running CF on the docker host:
 *  - locates the locally-built broker jar (`broker/target/cf-docker-cpi-broker-*.jar`)
 *  - scp's it to `~/.cf-docker-cpi-work/cf-docker-cpi-broker.jar` on the docker host
 *  - writes a manifest with the broker's env vars (BROKER_USERNAME, BROKER_PASSWORD,
 *    DOCKER_HOST, DOCKER_TLS_*_B64 — the last three sourced from `~/.cf-docker-cpi-work/tls/`)
 *  - creates a security group that permits TCP egress to `10.245.0.1/2376`
 *  - cf push'es the broker, binds the ASG to system/dev, restarts to pick it up
 *  - cf create-service-broker'es it as `cf-docker-cpi` (space-scoped to system/dev)
 *  - records the broker username / password / URL under {@link StatusStore#BROKER_KEY}
 *
 * <p>Idempotent at each step: re-running detects existing artifacts on the host and skips.
 */
@Component
public class BrokerDeployer {

    static final String NAME = "broker-deploy";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final String REMOTE_BROKER_JAR = REMOTE_WORK_DIR + "/cf-docker-cpi-broker.jar";
    static final String REMOTE_MANIFEST = REMOTE_WORK_DIR + "/cf-docker-cpi-broker-manifest.yml";
    static final String REMOTE_ASG = REMOTE_WORK_DIR + "/cf-docker-cpi-broker-asg.json";
    static final String CF_APP_NAME = "cf-docker-cpi-broker";
    static final String CF_BROKER_NAME = "cf-docker-cpi";
    static final String ASG_NAME = "cf-docker-cpi-broker-egress";
    static final String DOCKER_HOST = "tcp://10.245.0.1:2376";
    static final String DOCKER_NETWORK = "cf-docker-cpi-net";

    private final StatusStore statusStore;

    public BrokerDeployer(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    public StepResult deploy(SetupContext ctx, Optional<Path> brokerJarOverride) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("broker deploy supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Path brokerJar = brokerJarOverride.orElseGet(this::findLocalBrokerJar);
        if (brokerJar == null || !Files.isRegularFile(brokerJar)) {
            String detail = "broker jar not found; run `./mvnw -pl broker package` "
                    + "or pass --broker-jar <path>. Looked under: " + defaultJarSearchPaths();
            statusStore.putService(ctx.statusFile(), StatusStore.BROKER_KEY, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String brokerPassword = UUID.randomUUID().toString().replace("-", "");
        String brokerUrl = "https://" + CF_APP_NAME + "." + ctx.systemDomain();

        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, brokerJar, brokerUrl);

            logOut.write("[scp] " + brokerJar.getFileName() + " -> "
                    + userHost + ":~/" + REMOTE_BROKER_JAR + "\n");
            logOut.flush();
            scpTo(userHost, sshPort, brokerJar, REMOTE_BROKER_JAR);

            String script = bootstrapScript(ctx, brokerPassword, brokerUrl);
            int exit = streamRemote(userHost, sshPort, script, logOut);
            if (exit != 0) {
                String detail = "broker deploy failed (ssh exit " + exit + ")";
                statusStore.putService(ctx.statusFile(), StatusStore.BROKER_KEY, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String summary = "broker registered as " + CF_BROKER_NAME + " @ " + brokerUrl
                + " (user=" + CF_BROKER_NAME + ")";
        statusStore.putService(ctx.statusFile(), StatusStore.BROKER_KEY, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    public StepResult remove(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("broker remove supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile("broker-remove");
        Files.createDirectories(logFile.getParent());

        String script = teardownScript();
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            logOut.write("Timestamp:  " + Instant.now() + "\n");
            logOut.write("Target:     " + ctx.target().uri() + "\n\n");
            logOut.flush();
            int exit = streamRemote(ctx.target().sshUserHost(), ctx.target().sshPort(), script, logOut);
            if (exit != 0) {
                String detail = "broker remove failed (ssh exit " + exit + ")";
                statusStore.putService(ctx.statusFile(), StatusStore.BROKER_KEY, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }
        // Clear the broker entry so subsequent `setup broker deploy` runs fresh.
        statusStore.putService(ctx.statusFile(), StatusStore.BROKER_KEY,
                new StepStatus(StepStatus.Status.NEW, Instant.now().toString(),
                        "broker removed; redeploy with `broker deploy`"));
        return StepResult.ran("broker removed (log: " + logFile + ")");
    }

    /**
     * Bash heredoc that runs on the docker host. Idempotent: each piece checks state before
     * acting. Designed to be safe to re-run after partial failure (e.g. push succeeded but
     * cf create-service-broker didn't).
     */
    private String bootstrapScript(SetupContext ctx, String brokerPassword, String brokerUrl) {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "if [ ! -f tls/ca.pem ] || [ ! -f tls/client-cert.pem ] || [ ! -f tls/client-key.pem ]; then\n"
            + "  echo 'ERROR: tls/{ca,client-cert,client-key}.pem missing — broker needs these to talk to dockerd'; exit 78\n"
            + "fi\n"
            + "if [ ! -x ./bin/cf ]; then\n"
            + "  echo 'ERROR: ./bin/cf missing — run configure-cf-cli'; exit 78\n"
            + "fi\n"
            + "if [ ! -d cf-home ]; then\n"
            + "  echo 'ERROR: cf-home missing — run configure-cf-cli'; exit 78\n"
            + "fi\n"
            + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
            + "\n"
            + "echo '[broker] preparing ASG (egress to " + DOCKER_HOST + ")'\n"
            + "cat > " + asgBaseName() + " <<'ASG'\n"
            + "[\n"
            + "  { \"protocol\": \"tcp\", \"destination\": \"10.245.0.1\", \"ports\": \"2376\" }\n"
            + "]\n"
            + "ASG\n"
            + "if ./bin/cf security-group " + ASG_NAME + " >/dev/null 2>&1; then\n"
            + "  echo '[broker] ASG " + ASG_NAME + " already exists; updating'\n"
            + "  ./bin/cf update-security-group " + ASG_NAME + " " + asgBaseName() + "\n"
            + "else\n"
            + "  ./bin/cf create-security-group " + ASG_NAME + " " + asgBaseName() + "\n"
            + "fi\n"
            + "./bin/cf bind-security-group " + ASG_NAME + " system --space dev || true\n"
            + "\n"
            + "echo '[broker] base64-encoding TLS material from ~/" + REMOTE_WORK_DIR + "/tls/'\n"
            + "DOCKER_TLS_CA_B64=$(base64 -w0 tls/ca.pem)\n"
            + "DOCKER_TLS_CERT_B64=$(base64 -w0 tls/client-cert.pem)\n"
            + "DOCKER_TLS_KEY_B64=$(base64 -w0 tls/client-key.pem)\n"
            + "\n"
            + "cat > " + manifestBaseName() + " <<MANIFEST\n"
            + "---\n"
            + "applications:\n"
            + "- name: " + CF_APP_NAME + "\n"
            + "  path: " + brokerJarBaseName() + "\n"
            + "  memory: 1G\n"
            + "  instances: 1\n"
            + "  buildpacks: [java_buildpack]\n"
            + "  env:\n"
            + "    BROKER_USERNAME: " + CF_BROKER_NAME + "\n"
            + "    BROKER_PASSWORD: " + brokerPassword + "\n"
            + "    DOCKER_HOST: " + DOCKER_HOST + "\n"
            + "    DOCKER_NETWORK: " + DOCKER_NETWORK + "\n"
            + "    DOCKER_TLS_CA_B64: \"$DOCKER_TLS_CA_B64\"\n"
            + "    DOCKER_TLS_CERT_B64: \"$DOCKER_TLS_CERT_B64\"\n"
            + "    DOCKER_TLS_KEY_B64: \"$DOCKER_TLS_KEY_B64\"\n"
            + "    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 17.+ } }'\n"
            + "MANIFEST\n"
            + "\n"
            + "echo '[broker] cf push " + CF_APP_NAME + "'\n"
            + "./bin/cf push -f " + manifestBaseName() + "\n"
            + "\n"
            + "echo '[broker] restart to pick up ASG'\n"
            + "./bin/cf restart " + CF_APP_NAME + "\n"
            + "\n"
            + "echo '[broker] register service broker (space-scoped to system/dev)'\n"
            + "if ./bin/cf service-brokers --no-color | awk '{print $1}' | grep -qx " + CF_BROKER_NAME + "; then\n"
            + "  echo '[broker] " + CF_BROKER_NAME + " already registered — updating with the new password'\n"
            + "  ./bin/cf update-service-broker " + CF_BROKER_NAME + " " + CF_BROKER_NAME + " "
                    + brokerPassword + " " + brokerUrl + "\n"
            + "else\n"
            + "  ./bin/cf create-service-broker " + CF_BROKER_NAME + " " + CF_BROKER_NAME + " "
                    + brokerPassword + " " + brokerUrl + " --space-scoped\n"
            + "fi\n"
            + "\n"
            + "echo '[broker] listing marketplace as a sanity check'\n"
            + "./bin/cf marketplace\n";
    }

    private String teardownScript() {
        return "set -uo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
            + "echo '[broker] cf delete-service-broker " + CF_BROKER_NAME + "'\n"
            + "./bin/cf delete-service-broker -f " + CF_BROKER_NAME + " || true\n"
            + "echo '[broker] cf delete " + CF_APP_NAME + "'\n"
            + "./bin/cf delete -f -r " + CF_APP_NAME + " || true\n"
            + "echo '[broker] unbind + delete ASG " + ASG_NAME + "'\n"
            + "./bin/cf unbind-security-group " + ASG_NAME + " system dev || true\n"
            + "./bin/cf delete-security-group -f " + ASG_NAME + " || true\n"
            + "rm -f " + manifestBaseName() + " " + asgBaseName() + " " + brokerJarBaseName() + "\n";
    }

    /** Look in the conventional places relative to the cwd ([root, broker]). */
    private Path findLocalBrokerJar() {
        for (Path dir : defaultJarSearchDirs()) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> s =
                         Files.newDirectoryStream(dir, "cf-docker-cpi-broker-*.jar")) {
                for (Path p : s) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".original")) continue;
                    return p;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private Iterable<Path> defaultJarSearchDirs() {
        Path cwd = Paths.get("").toAbsolutePath();
        return java.util.List.of(
                cwd.resolve("broker").resolve("target"),
                cwd.resolve("target"),                  // if invoked from broker/
                cwd.getParent() == null ? cwd : cwd.getParent().resolve("broker").resolve("target"));
    }

    private String defaultJarSearchPaths() {
        StringBuilder b = new StringBuilder();
        for (Path d : defaultJarSearchDirs()) {
            if (b.length() > 0) b.append(", ");
            b.append(d);
        }
        return b.toString();
    }

    private void scpTo(String userHost, int sshPort, Path local, String remotePath)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                "-P", String.valueOf(sshPort),
                local.toString(),
                userHost + ":" + remotePath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("scp " + local + " -> " + userHost + ":" + remotePath
                    + " failed (exit " + exit + "): " + new String(out, StandardCharsets.UTF_8).trim());
        }
    }

    private int streamRemote(String userHost, int sshPort, String script, BufferedWriter logOut)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(sshPort),
                userHost,
                "bash -s");
        pb.redirectErrorStream(true);
        Process p = pb.start();
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

    private void header(BufferedWriter logOut, SetupContext ctx, Path brokerJar, String brokerUrl)
            throws IOException {
        logOut.write("Timestamp:     " + Instant.now() + "\n");
        logOut.write("Target:        " + ctx.target().uri() + "\n");
        logOut.write("Broker jar:    " + brokerJar + " (" + Files.size(brokerJar) / 1_000_000 + " MB)\n");
        logOut.write("Broker URL:    " + brokerUrl + "\n");
        logOut.write("App name:      " + CF_APP_NAME + "\n");
        logOut.write("Broker name:   " + CF_BROKER_NAME + " (space-scoped to system/dev)\n");
        logOut.write("ASG:           " + ASG_NAME + " → tcp 10.245.0.1:2376\n");
        logOut.write("\n");
        logOut.flush();
    }

    private static String manifestBaseName() {
        return REMOTE_MANIFEST.substring(REMOTE_MANIFEST.indexOf('/') + 1);
    }

    private static String brokerJarBaseName() {
        return REMOTE_BROKER_JAR.substring(REMOTE_BROKER_JAR.indexOf('/') + 1);
    }

    private static String asgBaseName() {
        return REMOTE_ASG.substring(REMOTE_ASG.indexOf('/') + 1);
    }
}
