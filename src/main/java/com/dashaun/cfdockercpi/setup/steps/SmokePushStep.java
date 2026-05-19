package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.docker.SshLocalForward;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SmokePushStep implements SetupStep {

    static final String NAME = "smoke-push";
    static final String APP_NAME = "cf-smoke";
    static final String APP_DIR = "cf-smoke";
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
        return "Fetch a Spring Boot starter, package it, `cf push` it, verify HTTP 200 on /actuator/health.";
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
            return StepCheck.NEEDS_RUN;
        }
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("smoke-push v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Path cfBin = ctx.binDir().resolve("cf");
        if (!Files.isRegularFile(cfBin) || !Files.isExecutable(cfBin)) {
            return failPrecheck(ctx, logFile, "cf binary missing at " + cfBin + " — run install-tools");
        }
        if (!Files.isDirectory(ctx.cfHome())) {
            return failPrecheck(ctx, logFile, "CF_HOME missing at " + ctx.cfHome()
                    + " — run configure-cf-cli");
        }

        Path appDir = ctx.stateDir().resolve(APP_DIR);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, cfBin, appDir);

            // 1. Fetch starter.zip if pom.xml not already present.
            if (!Files.isRegularFile(appDir.resolve("pom.xml"))) {
                logOut.write("[fetch] " + STARTER_URL + "\n");
                logOut.flush();
                fetchStarter(appDir);
            } else {
                logOut.write("[fetch] reusing existing " + appDir + "/pom.xml\n");
                logOut.flush();
            }

            // 2. Write manifest.yml (every run — JBP config + memory may drift).
            Path manifest = appDir.resolve("manifest.yml");
            Files.writeString(manifest, manifestYaml());
            logOut.write("[manifest] wrote " + manifest + "\n\n");
            logOut.flush();

            // 3. Build the jar.
            logOut.write("[build] ./mvnw package -DskipTests\n");
            logOut.flush();
            int mvnExit = streamCommand(appDir, Map.of(), logOut,
                    appDir.resolve("mvnw").toString(), "package", "-DskipTests");
            if (mvnExit != 0) {
                throw new StepFailure("./mvnw package failed (exit " + mvnExit + ")");
            }
            Path jar = findJar(appDir.resolve("target"));
            if (jar == null) {
                throw new StepFailure("build succeeded but no cf-smoke-*.jar found under "
                        + appDir.resolve("target"));
            }
            logOut.write("[build] " + jar.getFileName() + "\n\n");
            logOut.flush();

            // 4. Open SSH tunnel for cf push + health probe.
            String routeHost = APP_NAME + "." + ctx.systemDomain();
            try (SshLocalForward fwd = SshLocalForward.open(ctx.target(),
                    ConfigureCfCliStep.HAPROXY_VM_IP,
                    ConfigureCfCliStep.HAPROXY_PORT,
                    ConfigureCfCliStep.PREFERRED_LOCAL_PORT,
                    Duration.ofSeconds(10))) {
                logOut.write("Tunnel:    " + fwd.description() + "\n\n");
                logOut.flush();

                int pushExit = streamCommand(appDir,
                        Map.of("CF_HOME", ctx.cfHome().toString(), "CF_COLOR", "false"),
                        logOut,
                        cfBin.toString(), "push", APP_NAME,
                        "-f", manifest.toString(),
                        "-p", jar.toString());
                if (pushExit != 0) {
                    throw new StepFailure("cf push failed (exit " + pushExit + ")");
                }

                String healthUrl = "https://" + routeHost + ":" + fwd.localPort() + HEALTH_PATH;
                logOut.write("[probe] GET " + healthUrl + " (up to " + HEALTH_TIMEOUT_SECONDS + "s)\n");
                logOut.flush();
                int status = pollFor200(healthUrl, Duration.ofSeconds(HEALTH_TIMEOUT_SECONDS), logOut);
                if (status != 200) {
                    throw new StepFailure("GET " + healthUrl + " never returned 200 (last=" + status + ")");
                }
                logOut.write("[probe] 200 OK\n");
                logOut.flush();

                String summary = APP_NAME + " up @ https://" + routeHost
                        + ":" + fwd.localPort() + HEALTH_PATH;
                statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
                return StepResult.ran(summary + " (log: " + logFile + ")");
            }
        } catch (StepFailure e) {
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(e.detail));
            return StepResult.failed(e.detail + " (log: " + logFile + ")");
        }
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
                // start.spring.io zips entries under a top-level "cf-smoke/" folder; strip it.
                String name = stripTopLevel(e.getName());
                if (name.isEmpty()) continue;
                Path dest = appDir.resolve(name).normalize();
                if (!dest.startsWith(appDir)) {
                    throw new IOException("zip entry escaped target dir: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zin, dest);
                    if (name.equals("mvnw") || name.startsWith("mvnw")) {
                        dest.toFile().setExecutable(true, false);
                    }
                }
            }
        }
    }

    private static String stripTopLevel(String entryName) {
        int slash = entryName.indexOf('/');
        if (slash < 0) return "";
        return entryName.substring(slash + 1);
    }

    private String manifestYaml() {
        return ""
            + "---\n"
            + "applications:\n"
            + "- name: " + APP_NAME + "\n"
            + "  memory: 1G\n"
            + "  instances: 1\n"
            + "  env:\n"
            + "    JBP_CONFIG_OPEN_JDK_JRE: '" + JBP_OPEN_JDK + "'\n";
    }

    private Path findJar(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) return null;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(targetDir, "cf-smoke-*.jar")) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                // Skip the *-sources or *-original variants if present.
                if (n.endsWith("-sources.jar") || n.endsWith(".original")) continue;
                return p;
            }
        }
        return null;
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

    // Polls the route until we see 200 (app is up) or the timeout elapses. We expect a brief
    // window after `cf push` returns where the route is registered but the app is still
    // starting up. 503 / connection refused / SSLException are all treated as "still booting".
    private int pollFor200(String url, Duration timeout, BufferedWriter logOut)
            throws IOException, InterruptedException {
        HttpClient client = trustAllClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        Instant deadline = Instant.now().plus(timeout);
        int lastStatus = -1;
        int attempt = 0;
        while (Instant.now().isBefore(deadline)) {
            attempt++;
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                lastStatus = resp.statusCode();
                if (lastStatus == 200) {
                    logOut.write("[probe] attempt " + attempt + ": 200 OK (body: "
                            + truncate(resp.body(), 120) + ")\n");
                    logOut.flush();
                    return 200;
                }
                logOut.write("[probe] attempt " + attempt + ": HTTP " + lastStatus + "\n");
                logOut.flush();
            } catch (IOException | InterruptedException e) {
                logOut.write("[probe] attempt " + attempt + ": " + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + "\n");
                logOut.flush();
                if (e instanceof InterruptedException) throw e;
            }
            Thread.sleep(3000);
        }
        return lastStatus;
    }

    private static HttpClient trustAllClient() {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            // Some JDKs hostname-verify even with a trust-all TM; clear endpoint identification.
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm(null);
            return HttpClient.newBuilder()
                    .sslContext(ssl)
                    .sslParameters(params)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private StepResult failPrecheck(SetupContext ctx, Path logFile, String detail) throws IOException {
        Files.writeString(logFile, "Timestamp: " + Instant.now() + "\n" + detail + "\n");
        statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
        return StepResult.failed(detail + " (log: " + logFile + ")");
    }

    private void header(BufferedWriter logOut, SetupContext ctx, Path cfBin, Path appDir) throws IOException {
        logOut.write("Timestamp:     " + Instant.now() + "\n");
        logOut.write("Target:        " + ctx.target().uri() + "\n");
        logOut.write("cf binary:     " + cfBin + "\n");
        logOut.write("CF_HOME:       " + ctx.cfHome() + "\n");
        logOut.write("App dir:       " + appDir + "\n");
        logOut.write("Route:         " + APP_NAME + "." + ctx.systemDomain() + "\n");
        logOut.write("\n");
        logOut.flush();
    }

    private static final class StepFailure extends Exception {
        final String detail;
        StepFailure(String detail) { super(detail); this.detail = detail; }
    }
}
