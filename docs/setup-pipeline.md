# Setup pipeline reference

This document is the canonical reference for every step in the setup pipeline. Each GitHub issue with the `step` label links here for detail.

For the orchestrator, state model, and SSH-tunnel design, see [architecture.md](architecture.md).

## 1. `verify-docker`

Wraps the existing `VerificationService` (also exposed standalone as `docker verify`).

- **Inputs**: `target` (Docker URI), `remoteSocket`.
- **Outputs**: none on disk; result recorded in `status.json` only.
- **Cheap check**: previous status PASS within the last 24 h.
- **Deep check** (`--verify`): re-run the full check sequence (ping → version → info → OS → arch → resources → CPI prereqs → API version).
- **Failure modes**: any FAIL from an underlying check halts the pipeline.

## 2. `host-setup`

Tune docker-host kernel limits and AppArmor profiles that affect stemcell containers. Two concerns today (the step is meant to absorb additional host-level prereqs as they're discovered):

1. `fs.inotify.*` sysctls (issue #14).
2. The host's `nc.openbsd` AppArmor profile (issue #16 follow-up — discovered while running diego-cell on noble; details below).

- **Inputs**: the SSH target.
- **Outputs**:
  - `/etc/sysctl.d/99-cf-docker-cpi.conf` on the docker host with `fs.inotify.max_user_instances = 8192` and `fs.inotify.max_user_watches = 65536`, plus a live `sysctl --load` so it takes effect immediately. Only written when current values are below the floor.
  - `/etc/apparmor.d/disable/nc.openbsd` symlink (or `usr.bin.nc.openbsd` on older Ubuntu) and a live `apparmor_parser -R` to unload the profile from the running kernel. Only applied when the profile is currently enforced.
- **Cheap check**: status PASS exists.
- **Deep check** (`--verify`): re-read `/proc/sys/fs/inotify/{max_user_instances,max_user_watches}` and re-probe AppArmor (`aa-status` + the disable/ symlink), confirm everything is still in place.
- **Failure modes**:
  - User lacks passwordless sudo on the docker host → the step prints both manual recipes (sysctl and AppArmor) and exits.
  - Sysctl applied but the kernel reports values still below the floor (extremely unlikely; would mean another sysctl drop-in overrides ours).
  - AppArmor: profile file path differs again in a future Ubuntu release. The step probes both `nc.openbsd` and `usr.bin.nc.openbsd` under `/etc/apparmor.d/`; a third name would require adding a candidate.

### Why the sysctl bit exists

The Linux default `fs.inotify.max_user_instances = 128` is a per-uid host-wide cap. Every stemcell container runs its own systemd + journald + udevd, all as uid 0 which maps to the host's uid 0 (non-userns docker). With ~16 CF instance-group VMs the host's pool is exhausted; new processes inside late-starting containers get `EMFILE` from `inotify_init()`. systemd-udevd inside the container then crash-loops, and the BOSH agent fails to come back up after the CPI's apply-settings container restart. The director times out pinging the dead agent; the container itself stays `Up`. Bumping the per-uid cap to 8192 (the same value used by kubernetes/containerd reference setups) makes the pool effectively unbounded for our deploy.

### Why the AppArmor bit exists

Noble Ubuntu ships an AppArmor profile for `nc.openbsd` that denies the `dac_override` and `dac_read_search` capabilities. AppArmor profiles are kernel-global and apply to processes inside docker containers as well as host processes (the cell container shares the host kernel). cf-deployment's `garden/post-start` script pings garden via `echo … | nc -U /var/vcap/data/garden/garden.sock`. Inside the cell that `nc` invocation is confined by the host's profile and fails with `Permission denied`, even though the socket is mode `0777` and garden itself is up — `curl --unix-socket` to the same socket works because no AppArmor profile applies to `curl`. The garden post-start times out after 120 s, BOSH marks the canary `failing`, the deploy fails. Disabling the profile on the host (one-time, one symlink + `apparmor_parser -R`) is the smallest blast-radius fix; the profile has nothing to do with `nc`'s legitimate use cases and Ubuntu only ships it because nc.openbsd has a long history of CVEs.

## 3. `install-tools`

Download `bosh` and `cf` CLIs to `~/.cf-docker-cpi/bin/`. Versions pinned in code.

- **Inputs**: `os.name`, `os.arch`.
- **Outputs**: `~/.cf-docker-cpi/bin/bosh`, `~/.cf-docker-cpi/bin/cf` (executable on POSIX).
- **Cheap check**: both files exist and `--version` matches the pinned version.
- **Deep check**: same as cheap; nothing remote to probe.
- **Failure modes**: unknown OS/arch combo — fail with the detected `os.name`/`os.arch` in the error. Download checksum mismatch — fail with both expected and actual SHA.

## 4. `fetch-manifests`

Clone `cloudfoundry/bosh-deployment` and `cloudfoundry/cf-deployment` into the host state dir at pinned commits.

- **Inputs**: pinned commit SHAs (recorded in `target.json`).
- **Outputs**: `<host>/bosh-deployment/`, `<host>/cf-deployment/`.
- **Cheap check**: both dirs exist, `git rev-parse HEAD` matches the pinned SHA.
- **Deep check**: same; upstream is not contacted unless the user passes `--update` (future flag).
- **Failure modes**: network failure during clone (transient, retry). Pinned SHA not found — upstream rebased; the pin needs updating in code.

## 5. `generate-director-vars`

Write `director-vars.yml` with director IP, internal CIDR, and the runtime docker socket URL.

- **Inputs**: `SetupContext.directorIp` (default `10.245.0.11`), `SetupContext.internalCidr` (default `10.245.0.0/24`), `SetupContext.tunnel.dockerHostUrl()`.
- **Outputs**: `<host>/director-vars.yml`.
- **Cheap check**: file exists with the right keys. We don't compare value content because the docker host URL changes between runs.
- **Deep check**: same as cheap.
- **Failure modes**: state dir not writable; tunnel not yet established when called (orchestrator bug — fail loudly).

## 6. `deploy-director`

`bosh create-env` the BOSH director. ~5 min on first run.

- **Inputs**: `bosh-deployment/bosh.yml`, `bosh-deployment/docker/cpi.yml`, `bosh-deployment/jumpbox-user.yml`, `bosh-deployment/uaa.yml`, `bosh-deployment/credhub.yml`, `director-vars.yml`, plus an inlined `docker-cpi-overrides.yml` ops file. UAA + CredHub are colocated on the director so the director has a built-in config server — required for generating the `/-prefixed` shared variables that the `dns` runtime-config defines (see §10).
- **Outputs**: `director-state.json` (contains VM CID), `director-creds.yml` (admin password, mTLS certs, jumpbox SSH key).
- **Cheap check**: `director-state.json` exists with a non-empty `current_vm_cid` and matches the recorded `current_manifest_sha`.
- **Deep check**: `bosh env -e <slug>` succeeds against the director IP through the tunnel.
- **Failure modes**:
  - State file present but director container gone (CPI lost it) → suggest `setup reset --step deploy-director`.
  - Container resource limits insufficient → bosh CLI emits a clear error.
  - CPI can't reach docker → check `tls/` on the host and dockerd's TLS config (below).
  - Bootstrap exits 78 with `tls/ca.pem missing` → see "dockerd TLS prereq" below.
  - `dial tcp 10.245.0.11:6868: i/o timeout` from the bosh CLI → on WSL2 docker hosts the `cf-docker-cpi-net` bridge isn't routable from the WSL shell by default. See "WSL2 routing prereq" below.

### WSL2 routing prereq

When the docker host is a WSL2 distro on Windows 11, the `cf-docker-cpi-net` bridge (`10.245.0.0/24`) is created inside Docker Desktop's helper VM and not exposed to the WSL2 shell. `bosh create-env` then can't reach `10.245.0.11:6868` to push the rest of the deployment. The fix is to enable WSL's mirrored networking mode on the Windows side (one-time host config; see the README's "WSL2 docker host notes" for the exact `.wslconfig` snippet and `wsl --shutdown` recipe). Verify on the docker host with:

```bash
ip route show | grep -E '10\.245|cf-docker'   # should print a route after the docker network is created
nc -w3 -zv 10.245.0.11 6868                   # should connect once the director container is up
```

If you can't enable mirrored networking, this CLI doesn't currently have a workaround — see issue #13 for the rejected sidecar-container alternative.

### dockerd TLS prereq

The director container runs on a dedicated `cf-docker-cpi-net` bridge (gateway `10.245.0.1`). The in-container CPI talks to dockerd over **TLS-on-TCP at `tcp://10.245.0.1:2376`**. This is non-negotiable:

- bosh-docker-cpi 0.2.12's `cpi.json.erb` reads `p('docker_cpi.docker.tls.ca')` unconditionally; whenever a `tls` block is rendered the Go docker client uses HTTPS regardless of the URL scheme. Plain `tcp://` with dummy certs fails with `http: server gave HTTP response to HTTPS client`.
- Bind-mounting `/var/run/docker.sock` into the director also fails because `/var/run` is a tmpfs in the noble stemcell.

So dockerd must be reconfigured (one-time per host):

```bash
sudo install -d -m 0755 /etc/docker/tls
sudo install -m 0444 -o root -g root <CA + server cert/key, signed s.t. SAN includes 10.245.0.1> /etc/docker/tls/
sudo tee /etc/systemd/system/docker.service.d/tcp-listen.conf >/dev/null <<'DROPIN'
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd \
  -H fd:// \
  -H tcp://0.0.0.0:2376 \
  --tlsverify \
  --tlscacert=/etc/docker/tls/ca.pem \
  --tlscert=/etc/docker/tls/server-cert.pem \
  --tlskey=/etc/docker/tls/server-key.pem \
  --containerd=/run/containerd/containerd.sock
DROPIN
sudo systemctl daemon-reload && sudo systemctl restart docker
```

`deploy-director` then requires a matching CA + client cert/key triple at `~/.cf-docker-cpi-work/tls/{ca,client-cert,client-key}.pem` on the docker host. They are injected into the BOSH ops file via `bosh create-env --var-file cf_docker_cpi_tls_{ca,cert,key}=tls/...`. There is no automated step that generates these certs yet — see the bootstrap precheck in `DeployDirectorStep`. The `cloud_provider` (host-side) CPI keeps `unix:///var/run/docker.sock` and an auto-generated dummy TLS block; unix:// never negotiates TLS so the dummy values are inert.

## 7. `login-director`

`bosh alias-env <slug>` then `bosh log-in` with admin creds from `director-creds.yml`.

- **Inputs**: director URL, admin credentials from creds file.
- **Outputs**: entry in `~/.bosh/config` for the alias.
- **Cheap check**: `~/.bosh/config` has the alias and `bosh env -e <slug>` succeeds.
- **Deep check**: same.
- **Failure modes**: director not reachable (likely tunnel issue). Creds file missing — step 6 didn't complete.

## 8. `upload-stemcell`

`bosh upload-stemcell` for the pinned warden stemcell version.

- **Inputs**: stemcell URL pinned in `ManifestVersions` (currently `bosh-warden-boshlite-ubuntu-noble` @ 1.364, fetched directly from `storage.googleapis.com/bosh-core-stemcells/` because bosh.io's `?v=` redirect for the noble line is unreliable). The version is the one cf-deployment `v56.4.0` expects.
- **Outputs**: stemcell present on director.
- **Cheap check**: status file says PASS in this director state.
- **Deep check**: `bosh stemcells` lists the pinned name+version.
- **Failure modes**: GCS transient unavailability; director out of disk — surfaced by bosh. CPI-to-dockerd connectivity issues surface here because this is the first step that actually exercises the in-container CPI (`create_stemcell` POSTs the image to dockerd) — if you see TLS/HTTPS errors, revisit §6's dockerd TLS prereq.

## 9. `update-cloud-config`

`bosh update-cloud-config` with `cf-deployment/iaas-support/bosh-lite/cloud-config.yml`.

- **Inputs**: the cloud-config YAML from the locally-cloned cf-deployment (`<state-dir>/cf-deployment/iaas-support/bosh-lite/cloud-config.yml`). The file is `scp`'d to `~/.cf-docker-cpi-work/cloud-config.yml` on the docker host on every run.
- **Outputs**: cloud-config in the director; no new local file. `status.json` records `file_sha=<8> applied_sha=<8>` — the SHA of the source file we sent, and the SHA of what `bosh cloud-config` returns from the director after apply.
- **Cheap check**: status PASS exists AND the current local file's SHA matches the recorded `file_sha`. If the cf-deployment pin moves (different SHA), the step is re-run.
- **Deep check** (`--verify`): cheap check passes AND `bosh -e <slug> cloud-config | sha256sum` on the remote matches the recorded `applied_sha`. Catches director-side drift (manual edits, lost state).
- **Failure modes**: director not logged in (step 7 regressed); YAML syntax error in cf-deployment (upstream issue); `bosh cloud-config` returns the empty/none response after a successful apply (director persistence bug — surfaces as missing `applied_sha` in the run output).

## 10. `update-runtime-config`

Applies **two** named runtime-configs to the director:

1. `bosh update-runtime-config bosh-deployment/runtime-configs/dns.yml -o dns-recursors-overrides.yml --name dns` — the upstream bosh-dns addon, with a small ops file layered on top that flips `disable_recursors → false` and sets `recursors → [8.8.8.8, 1.1.1.1]` on the noble addon. Without this, `route_registrar` (and anything else resolving `*.service.cf.internal`) crashes during `deploy-cf` with `dial tcp: lookup nats.service.cf.internal on 127.0.0.53:53: server misbehaving`, and the java_buildpack can't fetch the JRE during app staging. See "Why we also layer `dns-recursors-overrides.yml` on `dns.yml`" below.
2. `bosh update-runtime-config dns-wait-runtime-config.yml --name dns-wait` — a tiny in-repo BOSH release (`cf-docker-cpi-dns-wait/0.1.0`) co-located via an addon on the `diego-cell` instance group. Its single job `wait-for-locket-dns` is a pre-start-only hook that polls `getent hosts locket.service.cf.internal` until success (or fails after 5 min). See issue #16: on noble docker stemcells, `systemd-resolved → bosh-dns` forwarding races bosh-dns coming up on the local cell VM; `rep` panics in `initializeCellPresence` with `failed-to-construct-locket-client / context deadline exceeded` before the resolver settles. Holding the VM in pre-start until libc can resolve `locket` lets bosh-dns finish settling on that VM.

The dns-wait release source is materialised on the docker host inside the step (no separate tarball checked into the repo). The step `bosh create-release --tarball`'s it and `bosh upload-release`'s it, skipping both if `bosh releases` already lists `cf-docker-cpi-dns-wait/0.1.0`. Bump `DNS_WAIT_RELEASE_VERSION` in `UpdateRuntimeConfigStep.java` if the pre-start script changes.

- **Inputs**: `bosh-deployment/runtime-configs/dns.yml` from the remote `~/.cf-docker-cpi-work/` clone (deploy-director already put it there); the inline release source written by the step under `~/.cf-docker-cpi-work/dns-wait-release/`.
- **Outputs**: two runtime-configs registered on the director (`dns`, `dns-wait`) and the `cf-docker-cpi-dns-wait` release uploaded. `status.json` records `dns_sha=<8> dns_wait_sha=<8>`.
- **Cheap check**: status PASS exists and detail contains both `dns_sha=` and `dns_wait_sha=`.
- **Deep check** (`--verify`): both `bosh runtime-config --name dns | sha256sum` and `bosh runtime-config --name dns-wait | sha256sum` on the remote match the recorded SHAs.
- **Failure modes**:
  - Director not logged in (step 7 regressed); `dns.yml` missing from the remote clone (deploy-director didn't run).
  - Director lacks a config server, e.g., UAA/CredHub were dropped from deploy-director — `bosh update-runtime-config` fails with `Failed to generate variable '/dns_healthcheck_tls_ca' from config server`.
  - `bosh create-release` / `bosh upload-release` failures for `cf-docker-cpi-dns-wait` — surfaced verbatim in the step log.

### Why a custom BOSH release for one bash pre-start

Background in issue #16. We tried `configure_systemd_resolved: false` + `override_nameserver: true` first; that fails universally on noble because `/etc/resolv.conf` is a symlink owned by systemd-resolved and bosh-dns's `override_nameserver` writes through the symlink only to have systemd-resolved rewrite it back. A BOSH addon co-located on `diego-cell` is the smallest unit BOSH offers for "run a pre-start on a specific instance group" — there's no in-manifest hook for raw shell. The release has zero packages, one job, one template; `bosh create-release --force --name ... --version ... --tarball ...` builds it locally from inline files in roughly two seconds. If the wait fails after 5 min, the VM goes `failing` with the failure clearly logged in `/var/vcap/sys/log/wait-for-locket-dns/pre-start.log` rather than the user seeing a confusing rep crash.

### Why we also layer `dns-recursors-overrides.yml` on `dns.yml`

bosh-deployment's `dns.yml` ships a `bosh-dns-systemd` addon for the ubuntu-noble stemcell that sets `disable_recursors: true` and leaves `recursors: []`. On a stock cf-deployment this is fine because libc on the cell goes through systemd-resolved → bosh-dns → the host's recursive resolver, which catches external names. After dns-wait v0.2.0 rewrites `/etc/resolv.conf` to point directly at bosh-dns, that fallback is gone: external names like `buildpacks.cloudfoundry.org` (which the java_buildpack staging fetches the JRE from) hit bosh-dns, bosh-dns has nowhere to forward them, and the staging container sees `server misbehaving` from the cell's `169.254.0.53` bosh-dns-adapter, causing `BuildpackCompileFailed`. The ops file flips `disable_recursors → false` and adds `recursors: [8.8.8.8, 1.1.1.1]` on the noble addon. Trade-off: bosh-dns now exposes a public-resolver path to every VM, which is acceptable for a development bosh-lite-style deploy but should be parameterised before this is used in any environment where outbound DNS to public resolvers is restricted.

## 11. `deploy-cf`

`bosh deploy` cf-deployment with bosh-lite ops files. **The long step** (30-60 min on first run).

- **Inputs**: `cf-deployment/cf-deployment.yml` + `operations/bosh-lite.yml` + `operations/use-compiled-releases.yml`, an inlined `cf-deployment-docker-cpi-overrides.yml` ops file, `--system-domain` (default `bosh-lite.com`), `cf-creds.yml` vars-store.
- **Outputs**: `<host>/cf-creds.yml` (scp'd back from the docker host); CF running on the director.
- **Cheap check**: status PASS AND local `cf-creds.yml` has a non-empty `cf_admin_password` value.
- **Deep check** (`--verify`): cheap check PLUS `bosh -e <slug> -d cf deployment` exits 0 on the remote (deployment is registered with the director).
- **Pre-run resource check**: SSHes the host and queries `docker info --format '{{.MemTotal}}'` and `df -B1 --output=avail <DockerRootDir>`. Fails fast if MemTotal < 16 GiB or disk free < 50 GiB. Bypass with `--ignore-resource-check`.
- **Inlined ops** (`cf-deployment-docker-cpi-overrides.yml`):
  - Pins `instance_groups/router/networks[default]/static_ips` to `10.245.0.34` (cf-deployment's bosh-lite.yml hardcodes `10.244.0.34`, outside our `cf-docker-cpi-net` subnet).
  - Updates the `load_balancer` security group rule destination to match.
- **Failure modes**:
  - Stemcell missing (step 8 didn't run).
  - Host out of RAM/disk — caught by the precheck unless bypassed.
  - Release downloads timeout (transient; resume the step — bosh deploy is idempotent).
  - **Issue #14 root cause + fix**: the previously-observed "pxc-mysql post-stop hang" was a symptom, not the cause. The actual problem was host `fs.inotify.max_user_instances=128` (Linux default) being exhausted by ~16 stemcell containers each running their own systemd + journald + udevd against the host's per-uid inotify pool. Containers brought up later in the deploy (database, which is updated after the nats canary) lose the race, their `systemd-udevd` crash-loops with `Failed to create inotify descriptor: Too many open files`, and the bosh-agent fails to come back up after the CPI's apply-settings container restart. The director then times out pinging the dead agent. Fixed by the `host-setup` step (§2) which raises `fs.inotify.max_user_instances` to >= 8192 on the docker host.

The step streams `bosh` stdout live to both the terminal and `logs/deploy-cf-<ts>.log`.

### Why update-cloud-config does so much more than "apply YAML"

cf-deployment v56.4.0's bosh-lite cloud-config emits three things that bosh-docker-cpi 0.2.12 can't handle as-is. `UpdateCloudConfigStep` writes a `cloud-config-docker-cpi-overrides.yml` on the remote and runs `bosh interpolate` before applying:

- `vm_extensions/ssh-proxy-and-router-lb/cloud_properties/ports`: `[{host: 80}, ...]` → `["80", "443", "2222"]` (CPI wants `[]string`).
- `vm_extensions/cf-tcp-router-network-properties/cloud_properties/ports`: `["1024-1123"]` → `[]` (docker rejects range syntax).
- `networks/default/subnets[0]`: `cloud_properties.name: random` + `10.244.0.0/20` → `name: cf-docker-cpi-net` + `10.245.0.0/24` (statics 10.245.0.12-99). The default `random` directive makes the CPI create a fresh isolated bridge per deploy; the director (on `cf-docker-cpi-net`) can't NATS into VMs on a sibling bridge. Sharing the network puts VMs and director on the same L2.

## 12. `configure-cf-cli`

Point the local `cf` CLI at the new Cloud Foundry, log in as admin, create the `system/dev` org & space. Uses an **isolated `CF_HOME` under `<state-dir>/cf-home`** so this tool never clobbers the user's existing `~/.cf`.

- **Inputs**: local `cf` binary at `<bin-dir>/cf`, admin password from `<state-dir>/cf-creds.yml`, `system_domain`.
- **Outputs**: `<state-dir>/cf-home/config.json` targeted at the new CF; `system` org with `dev` space; if missing, `/etc/hosts` rewritten (when `--write-hosts` is set).
- **Cheap check**: status PASS exists AND `<state-dir>/cf-home/` exists.
- **Deep check** (`--verify`): cheap check PLUS `cf target` (with the isolated `CF_HOME`) reads `api.<system_domain>` and org=system / space=dev.
- **Failure modes**:
  - `cf` binary missing → run install-tools.
  - `cf_admin_password` not in `cf-creds.yml` → deploy-cf didn't complete.
  - Hostnames don't resolve locally → step exits with the exact `/etc/hosts` line; re-run with `--write-hosts` to apply it via `sudo tee -a`.
  - SSH local-forward fails to bind `localhost:8443` → likely a stale tunnel from a prior run; kill it and retry (the step falls back to a random free port automatically).
  - cf API returns a 5xx during `cf auth` → director/router not fully up yet; re-run after a minute.

### How the step reaches haproxy

The cf-deployment haproxy/router is pinned to `10.245.0.34` on the `cf-docker-cpi-net` bridge (see `DeployCfStep.ROUTER_STATIC_IP`). Only the docker host itself sees that bridge directly. For local `cf` to reach it, the step opens an SSH local-forward `localhost:8443 → 10.245.0.34:443` (via `SshLocalForward`) for the duration of the cf commands. cf is then pointed at `https://api.<system_domain>:8443 --skip-ssl-validation`.

`/etc/hosts` is necessary on the laptop because cf uses the hostname (not the IP) when connecting and routing through haproxy. Required entries:

```
127.0.0.1 api.<system_domain> login.<system_domain> uaa.<system_domain> cf-smoke.<system_domain>
```

`--write-hosts` shells out to `sudo tee -a /etc/hosts` (interactive password prompt) and adds the line bracketed by `# cf-docker-cpi (...)` markers so it's easy to find / remove later. Local resolution is verified again after the write before continuing.

Gotcha (carried over from earlier docs): on WSL2 the docker daemon may not bind haproxy on `0.0.0.0` reliably. If localhost:8443 connects but every cf request hangs, the escape hatch is to `cf push` from a container colocated on the docker host via `bosh ssh diego-cell/0` and the `cf` binary copied into that container.

## 13. `smoke-push`

Fetch a minimal Spring Boot web app from start.spring.io, build it locally with the project's bundled `mvnw`, `cf push` it through the same SSH local-forward, then HTTP-GET `/actuator/health` until it returns 200 (timeout 120 s).

- **Inputs**: `<state-dir>/cf-home/` (configure-cf-cli ran), `<bin-dir>/cf`, `system_domain`.
- **Outputs**: `<state-dir>/cf-smoke/` (extracted starter), `<state-dir>/cf-smoke/target/cf-smoke-*.jar`, `<state-dir>/cf-smoke/manifest.yml`, the `cf-smoke` app pushed to `system/dev`.
- **Cheap check**: status PASS exists.
- **Deep check** (`--verify`): re-runs the step (push is idempotent — cf push will skip the upload if the bits match, and the health probe re-confirms).
- **Failure modes**:
  - Spring Initializr unreachable → step exits with the HTTP status from `start.spring.io`.
  - Build fails → look at the `[build]` block of the step log; usually a transient Maven Central / artifact-proxy issue, sometimes a Java version mismatch.
  - `cf push` reports `failed` for the app → app instance crashed; `cf logs cf-smoke --recent` (with `CF_HOME=<state-dir>/cf-home`) shows the stack trace.
  - HTTP probe times out → tunnel died mid-run (look for `ssh exited` lines), or the app is still warming up (raise `HEALTH_TIMEOUT_SECONDS`).

The starter is fetched as:

```
https://start.spring.io/starter.zip
  ?type=maven-project
  &dependencies=web,actuator
  &name=cf-smoke
  &packageName=com.dashaun.smoke
  &groupId=com.dashaun
  &artifactId=cf-smoke
  &javaVersion=17
```

actuator is included so we have a known-good `/actuator/health` URL to probe; `javaVersion=17` matches what java_buildpack v4.x+ supports. The generated manifest pins `JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 17.+ } }'` in `env:` to override the buildpack's JDK-8 default. If the step is re-run, the starter zip is *not* re-downloaded (the `pom.xml` presence check short-circuits) — `rm -rf <state-dir>/cf-smoke` to force a fresh fetch.

The HTTP probe uses Java's `HttpClient` with a permissive `SSLContext` (the haproxy cert is self-signed and the connect goes through the local-forward tunnel — `--skip-ssl-validation` equivalent). Pollster polls every 3 s and tolerates `ConnectException` / `SSLException` / 502 / 503 as "still booting"; only a clean 200 wins.
