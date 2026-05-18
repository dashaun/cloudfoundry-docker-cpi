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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class HostSetupStep implements SetupStep {

    static final String NAME = "host-setup";
    static final String SYSCTL_DROPIN = "/etc/sysctl.d/99-cf-docker-cpi.conf";
    // 128 (Linux default) is exhausted by ~16 systemd-in-docker stemcell containers; 8192
    // matches the k8s/containerd reference value. See docs/setup-pipeline.md §2 (and issue #14).
    static final long MIN_INOTIFY_INSTANCES = 8192;
    // The per-uid watch pool is shared the same way; the default of 8192 is also too low for
    // a CF-on-docker host. 65536 is a generous floor that most modern distros already meet.
    static final long MIN_INOTIFY_WATCHES = 65536;

    private final StatusStore statusStore;

    public HostSetupStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Ensure host kernel limits suitable for many systemd-in-docker stemcell containers (fs.inotify.*).";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        try {
            Optional<StepStatus> recorded = statusStore.get(ctx.statusFile(), NAME);
            if (recorded.isEmpty() || recorded.get().status() != StepStatus.Status.PASS) {
                return StepCheck.NEEDS_RUN;
            }
            return StepCheck.ALREADY_DONE;
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("host-setup v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        Map<String, Long> required = expected();
        Map<String, Long> before;
        try {
            before = readSysctls(ctx);
        } catch (IOException e) {
            String detail = "remote sysctl probe failed: " + e.getMessage();
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, before, required);

            Map<String, Long> toRaise = belowFloor(before, required);
            if (toRaise.isEmpty()) {
                String summary = "inotify limits OK (" + format(before) + ")";
                logOut.write("[host-setup] all sysctls already meet minimums; nothing to do.\n");
                statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
                return StepResult.ran(summary + " (log: " + logFile + ")");
            }
            logOut.write("[host-setup] raising: " + format(toRaise) + "\n");

            String script = applyScript(toRaise);
            int exit = streamRemote(ctx, script, logOut);
            if (exit != 0) {
                String detail = "sudo apply failed (exit " + exit + "). "
                        + "The user must have passwordless sudo on the docker host, or run manually:"
                        + manualRecipe(required);
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }

            Map<String, Long> after = readSysctls(ctx);
            logOut.write("\n[host-setup] post-apply: " + format(after) + "\n");
            if (!allMet(after, required)) {
                String detail = "sysctl applied (exit 0) but values still below minimums: " + format(after);
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
            String summary = "inotify limits bumped (was: " + format(before) + " -> now: " + format(after) + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
            return StepResult.ran(summary + " (log: " + logFile + ")");
        }
    }

    private Map<String, Long> readSysctls(SetupContext ctx) throws IOException, InterruptedException {
        String script = "set -euo pipefail\n"
                + "echo fs.inotify.max_user_instances=$(cat /proc/sys/fs/inotify/max_user_instances)\n"
                + "echo fs.inotify.max_user_watches=$(cat /proc/sys/fs/inotify/max_user_watches)\n";
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        String text = new String(out, StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new IOException("remote sysctl probe exited " + exit + ": " + text.trim());
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                result.put(line.substring(0, eq).trim(),
                        Long.parseLong(line.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private String applyScript(Map<String, Long> required) {
        StringBuilder sb = new StringBuilder();
        sb.append("set -euo pipefail\n");
        sb.append("echo \"[host-setup] writing ").append(SYSCTL_DROPIN).append("\"\n");
        sb.append("cat <<'CONF' | sudo -n tee ").append(SYSCTL_DROPIN).append(" >/dev/null\n");
        sb.append("# Managed by cf-docker-cpi host-setup step.\n");
        for (Map.Entry<String, Long> e : required.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
        }
        sb.append("CONF\n");
        sb.append("echo \"[host-setup] sysctl --load=").append(SYSCTL_DROPIN).append("\"\n");
        sb.append("sudo -n sysctl --quiet --load=").append(SYSCTL_DROPIN).append("\n");
        return sb.toString();
    }

    private String manualRecipe(Map<String, Long> required) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  sudo tee ").append(SYSCTL_DROPIN).append(" >/dev/null <<'CONF'\n");
        for (Map.Entry<String, Long> e : required.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
        }
        sb.append("  CONF\n");
        sb.append("  sudo sysctl --quiet --load=").append(SYSCTL_DROPIN).append("\n");
        return sb.toString();
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
                "-p", String.valueOf(ctx.target().sshPort()),
                ctx.target().sshUserHost(),
                "bash -s");
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private Map<String, Long> expected() {
        Map<String, Long> r = new LinkedHashMap<>();
        r.put("fs.inotify.max_user_instances", MIN_INOTIFY_INSTANCES);
        r.put("fs.inotify.max_user_watches", MIN_INOTIFY_WATCHES);
        return r;
    }

    private boolean allMet(Map<String, Long> actual, Map<String, Long> required) {
        return belowFloor(actual, required).isEmpty();
    }

    // Returns just the keys whose current value is below the required floor, mapped to the floor.
    // Keys already meeting the floor are intentionally excluded so the dropin doesn't downgrade
    // a value some other config (cloud-init, ansible) has already raised above our minimum.
    private Map<String, Long> belowFloor(Map<String, Long> actual, Map<String, Long> required) {
        Map<String, Long> r = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : required.entrySet()) {
            Long got = actual.get(e.getKey());
            if (got == null || got < e.getValue()) {
                r.put(e.getKey(), e.getValue());
            }
        }
        return r;
    }

    private String format(Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private void header(BufferedWriter logOut, SetupContext ctx, Map<String, Long> before,
                        Map<String, Long> required) throws IOException {
        logOut.write("Timestamp:  " + Instant.now() + "\n");
        logOut.write("Target:     " + ctx.target().uri() + "\n");
        logOut.write("Required:   " + format(required) + "\n");
        logOut.write("Current:    " + format(before) + "\n");
        logOut.write("\n");
        logOut.flush();
    }
}
