# Optional marketplace services

A Spring Cloud Open Service Broker app that lives alongside this CLI. It is **not** part of the default 13-step pipeline — you opt in after a CF deploy is up. When enabled, four plans show up in `cf marketplace`:

| Plan name          | Backing image          | Notes                                      |
| ------------------ | ---------------------- | ------------------------------------------ |
| `postgres-single`  | `postgres:16`          | One container per service instance         |
| `redis-single`     | `redis:7`              | `--requirepass <pw>` on the command line   |
| `rabbitmq-single`  | `rabbitmq:3-management`| Adds the 15672 management UI as `managementUri` in the binding |
| `minio-single`     | `minio/minio:latest`   | S3-compatible; binding has AWS-shaped keys |

Each provisioned instance is one docker container on `cf-docker-cpi-net` (same bridge as the BOSH director + CF VMs). Per-instance state lives on the container's labels — the broker itself is `cf push`'d and effectively stateless.

> **Status (see [#29](https://github.com/dashaun/cloudfoundry-docker-cpi/issues/29)):** `broker deploy` runs through cleanly except the final `cf create-service-broker` step. cf-deployment v56.4.0's bosh-dns doesn't serve `*.<system_domain>`, so cloud_controller can't resolve `cf-docker-cpi-broker.<system_domain>`. Fix is a small bosh-dns aliases entry — tracked in #29 — not yet shipped.

## Architecture

```
┌─────────────┐                                         ┌─────────────────────────────────────────────┐
│  laptop     │  ssh / scp / cf push                    │  docker host                                  │
│             │  ──────────────────────────────────▶    │                                               │
│  broker     │                                         │  cf-docker-cpi-net (10.245.0.0/24)            │
│  deploy     │                                         │  ├─ director (10.245.0.11)                    │
│  service    │                                         │  ├─ cf VMs   (haproxy=10.245.0.34, api=…7)    │
│  add        │                                         │  │     └─ diego-cell/0                        │
└─────────────┘                                         │  │           └─ cf-docker-cpi-broker (CF app) │
                                                        │  │                  │                         │
                                                        │  │   ASG: TCP 10.245.0.1:2376 (egress)        │
                                                        │  │   env: BROKER_USERNAME, BROKER_PASSWORD,   │
                                                        │  │        DOCKER_HOST, DOCKER_TLS_*_B64       │
                                                        │  │                  │                         │
                                                        │  │                  ▼                         │
                                                        │  └─ dockerd on host (tcp://10.245.0.1:2376)   │
                                                        │     spawns: cf-svc-postgres-<uuid>, …          │
                                                        └─────────────────────────────────────────────┘
```

- The broker reaches dockerd over the same TLS-on-TCP listener `deploy-director` already requires (`/etc/docker/tls/` + the systemd dropin documented in [setup-pipeline.md §6](setup-pipeline.md)). It uses the matching client triple in `~/.cf-docker-cpi-work/tls/`, base64-encoded into CF env vars at push time.
- `cf create-service postgres-single mydb` → broker → `docker run postgres:16 --network=cf-docker-cpi-net --name cf-svc-postgres-…`.
- `cf bind-service myapp mydb` → broker → reads the container's IP via `docker inspect` + the admin credentials from the container labels → returns those in `VCAP_SERVICES.postgres-single[0].credentials`.

### Trade-offs the broker is opinionated about

- **One container per service instance** in v1. No multi-instance / HA plans.
- **All bindings to a given instance return the same admin credentials.** Per-binding role provisioning (mint a postgres role per binding, drop it on unbind) is a planned follow-up.
- **Service container data is ephemeral.** `docker rm -f` on deprovision wipes the volume. Acceptable for dev/test; persistent volumes are a follow-up.
- **Admin password stored in container labels.** dockerd is TLS-protected and only the broker has the client cert; an extra encryption hop wouldn't add meaningful security.

## How to opt in

```bash
# After the 13-step pipeline + smoke-push are green (or at least configure-cf-cli):

./mvnw -pl broker package -DskipTests                          # build the broker jar

java -Dspring.shell.interactive.enabled=false \
  -jar cli/target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  broker deploy --host ssh://<docker-host>

# then per-plan, repeat as you like:
java -Dspring.shell.interactive.enabled=false \
  -jar cli/target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  service add --name postgres --host ssh://<docker-host>

# inspect current state any time:
service list --host ssh://<docker-host>
```

### `broker deploy` does, in order:

1. Locates the broker jar (`broker/target/cf-docker-cpi-broker-*.jar` by default; `--broker-jar <path>` overrides).
2. `scp` to `~/.cf-docker-cpi-work/cf-docker-cpi-broker.jar` on the docker host.
3. Generates a fresh `BROKER_PASSWORD` (UUID) and a CF manifest with all the env vars (`BROKER_USERNAME`, `BROKER_PASSWORD`, `DOCKER_HOST=tcp://10.245.0.1:2376`, `DOCKER_NETWORK=cf-docker-cpi-net`, `DOCKER_TLS_*_B64` sourced from `~/.cf-docker-cpi-work/tls/`).
4. Creates / updates an ASG (`cf-docker-cpi-broker-egress`) opening TCP egress to `10.245.0.1:2376`, binds to `system/dev`.
5. `cf push` + `cf restart` (so the ASG takes effect).
6. `cf create-service-broker cf-docker-cpi … --space-scoped` (scoped to `system/dev`).
7. Persists `broker registered as cf-docker-cpi @ <url>` under `status.json` `services._broker`.

Idempotent: re-running detects the existing app/ASG/broker and `update`s rather than recreating.

### `service add --name <plan>` does:

1. Pre-check: refuses to run unless the `_broker` entry is `PASS`.
2. `cf enable-service-access <plan>-single -p single -o system`.
3. Persists under `status.json` `services.<plan>-single`.

That's all — the broker app does the actual provisioning when `cf create-service` fires.

### `broker remove` / `service remove`

Defensive teardown. `broker remove` calls `cf delete-service-broker`, `cf delete -r` the broker app, `cf unbind-security-group` + `cf delete-security-group` — each command with `|| true` so partial cleanup still helps when state is weird. `service remove --name <plan>` calls `cf disable-service-access`; the broker app stays deployed.

## Known limits (as of this writing)

- **[#29](https://github.com/dashaun/cloudfoundry-docker-cpi/issues/29) — bosh-dns has no `*.<system_domain>` alias.** `cf create-service-broker` can't reach `cf-docker-cpi-broker.<system_domain>`; cloud_controller resolves it as NXDOMAIN. Until that ships, the final step of `broker deploy` fails; everything before it (push, ASG, broker reachable via the haproxy URL when DNS works) is verified.
- The broker requires the dockerd TLS prereq + cell network reachability — neither auto-detected by `broker deploy`. Failures in those layers surface as broker app startup errors.

## Hacking on the broker

```bash
# Build + run locally without cf push:
./mvnw -pl broker package -DskipTests
BROKER_USERNAME=u BROKER_PASSWORD=p java -jar broker/target/cf-docker-cpi-broker-0.1.0-SNAPSHOT.jar
curl -u u:p http://localhost:8080/v2/catalog | jq '.services[].name'
```

With no `DOCKER_HOST` env var, the broker falls back to the stub services from Phase 1 — `/v2/catalog` still returns the four offerings but `cf create-service` against this broker would only log the request and return success without provisioning. Useful for OSB-contract / UI work without dockerd at hand.

To exercise the real provisioning path locally, set `DOCKER_HOST=tcp://…:2376` + the three `DOCKER_TLS_*_B64` env vars and ensure your local user can reach the daemon at that host.

## Tests

```
broker/src/test/java/com/dashaun/cfdockercpi/broker/
├── CatalogEndpointTest.java         catalog bean shape + deterministic offering UUIDs
└── plans/PlanRegistryTest.java      every offering has a plan; per-plan credential URI shapes
```

44/44 in `./mvnw test` (30 cli + 14 broker). The HTTP-level catalog test isn't shipped — Spring Cloud OSB 4.5.0 was built against Spring Boot 3 and the SB4 test-context plumbing fights with it; the running app demonstrably serves the endpoint (manual curl during validation).
