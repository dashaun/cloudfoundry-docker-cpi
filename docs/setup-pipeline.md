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

## 2. `install-tools`

Download `bosh` and `cf` CLIs to `~/.cf-docker-cpi/bin/`. Versions pinned in code.

- **Inputs**: `os.name`, `os.arch`.
- **Outputs**: `~/.cf-docker-cpi/bin/bosh`, `~/.cf-docker-cpi/bin/cf` (executable on POSIX).
- **Cheap check**: both files exist and `--version` matches the pinned version.
- **Deep check**: same as cheap; nothing remote to probe.
- **Failure modes**: unknown OS/arch combo — fail with the detected `os.name`/`os.arch` in the error. Download checksum mismatch — fail with both expected and actual SHA.

## 3. `fetch-manifests`

Clone `cloudfoundry/bosh-deployment` and `cloudfoundry/cf-deployment` into the host state dir at pinned commits.

- **Inputs**: pinned commit SHAs (recorded in `target.json`).
- **Outputs**: `<host>/bosh-deployment/`, `<host>/cf-deployment/`.
- **Cheap check**: both dirs exist, `git rev-parse HEAD` matches the pinned SHA.
- **Deep check**: same; upstream is not contacted unless the user passes `--update` (future flag).
- **Failure modes**: network failure during clone (transient, retry). Pinned SHA not found — upstream rebased; the pin needs updating in code.

## 4. `generate-director-vars`

Write `director-vars.yml` with director IP, internal CIDR, and the runtime docker socket URL.

- **Inputs**: `SetupContext.directorIp` (default `10.245.0.11`), `SetupContext.internalCidr` (default `10.245.0.0/24`), `SetupContext.tunnel.dockerHostUrl()`.
- **Outputs**: `<host>/director-vars.yml`.
- **Cheap check**: file exists with the right keys. We don't compare value content because the docker host URL changes between runs.
- **Deep check**: same as cheap.
- **Failure modes**: state dir not writable; tunnel not yet established when called (orchestrator bug — fail loudly).

## 5. `deploy-director`

`bosh create-env` the BOSH director. ~5 min on first run.

- **Inputs**: `bosh-deployment/bosh.yml`, `bosh-deployment/docker/cpi.yml`, `bosh-deployment/jumpbox-user.yml`, `director-vars.yml`.
- **Outputs**: `director-state.json` (contains VM CID), `director-creds.yml` (admin password, mTLS certs, jumpbox SSH key).
- **Cheap check**: `director-state.json` exists with a non-empty `current_vm_cid` and matches the recorded `current_manifest_sha`.
- **Deep check**: `bosh env -e <slug>` succeeds against the director IP through the tunnel.
- **Failure modes**:
  - State file present but director container gone (CPI lost it) → suggest `setup reset --step deploy-director`.
  - Container resource limits insufficient → bosh CLI emits a clear error.
  - CPI can't reach docker socket → check the tunnel; SSH stderr in the step log.

## 6. `login-director`

`bosh alias-env <slug>` then `bosh log-in` with admin creds from `director-creds.yml`.

- **Inputs**: director URL, admin credentials from creds file.
- **Outputs**: entry in `~/.bosh/config` for the alias.
- **Cheap check**: `~/.bosh/config` has the alias and `bosh env -e <slug>` succeeds.
- **Deep check**: same.
- **Failure modes**: director not reachable (likely tunnel issue). Creds file missing — step 5 didn't complete.

## 7. `upload-stemcell`

`bosh upload-stemcell` for the pinned warden stemcell version.

- **Inputs**: stemcell URL (`https://bosh.io/d/stemcells/bosh-warden-boshlite-ubuntu-jammy-go_agent?v=<version>`), pinned in code.
- **Outputs**: stemcell present on director.
- **Cheap check**: status file says PASS in this director state.
- **Deep check**: `bosh stemcells` lists the pinned name+version.
- **Failure modes**: bosh.io transient unavailability; director out of disk — surfaced by bosh.

## 8. `update-cloud-config`

`bosh update-cloud-config` with `cf-deployment/iaas-support/bosh-lite/cloud-config.yml`.

