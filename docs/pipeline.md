<!-- .slide: data-background-color="#191e1e" -->

# The Pipeline

### Thirteen idempotent, resumable steps

`setup step --name X --host ssh://user@host`

Notes:
**~5 min** — show the shape, not every step. The key ideas: per-step idempotency, per-step `--verify`, per-host state directory, resumability after partial failure.

---

## All 13 at a glance

| # | Step | Time | What |
|---|---|---|---|
| 1 | verify-docker | 5s | Host meets bosh-docker-cpi prereqs |
| 2 | host-setup | 5s | inotify sysctls + nc.openbsd AppArmor disable |
| 3 | install-tools | 5s | Pinned bosh + cf CLIs |
| 4 | fetch-manifests | 10s | Clone bosh-deployment + cf-deployment |
| 5 | generate-director-vars | <1s | Write director-vars.yml |
| 6 | deploy-director | 5–7m | `bosh create-env` the director |
| 7 | login-director | 5s | bosh alias + log in |
| 8 | upload-stemcell | 1m | ubuntu-noble@1.364 |
| 9 | update-cloud-config | 2s | bosh-lite cloud-config + CPI overrides |
| 10 | update-runtime-config | 10s | bosh-dns + dns-wait + recursors override |
| 11 | **deploy-cf** | **23–35m** | `bosh deploy` the cf-deployment.yml |
| 12 | configure-cf-cli | 15s | cf api + auth + system/dev org+space |
| 13 | smoke-push | 1m | Build a Spring Boot starter, push, probe 200 |

**Total wall-clock from scratch: ~35 min.** Two long steps (`deploy-director`, `deploy-cf`); everything else is sub-minute.

---

## State directory

```text
~/.cf-docker-cpi/
├── bin/{bosh,cf}                          # pinned tool versions
└── hosts/<slug>/                          # one dir per docker host
    ├── status.json                        # per-step status + detail
    ├── target.json                        # what host this is + pins
    ├── director-vars.yml                  # generated for deploy-director
    ├── director-state.json                # bosh-state, scp'd back after deploy
    ├── director-creds.yml                 # admin pw, mTLS, jumpbox key
    ├── cf-creds.yml                       # cf admin pw, vars-store
    ├── cf-smoke/                          # the Spring Boot starter
    │   ├── pom.xml
    │   └── target/cf-smoke-*.jar
    └── logs/                              # one per step run, timestamped
        ├── deploy-director-20260520-...log
        └── deploy-cf-20260520-...log
```

**Per-host slug** (`ssh-zephyrus-2`, `ssh-senshin`, `unix-local`) means you can drive multiple deploys from one laptop without state collision.

---

## How each step decides what to do

```text
StepCheck check(SetupContext):
   → if status.json says PASS and --verify is off  → ALREADY_DONE  (SKIP)
   → if status.json says PASS and --verify is on   → run a "deep check"
      (e.g. `bosh runtime-config --name dns | sha256sum` vs recorded sha)
   → otherwise                                     → NEEDS_RUN

StepResult run(SetupContext):
   → do the thing
   → write a fresh entry to status.json (PASS or FAIL + a one-line detail)
```

- **Cheap check** (default): a JSON file probe + a regex match. Fast, no SSH.
- **Deep check** (`--verify`): SSHes the host and re-verifies what's actually deployed. Use after a director restart or before a re-deploy.
- **Force re-run** (`--force`): ignore the check, run anyway.

---

## Resumability example

Suppose `deploy-cf` died at minute 28 (`Failed instance: api/0 errand`). You fix the underlying cause, then:

```bash
java -Dspring.shell.interactive.enabled=false \
  -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  setup step --name deploy-cf --host ssh://user@host
```

- Status was FAIL → step runs.
- The script's first line is `cd ~/.cf-docker-cpi-work && ./bin/bosh -e ... -d cf deploy ...`. **`bosh deploy` itself is idempotent** — it re-renders the manifest, sees what's already at the right version, and only re-applies what changed.
- The 25 instance groups that succeeded earlier are no-ops. Whatever failed gets re-tried with whatever you fixed.

The tool doesn't reinvent BOSH's resumability — it leans on it.

---

## What's NOT in this tool yet

Worth knowing what we punted:

- **`setup reset --step X`** — would wipe per-step state safely. Today you `rm` a couple of files.
- **`setup run-all`** — chains all 13 steps. Today you run them by name (or in a `for` loop, see Section 4).
- **Multi-host CF** — single docker host only. cf-deployment supports multi-VM-cluster modes; we don't attempt that.
- **Automated cert lifecycle** — the dockerd TLS triple is hand-rolled (see Section 2).
- **`verify-host` networking probe** — would catch the WSL2-Docker-Desktop case early. Future #20-style work.

Notes:
- The state directory is intentionally human-readable. `cat status.json | jq` should always be a sensible debug move.
