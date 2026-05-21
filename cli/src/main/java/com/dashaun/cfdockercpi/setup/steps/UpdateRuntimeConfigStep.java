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
    static final String DNS_RECURSORS_OPS_FILE = "dns-recursors-overrides.yml";
    // bosh-deployment's dns.yml ships `disable_recursors: true` on the ubuntu-noble addon
    // ("bosh-dns-systemd"), with empty `recursors`. On a stock cf-deployment that's fine —
    // the host's recursive resolver picks up external names. On our docker-CPI deploy the
    // cell VM's /etc/resolv.conf has been rewritten by dns-wait v0.2.0 to point at bosh-dns
    // itself (169.254.0.2), so bosh-dns has nowhere to forward unknown names → SERVFAIL on
    // anything off the cf-internal mesh, including the buildpacks.cloudfoundry.org domain
    // the java_buildpack staging fetches the JRE from. Override both: enable recursion and
    // hard-code a pair of public resolvers.
    static final String[] DNS_RECURSORS = {"8.8.8.8", "1.1.1.1"};

    // Tiny in-repo BOSH release that gives diego-cell a pre-start hook waiting for
    // locket.service.cf.internal to resolve via libc before letting rep start.
    // Issue #16: on noble docker stemcells, systemd-resolved -> bosh-dns forwarding
    // races bosh-dns coming up on the local cell VM. rep panics in initializeCellPresence
    // (failed-to-construct-locket-client, context deadline exceeded) before the resolver
    // settles. The pre-start polls getent until success or PRE_START_TIMEOUT_SECONDS,
    // then fails the VM loudly if DNS is still broken so the symptom points at the
    // resolver, not at rep.
    static final String DNS_WAIT_RELEASE_NAME = "cf-docker-cpi-dns-wait";
    // v0.2.0: pre-start now ALSO takes over /etc/resolv.conf with a plain file pointing
    // directly at the bosh-dns listener, bypassing systemd-resolved's flaky stub forwarder.
    // Go binaries (rep, route_emitter, etc.) read /etc/resolv.conf directly via Go's pure
    // resolver — they don't go through nsswitch/libc/systemd-resolved — so the symlink to
    // /run/systemd/resolve/stub-resolv.conf (nameserver 127.0.0.53) is what trips them up.
    static final String DNS_WAIT_RELEASE_VERSION = "0.2.0";
    static final String DNS_WAIT_RELEASE_DIR = "dns-wait-release";
    static final String DNS_WAIT_RELEASE_TARBALL = "dns-wait-release.tgz";
    static final String DNS_WAIT_CONFIG_FILE = "dns-wait-runtime-config.yml";
    static final int PRE_START_TIMEOUT_SECONDS = 300;
    static final int PRE_START_POLL_INTERVAL_SECONDS = 5;
    // bosh-dns's default IPv4 listener inside cf-deployment VMs. Confirmed on senshin by
    // `dig @169.254.0.2 locket.service.cf.internal +short` returning the expected IP.
    static final String BOSH_DNS_LISTENER = "169.254.0.2";

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
            + "cat > " + DNS_RECURSORS_OPS_FILE + " <<'OPS'\n"
            + dnsRecursorsOpsFile()
            + "OPS\n"
            + "\n"
            + "echo \"[runtime-config] applying " + REMOTE_DNS_YML + " (+recursors ops) as name=" + DNS_CONFIG_NAME + "\"\n"
            + "./bin/bosh -e " + alias + " update-runtime-config " + REMOTE_DNS_YML
                    + " -o " + DNS_RECURSORS_OPS_FILE
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

    private static String dnsRecursorsOpsFile() {
        // The noble addon in upstream dns.yml is named `bosh-dns-systemd` (it co-locates a
        // `configure_systemd_resolved: true` so libc on noble goes through systemd-resolved
        // → bosh-dns). On a docker-CPI deploy that pairing is what dns-wait v0.2.0 has to
        // unwind, and once /etc/resolv.conf no longer points at systemd-resolved's stub,
        // bosh-dns has nothing upstream to forward unknown names to. Override both knobs
        // on that addon. `?` after disable_recursors creates the key if upstream renames
        // or drops it; the addon-name path itself uses `=` so it must match exactly.
        StringBuilder recursors = new StringBuilder();
        for (String r : DNS_RECURSORS) {
            recursors.append("    - ").append(r).append('\n');
        }
        return ""
            + "- type: replace\n"
            + "  path: /addons/name=bosh-dns-systemd/jobs/name=bosh-dns/properties/disable_recursors?\n"
            + "  value: false\n"
            + "- type: replace\n"
            + "  path: /addons/name=bosh-dns-systemd/jobs/name=bosh-dns/properties/recursors?\n"
            + "  value:\n"
            + recursors.toString();
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
            + "    default: " + PRE_START_POLL_INTERVAL_SECONDS + "\n"
            + "  wait_for_locket_dns.bosh_dns_listener:\n"
            + "    description: |\n"
            + "      Address of the bosh-dns listener on the local VM. After pre-start confirms\n"
            + "      bosh-dns is reachable via libc, /etc/resolv.conf is rewritten to point\n"
            + "      directly here, bypassing systemd-resolved's stub forwarder (which is flaky\n"
            + "      for Go binaries on noble — they read /etc/resolv.conf directly via Go's\n"
            + "      pure-resolver mode, not via libc/nsswitch).\n"
            + "    default: " + BOSH_DNS_LISTENER + "\n";
    }

    private static String preStartTemplate() {
        return ""
            + "#!/usr/bin/env bash\n"
            + "# Issue #16: on noble docker stemcells, systemd-resolved -> bosh-dns forwarding can\n"
            + "# race bosh-dns coming up on the local VM, AND systemd-resolved's stub forwarder is\n"
            + "# itself flaky from Go binaries that read /etc/resolv.conf directly (rep,\n"
            + "# route_emitter — they bypass nsswitch).\n"
            + "#\n"
            + "# Two-step fix:\n"
            + "#   1. Wait for `getent hosts <locket>` to work — proves bosh-dns is up listening\n"
            + "#      on $LISTENER (otherwise systemd-resolved couldn't forward to it).\n"
            + "#   2. Atomically replace /etc/resolv.conf (currently a symlink to systemd-\n"
            + "#      resolved's stub-resolv.conf) with a plain file pointing directly at\n"
            + "#      $LISTENER. systemd-resolved only manages /etc/resolv.conf when it's a\n"
            + "#      symlink to one of its files; a plain file is left alone.\n"
            + "set -euo pipefail\n"
            + "\n"
            + "HOST=\"<%= p('wait_for_locket_dns.host') %>\"\n"
            + "TIMEOUT=<%= p('wait_for_locket_dns.timeout_seconds') %>\n"
            + "INTERVAL=<%= p('wait_for_locket_dns.poll_interval_seconds') %>\n"
            + "LISTENER=\"<%= p('wait_for_locket_dns.bosh_dns_listener') %>\"\n"
            + "\n"
            + "LOG_DIR=/var/vcap/sys/log/wait-for-locket-dns\n"
            + "mkdir -p \"$LOG_DIR\"\n"
            + "exec >>\"$LOG_DIR/pre-start.log\" 2>&1\n"
            + "\n"
            + "echo \"[$(date -Iseconds)] waiting for ${HOST} via libc (timeout=${TIMEOUT}s, interval=${INTERVAL}s)\"\n"
            + "\n"
            + "deadline=$(( $(date +%s) + TIMEOUT ))\n"
            + "attempt=0\n"
            + "while :; do\n"
            + "  attempt=$((attempt + 1))\n"
            + "  if getent hosts \"$HOST\" >/dev/null 2>&1; then\n"
            + "    echo \"[$(date -Iseconds)] libc resolved ${HOST} on attempt ${attempt}\"\n"
            + "    break\n"
            + "  fi\n"
            + "  if [ \"$(date +%s)\" -ge \"$deadline\" ]; then\n"
            + "    echo \"[$(date -Iseconds)] FAILED to resolve ${HOST} via libc within ${TIMEOUT}s (${attempt} attempts)\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  sleep \"$INTERVAL\"\n"
            + "done\n"
            + "\n"
            + "# bosh-dns is up. Sanity-check direct reachability before we cut systemd-resolved out\n"
            + "# of the path. If this fails the wait somehow lied (e.g. bosh-dns forwarder rather\n"
            + "# than bosh-dns itself answered) — surface that loudly instead of leaving the VM\n"
            + "# with a broken /etc/resolv.conf.\n"
            + "if command -v dig >/dev/null 2>&1; then\n"
            + "  if ! dig +time=2 +tries=1 +short \"@${LISTENER}\" \"${HOST}\" >/dev/null; then\n"
            + "    echo \"[$(date -Iseconds)] FAILED to dig ${HOST} @${LISTENER} directly; aborting takeover\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  echo \"[$(date -Iseconds)] verified ${HOST} resolves via ${LISTENER} directly\"\n"
            + "fi\n"
            + "\n"
            + "# Take over /etc/resolv.conf with a plain file pointing at the bosh-dns listener.\n"
            + "# Atomic replace via mv -f, so anything in the middle of a lookup sees either the\n"
            + "# old symlink or the new plain file, never a half-written state.\n"
            + "if [ -L /etc/resolv.conf ] || ! grep -q \"^nameserver ${LISTENER}\\$\" /etc/resolv.conf 2>/dev/null; then\n"
            + "  tmp=$(mktemp /etc/resolv.conf.cfdcpi.XXXXXX)\n"
            + "  cat > \"$tmp\" <<EOF\n"
            + "# Managed by cf-docker-cpi-dns-wait pre-start (issue #16).\n"
            + "# Original /etc/resolv.conf was a symlink to systemd-resolved's stub; that's flaky\n"
            + "# from Go binaries on noble docker stemcells. Pointing directly at bosh-dns instead.\n"
            + "nameserver ${LISTENER}\n"
            + "options timeout:2 attempts:3\n"
            + "EOF\n"
            + "  chmod 0644 \"$tmp\"\n"
            + "  mv -f \"$tmp\" /etc/resolv.conf\n"
            + "  echo \"[$(date -Iseconds)] /etc/resolv.conf rewritten to plain file with nameserver ${LISTENER}\"\n"
            + "else\n"
            + "  echo \"[$(date -Iseconds)] /etc/resolv.conf already a plain file pointing at ${LISTENER}; skipping takeover\"\n"
            + "fi\n"
            + "\n"
            + "# Re-verify libc still works through the new plain file (sanity).\n"
            + "if ! getent hosts \"$HOST\" >/dev/null 2>&1; then\n"
            + "  echo \"[$(date -Iseconds)] FAILED: ${HOST} no longer resolves after takeover\"\n"
            + "  exit 1\n"
            + "fi\n"
            + "echo \"[$(date -Iseconds)] takeover verified; pre-start done\"\n";
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
