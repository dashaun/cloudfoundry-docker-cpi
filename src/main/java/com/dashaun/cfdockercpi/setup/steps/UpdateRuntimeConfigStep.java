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
    static final String DNS_CONFIG_NAME = "dns";
    static final String DNS_WAIT_CONFIG_NAME = "dns-wait";
    static final String REMOTE_DNS_YML = "bosh-deployment/runtime-configs/dns.yml";

    // Tiny in-repo BOSH release that gives diego-cell a pre-start hook waiting for
    // locket.service.cf.internal to resolve via libc before letting rep start.
    // Issue #16: on noble docker stemcells, systemd-resolved -> bosh-dns forwarding
    // races bosh-dns coming up on the local cell VM. rep panics in initializeCellPresence
    // (failed-to-construct-locket-client, context deadline exceeded) before the resolver
    // settles. The pre-start polls getent until success or PRE_START_TIMEOUT_SECONDS,
    // then fails the VM loudly if DNS is still broken so the symptom points at the
    // resolver, not at rep.
    static final String DNS_WAIT_RELEASE_NAME = "cf-docker-cpi-dns-wait";
    static final String DNS_WAIT_RELEASE_VERSION = "0.1.0";
    static final String DNS_WAIT_RELEASE_DIR = "dns-wait-release";
    static final String DNS_WAIT_RELEASE_TARBALL = "dns-wait-release.tgz";
    static final String DNS_WAIT_CONFIG_FILE = "dns-wait-runtime-config.yml";
    static final int PRE_START_TIMEOUT_SECONDS = 300;
    static final int PRE_START_POLL_INTERVAL_SECONDS = 5;

    static final String DNS_SHA_MARKER = "[dns-applied-sha]";
    static final String DNS_WAIT_SHA_MARKER = "[dns-wait-applied-sha]";

    private static final Pattern DETAIL_DNS_SHA = Pattern.compile("dns_sha=([0-9a-f]{8,64})");
    private static final Pattern DETAIL_DNS_WAIT_SHA = Pattern.compile("dns_wait_sha=([0-9a-f]{8,64})");

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
        return "Apply bosh-deployment's `dns` runtime-config (bosh-dns addon) plus a `dns-wait` "
                + "runtime-config (diego-cell pre-start that waits for locket DNS — see issue #16).";
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
        if (!hasShaMarker(recorded.get().detail(), DETAIL_DNS_SHA)
                || !hasShaMarker(recorded.get().detail(), DETAIL_DNS_WAIT_SHA)) {
            return StepCheck.NEEDS_RUN;
        }
        if (ctx.verify()) {
            if (!ctx.target().isSsh()) return StepCheck.NEEDS_RUN;
            try {
                RemoteShas remote = remoteAppliedShas(ctx);
                if (remote == null) return StepCheck.NEEDS_RUN;
                if (!shaMatches(recorded.get().detail(), DETAIL_DNS_SHA, remote.dns())) {
                    return StepCheck.NEEDS_RUN;
                }
                if (!shaMatches(recorded.get().detail(), DETAIL_DNS_WAIT_SHA, remote.dnsWait())) {
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
        CapturedRun result;
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, alias);
            result = streamRemote(ctx, bootstrap, logOut);
        }
        if (result.exit != 0) {
            String detail = "bosh update-runtime-config failed (ssh exit " + result.exit + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }
        if (result.dnsSha == null || result.dnsWaitSha == null) {
            String detail = "update-runtime-config succeeded but applied SHAs were not emitted"
                    + " (dns=" + (result.dnsSha != null) + " dns_wait=" + (result.dnsWaitSha != null) + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        String detail = "dns_sha=" + shortSha(result.dnsSha)
                + " dns_wait_sha=" + shortSha(result.dnsWaitSha);
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
            + "echo \"[runtime-config] applying " + REMOTE_DNS_YML + " as name=" + DNS_CONFIG_NAME + "\"\n"
            + "./bin/bosh -e " + alias + " update-runtime-config " + REMOTE_DNS_YML
                    + " --name " + DNS_CONFIG_NAME + " --no-color --non-interactive\n"
            + "\n"
            + dnsWaitSection(alias)
            + "\n"
            + "echo \"[runtime-config] verifying applied configs on director\"\n"
            + "DNS_APPLIED=\"$(./bin/bosh -e " + alias + " runtime-config --name " + DNS_CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}')\"\n"
            + "DNS_WAIT_APPLIED=\"$(./bin/bosh -e " + alias + " runtime-config --name " + DNS_WAIT_CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}')\"\n"
            + "echo \"" + DNS_SHA_MARKER + " ${DNS_APPLIED}\"\n"
            + "echo \"" + DNS_WAIT_SHA_MARKER + " ${DNS_WAIT_APPLIED}\"\n";
    }

    private String dnsWaitSection(String alias) {
        // 1. Materialise the BOSH release source from inline templates.
        // 2. Skip create+upload if director already has cf-docker-cpi-dns-wait@VERSION.
        // 3. Otherwise: bosh create-release --tarball, bosh upload-release.
        // 4. Write the addon runtime-config and apply it under --name dns-wait.
        return ""
            + "echo \"[dns-wait] materialising release source under " + DNS_WAIT_RELEASE_DIR + "/\"\n"
            + "rm -rf " + DNS_WAIT_RELEASE_DIR + "\n"
            + "mkdir -p " + DNS_WAIT_RELEASE_DIR + "/config\n"
            + "mkdir -p " + DNS_WAIT_RELEASE_DIR + "/jobs/wait-for-locket-dns/templates\n"
            + "\n"
            + "cat > " + DNS_WAIT_RELEASE_DIR + "/config/final.yml <<'CFEOF'\n"
            + "---\n"
            + "name: " + DNS_WAIT_RELEASE_NAME + "\n"
            + "CFEOF\n"
            + "\n"
            + "# bosh create-release reads blobs.yml unconditionally even for zero-blob releases.\n"
            + "cat > " + DNS_WAIT_RELEASE_DIR + "/config/blobs.yml <<'BLOBEOF'\n"
            + "--- {}\n"
            + "BLOBEOF\n"
            + "\n"
            + "cat > " + DNS_WAIT_RELEASE_DIR + "/jobs/wait-for-locket-dns/spec <<'SPECEOF'\n"
            + jobSpec()
            + "SPECEOF\n"
            + "\n"
            + "cat > " + DNS_WAIT_RELEASE_DIR + "/jobs/wait-for-locket-dns/monit <<'MONEOF'\n"
            + "# wait-for-locket-dns is a pre-start-only job; no long-running process to monitor.\n"
            + "MONEOF\n"
            + "\n"
            + "cat > " + DNS_WAIT_RELEASE_DIR + "/jobs/wait-for-locket-dns/templates/pre-start.erb <<'PSEOF'\n"
            + preStartTemplate()
            + "PSEOF\n"
            + "\n"
            + "if ./bin/bosh -e " + alias + " releases --no-color 2>/dev/null \\\n"
            + "    | awk -v n='" + DNS_WAIT_RELEASE_NAME + "' -v v='" + DNS_WAIT_RELEASE_VERSION + "' \\\n"
            + "        '$1==n && $2==v {found=1} END {exit !found}'; then\n"
            + "  echo \"[dns-wait] " + DNS_WAIT_RELEASE_NAME + "/" + DNS_WAIT_RELEASE_VERSION
                    + " already on director — skipping create+upload\"\n"
            + "else\n"
            + "  echo \"[dns-wait] bosh create-release " + DNS_WAIT_RELEASE_NAME + "/"
                    + DNS_WAIT_RELEASE_VERSION + "\"\n"
            + "  ( cd " + DNS_WAIT_RELEASE_DIR + " && ../bin/bosh create-release --force \\\n"
            + "      --name " + DNS_WAIT_RELEASE_NAME + " --version " + DNS_WAIT_RELEASE_VERSION + " \\\n"
            + "      --tarball ../" + DNS_WAIT_RELEASE_TARBALL + " )\n"
            + "  echo \"[dns-wait] bosh upload-release\"\n"
            + "  ./bin/bosh -e " + alias + " upload-release " + DNS_WAIT_RELEASE_TARBALL
                    + " --no-color --non-interactive\n"
            + "fi\n"
            + "\n"
            + "cat > " + DNS_WAIT_CONFIG_FILE + " <<'RCEOF'\n"
            + dnsWaitRuntimeConfig()
            + "RCEOF\n"
            + "\n"
            + "echo \"[runtime-config] applying " + DNS_WAIT_CONFIG_FILE + " as name="
                    + DNS_WAIT_CONFIG_NAME + "\"\n"
            + "./bin/bosh -e " + alias + " update-runtime-config " + DNS_WAIT_CONFIG_FILE
                    + " --name " + DNS_WAIT_CONFIG_NAME + " --no-color --non-interactive\n";
    }

    private static String jobSpec() {
        return ""
            + "---\n"
            + "name: wait-for-locket-dns\n"
            + "\n"
            + "templates:\n"
            + "  pre-start.erb: bin/pre-start\n"
            + "\n"
            + "properties:\n"
            + "  wait_for_locket_dns.host:\n"
            + "    description: Hostname to resolve via libc before letting the VM leave pre-start\n"
            + "    default: locket.service.cf.internal\n"
            + "  wait_for_locket_dns.timeout_seconds:\n"
            + "    description: Maximum seconds to poll before failing the pre-start (and the VM)\n"
            + "    default: " + PRE_START_TIMEOUT_SECONDS + "\n"
            + "  wait_for_locket_dns.poll_interval_seconds:\n"
            + "    description: Seconds between successive getent attempts\n"
            + "    default: " + PRE_START_POLL_INTERVAL_SECONDS + "\n";
    }

    private static String preStartTemplate() {
        return ""
            + "#!/usr/bin/env bash\n"
            + "# Issue #16: on noble docker stemcells, systemd-resolved -> bosh-dns forwarding can\n"
            + "# race bosh-dns coming up on the local VM. rep panics in initializeCellPresence\n"
            + "# (context-deadline-exceeded on locket client) before the resolver settles. Hold the\n"
            + "# VM in pre-start until libc can resolve <host>, then fail loudly if it never does so\n"
            + "# the symptom points at DNS instead of at a confusing rep crash.\n"
            + "set -euo pipefail\n"
            + "\n"
            + "HOST=\"<%= p('wait_for_locket_dns.host') %>\"\n"
            + "TIMEOUT=<%= p('wait_for_locket_dns.timeout_seconds') %>\n"
            + "INTERVAL=<%= p('wait_for_locket_dns.poll_interval_seconds') %>\n"
            + "\n"
            + "LOG_DIR=/var/vcap/sys/log/wait-for-locket-dns\n"
            + "mkdir -p \"$LOG_DIR\"\n"
            + "exec >>\"$LOG_DIR/pre-start.log\" 2>&1\n"
            + "\n"
            + "echo \"[$(date -Iseconds)] waiting for ${HOST} (timeout=${TIMEOUT}s, interval=${INTERVAL}s)\"\n"
            + "\n"
            + "deadline=$(( $(date +%s) + TIMEOUT ))\n"
            + "attempt=0\n"
            + "while :; do\n"
            + "  attempt=$((attempt + 1))\n"
            + "  if getent hosts \"$HOST\" >/dev/null 2>&1; then\n"
            + "    echo \"[$(date -Iseconds)] resolved ${HOST} on attempt ${attempt}\"\n"
            + "    exit 0\n"
            + "  fi\n"
            + "  if [ \"$(date +%s)\" -ge \"$deadline\" ]; then\n"
            + "    echo \"[$(date -Iseconds)] FAILED to resolve ${HOST} within ${TIMEOUT}s (${attempt} attempts)\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  sleep \"$INTERVAL\"\n"
            + "done\n";
    }

    private static String dnsWaitRuntimeConfig() {
        return ""
            + "---\n"
            + "addons:\n"
            + "- name: dns-wait\n"
            + "  include:\n"
            + "    instance_groups:\n"
            + "    - diego-cell\n"
            + "  jobs:\n"
            + "  - name: wait-for-locket-dns\n"
            + "    release: " + DNS_WAIT_RELEASE_NAME + "\n"
            + "\n"
            + "releases:\n"
            + "- name: " + DNS_WAIT_RELEASE_NAME + "\n"
            + "  version: " + DNS_WAIT_RELEASE_VERSION + "\n";
    }

    private CapturedRun streamRemote(SetupContext ctx, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        }
        String dnsSha = null;
        String dnsWaitSha = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                logOut.write(line);
                logOut.newLine();
                logOut.flush();
                String captured = extractSha(line, DNS_SHA_MARKER);
                if (captured != null) dnsSha = captured;
                captured = extractSha(line, DNS_WAIT_SHA_MARKER);
                if (captured != null) dnsWaitSha = captured;
            }
        }
        return new CapturedRun(p.waitFor(), dnsSha, dnsWaitSha);
    }

    private RemoteShas remoteAppliedShas(SetupContext ctx) throws IOException, InterruptedException {
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        String script = "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "DNS=\"$(./bin/bosh -e " + alias + " runtime-config --name " + DNS_CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}')\"\n"
            + "DNS_WAIT=\"$(./bin/bosh -e " + alias + " runtime-config --name " + DNS_WAIT_CONFIG_NAME
                    + " --no-color | sha256sum | awk '{print $1}')\"\n"
            + "echo DNS=$DNS\n"
            + "echo DNS_WAIT=$DNS_WAIT\n";
        Process p = startSshBash(ctx);
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String dns = null;
        String dnsWait = null;
        Pattern dnsLine = Pattern.compile("^DNS=([0-9a-f]{64})$");
        Pattern dnsWaitLine = Pattern.compile("^DNS_WAIT=([0-9a-f]{64})$");
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher m = dnsLine.matcher(line.trim());
                if (m.matches()) { dns = m.group(1); continue; }
                m = dnsWaitLine.matcher(line.trim());
                if (m.matches()) { dnsWait = m.group(1); }
            }
        }
        if (p.waitFor() != 0) return null;
        if (dns == null || dnsWait == null) return null;
        return new RemoteShas(dns, dnsWait);
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

    private static String extractSha(String line, String marker) {
        int idx = line.indexOf(marker);
        if (idx < 0) return null;
        String rest = line.substring(idx + marker.length()).trim();
        return rest.matches("[0-9a-f]{64}") ? rest : null;
    }

    private static boolean hasShaMarker(String detail, Pattern pattern) {
        return detail != null && pattern.matcher(detail).find();
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
        logOut.write("Configs:     " + DNS_CONFIG_NAME + ", " + DNS_WAIT_CONFIG_NAME + "\n");
        logOut.write("dns source:  ~/" + REMOTE_WORK_DIR + "/" + REMOTE_DNS_YML + "\n");
        logOut.write("dns-wait:    " + DNS_WAIT_RELEASE_NAME + "/" + DNS_WAIT_RELEASE_VERSION
                + " (diego-cell pre-start, timeout " + PRE_START_TIMEOUT_SECONDS + "s)\n");
        logOut.write("\n");
        logOut.flush();
    }

    private record CapturedRun(int exit, String dnsSha, String dnsWaitSha) {}

    private record RemoteShas(String dns, String dnsWait) {}
}
