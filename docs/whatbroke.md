<!-- .slide: data-background-color="#191e1e" -->

# What broke

### Four rabbit holes from validating end-to-end

Honest version: every one of these is now invisible because the tool fixes it. But finding them took hours.

Notes:
**~6 min** — this is the section the audience came for. Pace it. Each rabbit hole has the same shape: symptom → wrong hypothesis → real cause → fix.

---

## #1 — diego-cell `rep` crash-loops on noble

### Symptom

```text
'diego-cell/...' is not running after update.
Review logs for failed jobs: rep
```

```text
panic: context deadline exceeded
goroutine 1 [running]:
... initializeCellPresence ...
    rep/cmd/rep/main.go:265 +0xf6
```

`rep` (the Diego cell agent) panics dialing `locket.service.cf.internal:8891`. Every other CF instance group comes up clean. Only `diego-cell` loses the race.

---

## #1 — the wrong hypothesis

> "Garden + runc inside a docker container is the classic docker-in-docker problem — nested namespaces, AppArmor, mount propagation. The CPI probably doesn't pass enough caps."

Plausible. Wrong.

`bosh ssh diego-cell/0` and look around:

```bash
$ dig @169.254.0.2 locket.service.cf.internal +short
10.245.0.4                                    ← bosh-dns answers fine

$ dig @127.0.0.53 locket.service.cf.internal +short
;; no servers could be reached               ← systemd-resolved stub doesn't

$ getent hosts locket.service.cf.internal     ← takes 40s, returns nothing
```

bosh-dns is healthy. **systemd-resolved → bosh-dns forwarding is flaky on noble.** Stub forwarder loses queries.

---

## #1 — the real fix (and a layer underneath)

A custom BOSH addon, `cf-docker-cpi-dns-wait`, co-located on `diego-cell` only. Two-stage pre-start:

```bash
# 1. Wait for libc resolution to work (proves bosh-dns is up).
while ! getent hosts locket.service.cf.internal >/dev/null; do
  sleep 5
done

# 2. Atomically replace /etc/resolv.conf with a plain file
#    pointing at bosh-dns directly (not at the systemd-resolved stub).
cat > /etc/resolv.conf.new <<EOF
nameserver 169.254.0.2
options timeout:2 attempts:3
EOF
mv -f /etc/resolv.conf.new /etc/resolv.conf
```

Why two stages? **Go binaries (rep, route_emitter) read `/etc/resolv.conf` directly and bypass nsswitch entirely.** They were dialing `127.0.0.53` (systemd-resolved stub) — which is the part that's flaky. Stage 2 cuts the stub out of the path.

`update-runtime-config` materialises and uploads the release inline; the addon ships as part of the pipeline.

---

## #2 — garden's post-start: `Permission denied` on a 0777 socket

### Symptom

```text
'diego-cell/...' is not running after update.
Review logs for failed jobs: garden
```

```text
2026-05-18T19:22:08Z: Attempt 117...
2026-05-18T19:23:09Z: Timed out pinging garden server.
```

`garden_ctl.stderr.log`:

```text
nc: /var/vcap/data/garden/garden.sock: Permission denied
nc: /var/vcap/data/garden/garden.sock: Permission denied
... (repeated for 120s)
```

But — `ls -la garden.sock`:

```text
srwxrwxrwx 1 root root 0 May 18 19:08 /var/vcap/data/garden/garden.sock
```

Mode 0777. Garden itself is up and creating containers fine. Just the post-start `nc` probe can't connect.

---

## #2 — the wrong hypothesis

> "Something about the socket path / mount namespace / bpm sandbox."

Wrong direction. Look at `dmesg`:

```text
apparmor="DENIED" operation="capable" class="cap"
  profile="nc.openbsd" pid=3328325 comm="nc"
  capability=2 capname="dac_read_search"
apparmor="DENIED" operation="capable" class="cap"
  profile="nc.openbsd" pid=3328325 comm="nc"
  capability=1 capname="dac_override"
```

Noble Ubuntu **ships an AppArmor profile for `nc.openbsd`** that denies the `dac_override` and `dac_read_search` capabilities. AppArmor is kernel-global — the profile applies to `nc` running **inside the diego-cell container** too. `curl --unix-socket ...` works because no profile applies to `curl`.

The garden post-start was hardcoded to `nc -U`. Permission denied. Garden never marked "up". BOSH gives up.

---

## #2 — the fix

`host-setup` now also disables the `nc.openbsd` AppArmor profile:

```bash
sudo ln -sf /etc/apparmor.d/nc.openbsd /etc/apparmor.d/disable/nc.openbsd
sudo apparmor_parser -R /etc/apparmor.d/nc.openbsd
```

Idempotent. Probes both `nc.openbsd` and `usr.bin.nc.openbsd` (path changed between older Ubuntu releases). NOT_PRESENT-no-ops gracefully on hosts without AppArmor at all (e.g. WSL2 distros).

