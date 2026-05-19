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
    // Noble ships an AppArmor profile for nc.openbsd that denies dac_override/dac_read_search.
    // garden's post-start probe shells `nc -U /var/vcap/data/garden/garden.sock` to ping garden;
    // the profile is enforced inside the diego-cell container too, so the probe fails with
    // "Permission denied" even though garden itself is up. Disabling the host profile is the
    // smallest blast-radius fix (curl --unix-socket is unaffected because no profile applies).
    // See deploy-cf #16 follow-up. The on-disk filename varies across releases — on noble it
    // is /etc/apparmor.d/nc.openbsd; older Ubuntu used /etc/apparmor.d/usr.bin.nc.openbsd. We
    // discover it at runtime rather than hardcoding.
    static final String APPARMOR_DIR = "/etc/apparmor.d";
    static final String APPARMOR_DISABLE_DIR = APPARMOR_DIR + "/disable";

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
            ApparmorState apparmor = probeNcApparmor(ctx);
            logOut.write("[host-setup] nc.openbsd AppArmor profile: " + apparmor + "\n");

            String sysctlSummary;
            if (toRaise.isEmpty()) {
                logOut.write("[host-setup] all sysctls already meet minimums; nothing to do.\n");
                sysctlSummary = "inotify limits OK (" + format(before) + ")";
            } else {
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
                sysctlSummary = "inotify limits bumped (was: " + format(before) + " -> now: " + format(after) + ")";
            }

            String apparmorSummary;
            if (apparmor == ApparmorState.NOT_PRESENT) {
                apparmorSummary = "nc.openbsd profile not present (no action needed)";
            } else if (apparmor == ApparmorState.ALREADY_DISABLED) {
                apparmorSummary = "nc.openbsd already disabled";
            } else {
                logOut.write("[host-setup] disabling nc.openbsd AppArmor profile under " + APPARMOR_DIR + "\n");
                int exit = streamRemote(ctx, disableNcApparmorScript(), logOut);
                if (exit != 0) {
                    String detail = "disabling nc.openbsd AppArmor profile failed (exit " + exit + "). "
                            + "Run manually:" + manualApparmorRecipe();
                    statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                    return StepResult.failed(detail + " (log: " + logFile + ")");
                }
                ApparmorState after = probeNcApparmor(ctx);
                if (after != ApparmorState.ALREADY_DISABLED) {
                    String detail = "nc.openbsd disable script succeeded but profile still loaded (state=" + after + ")";
                    statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                    return StepResult.failed(detail + " (log: " + logFile + ")");
                }
                apparmorSummary = "nc.openbsd AppArmor profile disabled";
            }

            String summary = sysctlSummary + "; " + apparmorSummary;
            statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
            return StepResult.ran(summary + " (log: " + logFile + ")");
        }
    }

    enum ApparmorState {
        NOT_PRESENT,         // /etc/apparmor.d/usr.bin.nc.openbsd doesn't exist on host
        ALREADY_DISABLED,    // disabled/* symlink exists AND not in active profile list
        ENFORCED             // active in /sys/kernel/security/apparmor/profiles or aa-status
    }

    private ApparmorState probeNcApparmor(SetupContext ctx) throws IOException, InterruptedException {
        // Outputs:
        //   PROFILE_FILE=<path|>     — first existing profile file (or empty)
        //   LINK_OK=yes|no           — disable/* symlink for that basename present
        //   ACTIVE=yes|no            — profile currently loaded into the kernel
        // Both Ubuntu naming conventions are checked (noble uses bare nc.openbsd; older releases
        // used usr.bin.nc.openbsd). aa-status sometimes needs sudo; we tolerate either.
        String script = "set -uo pipefail\n"
                + "PROFILE=\"\"\n"
                + "for cand in " + APPARMOR_DIR + "/nc.openbsd " + APPARMOR_DIR + "/usr.bin.nc.openbsd; do\n"
                + "  if [ -f \"$cand\" ]; then PROFILE=\"$cand\"; break; fi\n"
                + "done\n"
                + "echo PROFILE_FILE=$PROFILE\n"
                + "if [ -n \"$PROFILE\" ] && [ -L " + APPARMOR_DISABLE_DIR + "/\"$(basename $PROFILE)\" ]; then\n"
                + "  echo LINK_OK=yes\n"
                + "else\n"
                + "  echo LINK_OK=no\n"
                + "fi\n"
                + "if (command -v aa-status >/dev/null 2>&1 && sudo -n aa-status 2>/dev/null | grep -q '^   nc.openbsd$') \\\n"
                + "    || (grep -q '^nc.openbsd ' /sys/kernel/security/apparmor/profiles 2>/dev/null); then\n"
                + "  echo ACTIVE=yes\n"
                + "else\n"
                + "  echo ACTIVE=no\n"
                + "fi\n";
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        String text = new String(out, StandardCharsets.UTF_8);
        boolean fileFound = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("PROFILE_FILE=") && line.length() > "PROFILE_FILE=".length()) {
                fileFound = true;
            }
        }
        boolean link = text.contains("LINK_OK=yes");
        boolean active = text.contains("ACTIVE=yes");
        if (!fileFound && !active) return ApparmorState.NOT_PRESENT;
        if (link && !active) return ApparmorState.ALREADY_DISABLED;
        return ApparmorState.ENFORCED;
    }

    private String disableNcApparmorScript() {
        return "set -euo pipefail\n"
                + "PROFILE=\"\"\n"
                + "for cand in " + APPARMOR_DIR + "/nc.openbsd " + APPARMOR_DIR + "/usr.bin.nc.openbsd; do\n"
                + "  if [ -f \"$cand\" ]; then PROFILE=\"$cand\"; break; fi\n"
                + "done\n"
                + "if [ -z \"$PROFILE\" ]; then\n"
                + "  echo '[apparmor] no nc.openbsd profile file found; nothing to disable'; exit 0\n"
                + "fi\n"
                + "BASENAME=\"$(basename \"$PROFILE\")\"\n"
                + "echo \"[apparmor] symlinking " + APPARMOR_DISABLE_DIR + "/$BASENAME -> $PROFILE\"\n"
                + "sudo -n install -d -m 0755 " + APPARMOR_DISABLE_DIR + "\n"
                + "# Clean up any stale broken symlinks from earlier runs that guessed the wrong basename.\n"
                + "for stale in " + APPARMOR_DISABLE_DIR + "/nc.openbsd " + APPARMOR_DISABLE_DIR + "/usr.bin.nc.openbsd; do\n"
                + "  if [ \"$stale\" != \"" + APPARMOR_DISABLE_DIR + "/$BASENAME\" ] && [ -L \"$stale\" ] && [ ! -e \"$stale\" ]; then\n"
                + "    echo \"[apparmor] removing stale broken symlink $stale\"\n"
                + "    sudo -n rm -f \"$stale\"\n"
                + "  fi\n"
                + "done\n"
                + "sudo -n ln -sf \"$PROFILE\" \"" + APPARMOR_DISABLE_DIR + "/$BASENAME\"\n"
                + "echo \"[apparmor] apparmor_parser -R $PROFILE\"\n"
                + "sudo -n apparmor_parser -R \"$PROFILE\"\n";
    }

    private String manualApparmorRecipe() {
        return "\n  PROFILE=$(ls " + APPARMOR_DIR + "/nc.openbsd " + APPARMOR_DIR + "/usr.bin.nc.openbsd 2>/dev/null | head -1)\n"
                + "  sudo install -d -m 0755 " + APPARMOR_DISABLE_DIR + "\n"
                + "  sudo ln -sf \"$PROFILE\" " + APPARMOR_DISABLE_DIR + "/$(basename \"$PROFILE\")\n"
                + "  sudo apparmor_parser -R \"$PROFILE\"\n";
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