- **Inputs**: the cloud-config YAML from the cloned cf-deployment.
- **Outputs**: cloud-config in the director.
- **Cheap check**: SHA of the local file matches the SHA recorded in `status.json`.
- **Deep check**: `bosh cloud-config` output's SHA matches the local file.
- **Failure modes**: director not logged in (step 6 regressed); YAML syntax error in cf-deployment (upstream issue).

## 9. `deploy-cf`

`bosh deploy` cf-deployment with bosh-lite ops files. **The long step** (30-60 min on first run).

- **Inputs**: `cf-deployment/cf-deployment.yml`, `cf-deployment/operations/bosh-lite.yml`, `cf-deployment/operations/use-compiled-releases.yml`, `system_domain` var, `cf-creds.yml` (vars-store).
- **Outputs**: `cf-creds.yml` populated with generated passwords/certs; CF running on the director.
- **Cheap check**: status PASS and `cf-creds.yml` exists with `cf_admin_password` populated.
- **Deep check**: `bosh -d cf deployment` shows the deployment in `success` state, no failed tasks since.
- **Pre-run resource check**: from `docker info`, fail fast if `MemTotal` < 16 GiB or disk free < 50 GiB. Bypass with `--ignore-resource-check`.
- **Failure modes**:
  - Stemcell missing (step 7 didn't run).
  - Host out of RAM/disk — caught by the precheck unless bypassed.
  - Release downloads timeout (transient; resume the step).
  - Output is streamed live to terminal AND `logs/deploy-cf-<ts>.log` so users see progress.

## 10. `configure-cf-cli`

Point `cf` at the new CF, log in as admin, create initial org/space.

- **Inputs**: `system_domain`, admin password from `cf-creds.yml`.
- **Outputs**: `~/.cf/config.json` targeted at the new CF; `system` org with `dev` space.
- **Cheap check**: status PASS and `cf target` reads the expected api/org/space.
- **Deep check**: `cf api`, `cf orgs`, `cf spaces` all succeed.
- **Failure modes**:
  - API endpoint unreachable — likely `/etc/hosts` entry missing or haproxy port-forward not set up.
  - SSL errors (we use `--skip-ssl-validation`).
  - Admin pw not found — step 9 didn't complete.

### `/etc/hosts` reachability

For the local `cf` CLI to reach `api.<system_domain>`, the system_domain hostname must resolve to an IP where the docker host's haproxy is reachable. Two cases:

- **Local docker**: haproxy on `127.0.0.1:80/443`. Add to `/etc/hosts`:
  ```
  127.0.0.1 api.<system_domain> login.<system_domain> uaa.<system_domain> cf-smoke.<system_domain>
  ```
- **SSH-remote docker**: `smoke-push` opens a tunnel `localhost:8443 → <remote>:443`. Set `cf api https://api.<system_domain>:8443 --skip-ssl-validation` and use the same `/etc/hosts` line.

Pass `--write-hosts` (interactive mode only, requires sudo) to have the step append the line for you. Documented gotcha: on WSL2 the docker daemon may not bind haproxy on `0.0.0.0` reliably; the escape hatch is to `cf push` from a container colocated on the docker host via `bosh ssh`.

## 11. `smoke-push`

Fetch a minimal Spring Boot web app, build it, `cf push`. Verify HTTP 200.

- **Inputs**: `cf` target, `system_domain`.
- **Outputs**: `cf-smoke` app running in CF, returning 200 on its route.
- **Cheap check**: status PASS in the current cf target.
- **Deep check**: `cf app cf-smoke` reports `running` and `curl` to the route returns HTTP 200.
- **Failure modes**:
  - Spring Initializr unreachable.
  - Build fails (Java version mismatch — usually fine since we use the system `mvnw`).
  - Route DNS unresolvable from local — same `/etc/hosts` issue as step 10.
  - App crashes during start — instance logs surfaced via `cf logs cf-smoke --recent`.

The app is fetched via:

```bash
curl -L 'https://start.spring.io/starter.zip?type=maven-project&dependencies=web&name=cf-smoke&packageName=com.dashaun.smoke' -o cf-smoke.zip
```

unzipped under the host state dir, then `./mvnw package -DskipTests` and `cf push cf-smoke` with a minimal `manifest.yml`.
