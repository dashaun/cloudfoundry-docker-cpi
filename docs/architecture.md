# Architecture

`cf-docker-cpi` orchestrates a series of well-defined, idempotent steps to take a Docker host and produce a working CloudFoundry deployment on it. Each step is individually runnable and verifiable. If a step fails, re-running picks up where it broke.

## Pipeline overview

The pipeline has 11 steps, declared in order. Each maps 1:1 to a GitHub issue with the `step` label. See [setup-pipeline.md](setup-pipeline.md) for per-step detail.

1. **`verify-docker`** — confirm host suitability (PASS/FAIL/WARN per check).
2. **`install-tools`** — auto-download pinned `bosh` and `cf` CLIs.
3. **`fetch-manifests`** — clone `bosh-deployment` and `cf-deployment` at pinned commits.
4. **`generate-director-vars`** — write the director vars file from context.
5. **`deploy-director`** — `bosh create-env` the director onto the docker host.
6. **`login-director`** — `bosh alias-env` and `bosh log-in`.
7. **`upload-stemcell`** — pinned warden stemcell.
8. **`update-cloud-config`** — apply cf-deployment's bosh-lite cloud-config.
9. **`deploy-cf`** — `bosh deploy` cf-deployment with the bosh-lite ops files (~30-60 min).
10. **`configure-cf-cli`** — `cf api` + `cf auth` + org/space.
11. **`smoke-push`** — fetch a tiny Spring Boot app from Spring Initializr, `cf push`, expect HTTP 200.

Each step has a cheap `check()` predicate that returns `ALREADY_DONE`, `NEEDS_RUN`, or `NEEDS_REPAIR` based on local artifacts and the persisted status file. Passing `--verify` upgrades `check()` to a deep probe that also queries remote state (`bosh env`, `cf api`, etc.).

## Core abstractions

```
SetupStep         interface { name; description; check(ctx); run(ctx); }
SetupContext      holds host slug, state dir, paths to tools, SSH tunnel handle, system domain, etc.
SetupOrchestrator runs steps in declared order; consults StatusStore between steps
```

`SetupOrchestrator.up()` walks the steps. `NEEDS_RUN` triggers `run()`; success advances. `FAILED` halts and persists the error in `status.json`.

`StepResult` is one of `ALREADY_DONE | RAN_OK | FAILED(detail)`.

## State directory layout

```
~/.cf-docker-cpi/
├── bin/
│   ├── bosh                      # auto-downloaded, version-pinned
│   └── cf
└── hosts/
    └── <host-slug>/              # e.g. ssh-zephyrus-2
        ├── status.json           # per-step status, timestamps, error details
        ├── target.json           # frozen target info (URI, remote socket, system_domain)
        ├── bosh-deployment/      # vendored upstream clone
        ├── cf-deployment/        # vendored upstream clone
        ├── director-state.json   # written by `bosh create-env`
        ├── director-creds.yml    # bosh-generated creds (admin pw, mTLS certs)
        ├── director-vars.yml     # our generated vars
        ├── cf-creds.yml          # cf-deployment vars-store
        └── logs/<step>-<ts>.log  # captured stdout/stderr from shell-outs
```

The host slug is derived from the target URI: scheme + host + optional port, with non-alphanumeric characters replaced by `-`. Examples:

| URI                                  | Slug                       |
|--------------------------------------|----------------------------|
| `ssh://user@zephyrus-2`              | `ssh-zephyrus-2`           |
| `tcp://192.168.1.10:2375`            | `tcp-192-168-1-10-2375`    |
| `unix:///var/run/docker.sock`        | `unix-local`               |

The target URI for a slug is frozen on first run. Using a different URI for the same slug fails unless `--force` is passed.

## SSH tunnel lifecycle

`docker-java` 3.5.1 has no native `ssh://` transport, so `SshTunnel` shells out to OpenSSH for a local port-forward to the remote docker socket. The verification command (`docker verify`) already uses this.

For setup we generalize ownership of the tunnel:

- When `setup up` (or any single step that touches BOSH) starts, the orchestrator opens **one** tunnel via `TunnelManager` and stores the handle in `SetupContext`.
- The cpi vars file is regenerated on every `bosh` invocation to reflect the current local port (`tcp://localhost:<port>`). The port is stable within one CLI command run but may change between runs.
- On command exit (success, failure, Ctrl+C, JVM shutdown) the tunnel is closed via `try-with-resources` plus a shutdown hook.
- Tunnel `stderr` is drained and tee'd into the step log file (`logs/<step>-<ts>.log`) so SSH failures surface alongside step output.

For purely local docker targets (`unix://`, `tcp://`) no tunnel is opened.

## Step contract

Every `SetupStep` implementation must:

1. **`check(ctx)` is cheap and offline by default.** Look at the persisted status file and on-disk artifacts under the host state dir. No network calls.
2. **`check(ctx)` with `ctx.verifyMode=true` may make remote calls.** Used by `setup status --verify` and `setup up --verify`.
3. **`run(ctx)` is idempotent.** Re-running after a partial failure produces the same end state. The underlying tool (`bosh`, `cf`) is itself idempotent in the relevant ways.
4. **`run(ctx)` writes its log to `logs/<step>-<ts>.log` and updates `status.json` on completion.**
5. **No step destroys artifacts owned by another step.** `setup destroy` is the only destructive command.

## Command surface

- `setup up [--host <uri>] [--system-domain <d>] [--verify] [--from <step>]` — run all steps; skip already-done.
- `setup step <name> [...]` — run one named step.
- `setup status [--verify]` — print per-step status table.
- `setup reset [--step <name>|--all]` — mark step(s) not-done; doesn't touch artifacts.
- `setup destroy [--yes]` — `bosh -d cf delete-deployment --force`, `bosh delete-env`, `rm -rf` the host state dir.

Existing `docker verify` stays as-is and is invoked by `setup up` via `VerificationService`, not by re-parsing args.

## Open issues

See [setup-pipeline.md](setup-pipeline.md) for step-by-step failure modes, and the [meta issue](https://github.com/dashaun/cloudfoundry-docker-cpi/issues) for the latest status on:

- **Network reachability local→deployed CF**: `/etc/hosts` + host port-forward is the recommended path; details in step 11.
- **WSL2 quirks**: WSL2 docker may not bind haproxy on `0.0.0.0` reliably; documented escape hatch is `bosh ssh` + colocated `cf push`.
- **`deploy-cf` runtime**: 30-60 min; bosh output streamed live.
- **Resources**: deploy-cf needs ~16 GB RAM and ~50 GB disk on the host.
- **Version drift**: `bosh-deployment`, `cf-deployment`, stemcell, and CLI versions are pinned in code.