```text
host-setup PASS  inotify limits OK; nc.openbsd AppArmor profile disabled
```

> The whole AppArmor profile exists because nc.openbsd has a long CVE history. Disabling it is fine for this use-case; if you'd rather keep it, you'd need to fork garden-runc-release with a different probe binary.

---

## #3 — `cf push` staging: `lookup buildpacks.cloudfoundry.org on 169.254.0.53:53: server misbehaving`

### Symptom

After rep and garden are both fixed, `cf push cf-smoke` uploads the bits, the diego-cell creates a staging container, and:

```text
error: Get "https://buildpacks.cloudfoundry.org/dependencies/openjdk/...":
  dial tcp: lookup buildpacks.cloudfoundry.org on 169.254.0.53:53: server misbehaving
BuildpackCompileFailed - App staging failed in the buildpack compile phase
```

`169.254.0.53` is bosh-dns-adapter (the per-container resolver). Hand-trace:

```bash
$ dig @169.254.0.2 buildpacks.cloudfoundry.org
;; ->>HEADER<<- opcode: QUERY, status: REFUSED
;; WARNING: recursion requested but not available
```

bosh-dns is REFUSING recursion.

---

## #3 — caused by our own fix

Look at the bosh-dns config on the cell:

```json
{
  "disable_recursors": true,
  "recursors": [],
  "excluded_recursors": [],
  ...
}
```

`disable_recursors: true`, `recursors: []`. Why?

bosh-deployment's `dns.yml` ships those defaults for the **`bosh-dns-systemd` addon** (the noble path). The assumption: `systemd-resolved` on the host handles external names; bosh-dns only answers `*.service.cf.internal`.

**But our rabbit hole #1 fix took systemd-resolved out of the path.** `/etc/resolv.conf` now points directly at bosh-dns. bosh-dns has nowhere to forward unknown names. SERVFAIL. Java buildpack can't fetch the JRE.

---

## #3 — the fix

A small ops file layered on `dns.yml` in `update-runtime-config`:

```yaml
- type: replace
  path: /addons/name=bosh-dns-systemd/jobs/name=bosh-dns/properties/disable_recursors?
  value: false
- type: replace
  path: /addons/name=bosh-dns-systemd/jobs/name=bosh-dns/properties/recursors?
  value:
    - 8.8.8.8
    - 1.1.1.1
```

```bash
bosh update-runtime-config dns.yml -o dns-recursors-overrides.yml --name dns
```

Trade-off: public resolvers baked in. Fine for a development bosh-lite. Would parameterise for restricted environments.

> Lesson: when you replace one piece of OS plumbing, audit who else was reading the file you just replaced.

---

## #4 — WSL2: `mount: /sys/kernel/security: mount point does not exist`

A user asked: "will this work on WSL2?" Got everything through `update-runtime-config` (10/13 steps). Then garden:

```text
diego-cell/...   garden   failing

$ cat /var/vcap/sys/log/garden/garden_ctl.stderr.log
mount: /sys/kernel/security: mount point does not exist.
       dmesg(1) may have more information after failed mount system call.
```

`zcat /proc/config.gz | grep SECURITY` on the WSL2 host:

```text
CONFIG_KEYS=y
# CONFIG_SECURITYFS is not set
# CONFIG_SECURITY_NETWORK is not set
# CONFIG_SECURITY_APPARMOR is not set
```

Microsoft's stock WSL2 kernel doesn't compile in securityfs. Garden-runc needs it to set up per-container LSM isolation. **No `cf push` on stock WSL2.**

---

## #4 — the only known fix

Build a custom WSL2 kernel:

```bash
git clone https://github.com/microsoft/WSL2-Linux-Kernel
cd WSL2-Linux-Kernel
# get current config, enable the missing keys, rebuild
zcat /proc/config.gz > .config
sed -i 's/# CONFIG_SECURITYFS is not set/CONFIG_SECURITYFS=y/' .config
sed -i 's/# CONFIG_SECURITY_APPARMOR is not set/CONFIG_SECURITY_APPARMOR=y/' .config
sed -i 's/# CONFIG_SECURITY_NETWORK is not set/CONFIG_SECURITY_NETWORK=y/' .config
make oldconfig && make -j$(nproc)
# copy bzImage to C:\..., set `kernel=` in .wslconfig, `wsl --shutdown`.
```

Not currently supported by the tool. Tracked in [issue #22](https://github.com/dashaun/cloudfoundry-docker-cpi/issues/22). README has the full diagnostic walkthrough.

Two consolations from the WSL2 run that are real wins for everyone:
- **Mirrored networking turned out to be unnecessary** with native dockerd in the distro. The old docs were wrong; now corrected.
- **Docker 29's containerd-snapshotter is hostile to bosh-docker-cpi 0.2.12**. One-line `daemon.json` fix.

Notes:
- Each rabbit hole compressed to a single slide pair. There's depth behind each one (commit messages, PRs, original GitHub issues) for anyone curious post-talk.
