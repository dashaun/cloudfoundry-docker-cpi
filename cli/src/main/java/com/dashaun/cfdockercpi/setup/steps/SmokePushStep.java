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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Builds a tiny Spring Boot app locally (the laptop has mvnw + JDK; the docker host usually
// doesn't), scp's the jar to the docker host, and drives `cf push` + the HTTP probe from
// there — same docker-host-side architecture as configure-cf-cli (issue #20).
@Component
public class SmokePushStep implements SetupStep {

    static final String NAME = "smoke-push";
    static final String APP_NAME = "cf-smoke";
    static final String APP_DIR = "cf-smoke";
    static final String REMOTE_WORK_DIR = ConfigureCfCliStep.REMOTE_WORK_DIR;
    static final String REMOTE_CF_BIN = ConfigureCfCliStep.REMOTE_CF_BIN;
    static final String REMOTE_CF_HOME = ConfigureCfCliStep.REMOTE_CF_HOME;
    static final String REMOTE_JAR = REMOTE_WORK_DIR + "/cf-smoke.jar";
    static final String REMOTE_MANIFEST = REMOTE_WORK_DIR + "/cf-smoke-manifest.yml";
    static final String STARTER_URL =
            "https://start.spring.io/starter.zip"
                    + "?type=maven-project"
                    + "&dependencies=web,actuator"
                    + "&name=cf-smoke"
                    + "&packageName=com.dashaun.smoke"
                    + "&groupId=com.dashaun"
                    + "&artifactId=cf-smoke"
                    + "&javaVersion=17";
    static final String JBP_OPEN_JDK = "{ jre: { version: 17.+ } }";
    static final String HEALTH_PATH = "/actuator/health";
    static final int HEALTH_TIMEOUT_SECONDS = 120;

    private final StatusStore statusStore;

    public SmokePushStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Build a Spring Boot starter locally, scp the jar to the docker host, "
                + "`cf push` it from the host, then HTTP 200-probe `/actuator/health`.";
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
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("smoke-push v2 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Path appDir = ctx.stateDir().resolve(APP_DIR);
        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String routeHost = APP_NAME + "." + ctx.systemDomain();
        String healthUrl = "https://" + routeHost + HEALTH_PATH;

        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, appDir, routeHost);

            // 1. Fetch the starter (idempotent — keeps existing pom.xml).
            if (!Files.isRegularFile(appDir.resolve("pom.xml"))) {
                logOut.write("[fetch] " + STARTER_URL + "\n");
                logOut.flush();
                fetchStarter(appDir);
            } else {
                logOut.write("[fetch] reusing existing " + appDir + "/pom.xml\n");
                logOut.flush();
            }

            // 2. Build with the starter's bundled mvnw (JDK on the laptop, not the host).
            logOut.write("\n[build] ./mvnw package -DskipTests\n");
            logOut.flush();
            int mvnExit = streamCommand(appDir, Map.of(), logOut,
                    appDir.resolve("mvnw").toString(), "package", "-DskipTests");
            if (mvnExit != 0) {
                return failRun(ctx, logFile, "./mvnw package failed (exit " + mvnExit + ")");
            }
            Path jar = findJar(appDir.resolve("target"));
            if (jar == null) {
                return failRun(ctx, logFile, "build succeeded but no cf-smoke-*.jar under "
                        + appDir.resolve("target"));
            }
            logOut.write("[build] " + jar.getFileName() + "\n\n");
            logOut.flush();

            // 3. scp the jar to the docker host.
            logOut.write("[scp] " + jar.getFileName() + " -> " + userHost + ":~/" + REMOTE_JAR + "\n");
            logOut.flush();
            scpTo(userHost, sshPort, jar, REMOTE_JAR);

            // 4. Run cf push + curl probe via a single bash blob on the host.
            String script = pushAndProbeScript(healthUrl);
            int exit = streamRemote(ctx, script, logOut);
            if (exit != 0) {
                return failRun(ctx, logFile, "cf push / probe failed on the docker host (ssh exit " + exit + ")");
            }
        }

        String summary = APP_NAME + " up at " + healthUrl;
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String pushAndProbeScript(String healthUrl) {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "if ! [ -x ./bin/cf ]; then\n"
            + "  echo \"ERROR: ~/" + REMOTE_CF_BIN + " missing — run configure-cf-cli\"; exit 78\n"
            + "fi\n"
            + "if ! [ -d cf-home ]; then\n"
            + "  echo \"ERROR: ~/" + REMOTE_CF_HOME + " missing — run configure-cf-cli\"; exit 78\n"
            + "fi\n"
            + "\n"
            + "cat > " + manifestBaseName() + " <<'MANIFEST'\n"
            + manifestYaml()
            + "MANIFEST\n"
            + "\n"
            + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
            + "echo \"[cf] push " + APP_NAME + "\"\n"
            + "./bin/cf push " + APP_NAME + " -f " + manifestBaseName() + " -p cf-smoke.jar\n"
            + "\n"
            + "echo \"[probe] GET " + healthUrl + " (up to " + HEALTH_TIMEOUT_SECONDS + "s)\"\n"
            + "deadline=$(( $(date +%s) + " + HEALTH_TIMEOUT_SECONDS + " ))\n"
            + "attempt=0\n"
            + "while :; do\n"
            + "  attempt=$((attempt + 1))\n"
            + "  code=$(curl -sk -o /tmp/cf-smoke-health.json -w '%{http_code}' --max-time 5 '"
                    + healthUrl + "' || echo 000)\n"
            + "  if [ \"$code\" = '200' ]; then\n"
            + "    body=$(cat /tmp/cf-smoke-health.json)\n"
            + "    echo \"[probe] attempt $attempt: 200 OK — body: $body\"\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  echo \"[probe] attempt $attempt: HTTP $code\"\n"
            + "  if [ \"$(date +%s)\" -ge \"$deadline\" ]; then\n"
            + "    echo \"ERROR: " + healthUrl + " never returned 200 (last=$code)\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  sleep 3\n"
            + "done\n";
    }

    private void fetchStarter(Path appDir) throws IOException {
        Files.createDirectories(appDir);
        URL url = URI.create(STARTER_URL).toURL();
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "cf-docker-cpi/0.1");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException("start.spring.io returned HTTP " + code);
        }
        try (ZipInputStream zin = new ZipInputStream(c.getInputStream())) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                // start.spring.io zips entries flat (no top-level project dir wrapping them
                // — pom.xml, mvnw, src/main/..., .mvn/wrapper/... are at the root). Write
                // them under appDir directly.
                String name = e.getName();
                if (name.isEmpty() || name.equals("/")) continue;
                Path dest = appDir.resolve(name).normalize();
                if (!dest.startsWith(appDir)) {
                    throw new IOException("zip entry escaped target dir: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zin, dest);
                    if (name.equals("mvnw")) {
                        dest.toFile().setExecutable(true, false);
                    }
                }
            }
        }
    }

    private String manifestYaml() {
        return ""
            + "---\n"
            + "applications:\n"
            + "- name: " + APP_NAME + "\n"
            + "  memory: 1G\n"
            + "  instances: 1\n"
            + "  path: cf-smoke.jar\n"
            + "  env:\n"
            + "    JBP_CONFIG_OPEN_JDK_JRE: '" + JBP_OPEN_JDK + "'\n";
    }

    private static String manifestBaseName() {
        // We write the manifest inside the remote work dir; cf reads from CWD, so a bare name
        // is enough. Kept a distinct file name (vs `manifest.yml`) to avoid colliding with
        // anything else under ~/.cf-docker-cpi-work/.
        return REMOTE_MANIFEST.substring(REMOTE_MANIFEST.indexOf('/') + 1);
    }

    private Path findJar(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) return null;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(targetDir, "cf-smoke-*.jar")) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.endsWith("-sources.jar") || n.endsWith(".original")) continue;
                return p;
            }
        }
        return null;
    }

    private void scpTo(String userHost, int sshPort, Path local, String remotePath)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "scp",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
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

    private int streamCommand(Path workdir, Map<String, String> env, BufferedWriter logOut, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workdir.toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
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

    private int streamRemote(SetupContext ctx, String script, BufferedWriter logOut)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(ctx.target().sshPort()),
                ctx.target().sshUserHost(),
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

    private StepResult failRun(SetupContext ctx, Path logFile, String detail) throws IOException {
        statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
        return StepResult.failed(detail + " (log: " + logFile + ")");
    }

    private void header(BufferedWriter logOut, SetupContext ctx, Path appDir, String routeHost) throws IOException {
        logOut.write("Timestamp:    " + Instant.now() + "\n");
        logOut.write("Target:       " + ctx.target().uri() + "\n");
        logOut.write("Local appdir: " + appDir + "\n");
        logOut.write("Remote dir:   ~/" + REMOTE_WORK_DIR + "\n");
        logOut.write("Remote jar:   ~/" + REMOTE_JAR + "\n");
        logOut.write("Route:        " + routeHost + "\n");
        logOut.write("\n");
        logOut.flush();
    }
}
