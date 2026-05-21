package com.dashaun.cfdockercpi.marketplace;

import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enables / disables one service offering in the marketplace. Each "service" here is a CF
 * plan exposed by the cf-docker-cpi broker — see the broker's catalog in
 * {@code BrokerCatalogConfiguration}. The actual provisioning of containers is done by the
 * broker when {@code cf create-service} fires; this class just toggles
 * {@code cf enable-service-access} / {@code disable-service-access} for the plan and tracks
 * the choice in {@code status.json}.
 */
@Component
public class ServiceManager {

    /** The offering names accepted by `setup service add --name <name>`. */
    static final Set<String> KNOWN_SERVICES = Set.of(
            "postgres", "redis", "rabbitmq", "minio");

    private final StatusStore statusStore;

    public ServiceManager(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    public StepResult add(SetupContext ctx, String name) throws IOException, InterruptedException {
        String offering = canonicalOffering(name);
        if (offering == null) return unknown(name);
        if (!ctx.target().isSsh()) {
            return StepResult.failed("setup service add v1 supports ssh:// targets only");
        }
        if (statusStore.getService(ctx.statusFile(), StatusStore.BROKER_KEY)
                .map(s -> s.status() != StepStatus.Status.PASS)
                .orElse(true)) {
            String detail = "broker not deployed — run `broker deploy --host " + ctx.target().uri() + "` first";
            statusStore.putService(ctx.statusFile(), offering, StepStatus.fail(detail));
            return StepResult.failed(detail);
        }

        String script = "set -euo pipefail\n"
                + "cd ~/.cf-docker-cpi-work\n"
                + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
                + "./bin/cf enable-service-access " + offering + " -p single -o system\n"
                + "./bin/cf marketplace | grep " + offering + " || echo '(not yet visible — broker may still be starting)'\n";
        Result r = runRemote(ctx, script);
        if (r.exit != 0) {
            String detail = "cf enable-service-access " + offering + " failed (exit " + r.exit + ")";
            statusStore.putService(ctx.statusFile(), offering, StepStatus.fail(detail));
            return StepResult.failed(detail + ":\n" + r.output);
        }
        String summary = offering + " enabled in marketplace (org=system)";
        statusStore.putService(ctx.statusFile(), offering, StepStatus.pass(summary));
        return StepResult.ran(summary);
    }

    public StepResult remove(SetupContext ctx, String name) throws IOException, InterruptedException {
        String offering = canonicalOffering(name);
        if (offering == null) return unknown(name);
        if (!ctx.target().isSsh()) {
            return StepResult.failed("setup service remove v1 supports ssh:// targets only");
        }
        String script = "set -uo pipefail\n"
                + "cd ~/.cf-docker-cpi-work\n"
                + "export CF_HOME=\"$(pwd)/cf-home\" CF_COLOR=false\n"
                + "./bin/cf disable-service-access " + offering + " -p single -o system || true\n";
        Result r = runRemote(ctx, script);
        statusStore.putService(ctx.statusFile(), offering,
                new StepStatus(StepStatus.Status.NEW, java.time.Instant.now().toString(),
                        "disabled — re-enable with `service add " + name + "`"));
        return StepResult.ran("disabled " + offering + " (broker still deployed)");
    }

    public Map<String, StepStatus> list(SetupContext ctx) throws IOException {
        return statusStore.load(ctx.statusFile()).services();
    }

    public List<String> knownServices() {
        return List.of("postgres", "redis", "rabbitmq", "minio");
    }

    private static String canonicalOffering(String shortName) {
        if (shortName == null) return null;
        return switch (shortName.toLowerCase()) {
            case "postgres", "postgres-single", "pg" -> "postgres-single";
            case "redis", "redis-single" -> "redis-single";
            case "rabbitmq", "rabbitmq-single", "rabbit" -> "rabbitmq-single";
            case "minio", "minio-single", "s3" -> "minio-single";
            default -> null;
        };
    }

    private static StepResult unknown(String name) {
        return StepResult.failed("unknown service '" + name + "'. Known: " + KNOWN_SERVICES
                + " (or their *-single forms)");
    }

    private Result runRemote(SetupContext ctx, String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(ctx.target().sshPort()),
                ctx.target().sshUserHost(),
                "bash -s");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                out.append(line).append('\n');
            }
        }
        return new Result(p.waitFor(), out.toString());
    }

    private record Result(int exit, String output) {}
}
