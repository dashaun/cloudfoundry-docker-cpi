<!-- .slide: data-background-color="#191e1e" -->

# Walkthrough

### One real run, end to end

`ssh://senshin` — Ubuntu noble, native dockerd 29.2.1, ~35 min wall-clock.

Notes:
**~8 min** — show actual command + output for the interesting bits. Skip past the 5 sub-second steps. Dwell on `deploy-director`, the runtime-config sha, the `cf push` HTTP 200.

---

## Build + status

```bash
$ ./mvnw clean package -q
$ java -Dspring.shell.interactive.enabled=false \
    -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
    setup status --host ssh://senshin

verify-docker:          UNRUN
host-setup:             UNRUN
install-tools:          UNRUN
fetch-manifests:        UNRUN
generate-director-vars: UNRUN
deploy-director:        UNRUN
login-director:         UNRUN
upload-stemcell:        UNRUN
update-cloud-config:    UNRUN
update-runtime-config:  UNRUN
deploy-cf:              UNRUN
configure-cf-cli:       UNRUN
smoke-push:             UNRUN
```

Empty state, fresh start.

---

## The five fast steps

```bash
$ for s in verify-docker host-setup install-tools \
           fetch-manifests generate-director-vars; do
    java -Dspring.shell.interactive.enabled=false \
      -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
      setup step --name $s --host ssh://senshin
  done
```

```text
verify-docker          RAN    7 checks passed
host-setup             RAN    inotify limits bumped (was: ...=128 -> now: ...=8192);
                              nc.openbsd AppArmor profile disabled
install-tools          RAN    bosh 7.10.5 + cf 8.18.3 installed
fetch-manifests        RAN    bosh-deployment @ 3ad6e9bc + cf-deployment @ 5c4fc5f5
generate-director-vars RAN    director_name=cf-docker-cpi internal_ip=10.245.0.11
```

Five steps in about thirty seconds.

---

## deploy-director — ~7 min

```bash
$ java ... setup step --name deploy-director --host ssh://senshin
[bootstrap] ruby: ruby 3.3.7 ...
[bootstrap] downloading bosh
bin/bosh: OK
[bootstrap] cloning bosh-deployment
[bootstrap] creating docker network cf-docker-cpi-net
[bootstrap] starting bosh create-env

Deployment manifest: '/home/dashaun/.cf-docker-cpi-work/bosh-deployment/bosh.yml'
Deployment state: 'director-state.json'
...
Started deploying
Stopping jobs on instance 'unknown/0'... Skipped [00:00:00]
Creating VM for instance 'bosh/0' from stemcell '...'... Finished [00:01:23]
...
Started installing job 'director'... Finished [00:02:45]
Succeeded
```

```text
Outcome: RAN
Detail:  director container c-0062cf74-2 up at 10.245.0.11
```

The next 4 steps (`login-director` → `upload-stemcell` → `update-cloud-config` → `update-runtime-config`) take ~2 min combined.

---

## update-runtime-config — the meaty one

```bash
[runtime-config] applying bosh-deployment/runtime-configs/dns.yml
                 (+recursors ops) as name=dns
[dns-wait] materialising release source under dns-wait-release/
[dns-wait] bosh create-release cf-docker-cpi-dns-wait/0.2.0
[dns-wait] bosh upload-release
Task 96 | Release has been created: cf-docker-cpi-dns-wait/0.2.0
[runtime-config] applying dns-wait-runtime-config.yml as name=dns-wait
[runtime-config] verifying applied configs on director
[dns-applied-sha]      72c5ddfa074f4070ad05e82a9295643a201ab9a34f85428755a66d2517cdf351
[dns-wait-applied-sha] c95625d33f01ea6aaae778cd727143e2bb44f60dd3bdd6a108810f58b256e777
```

```text
Outcome: RAN
Detail:  dns_sha=72c5ddfa dns_wait_sha=c95625d3
```

Two runtime-configs + a custom BOSH release built and uploaded on the fly. Section 5 explains why all three exist.

---

## deploy-cf — ~23 min

Live tail of the bosh task. 16 instance groups, each a stemcell container, each canary'd before being scaled.

```text
Task 36 | Updating instance database: database/...   (canary) (00:01:34)
Task 36 | Updating instance singleton-blobstore: ... (canary) (00:00:52)
Task 36 | Updating instance api: api/...             (canary) (00:01:47)
Task 36 | Updating instance scheduler: scheduler/... (canary) (00:01:12)
...
Task 36 | Updating instance diego-cell: diego-cell/...  (canary) (00:03:18)
Task 36 |   L executing pre-start: ...  ← our dns-wait pre-start runs here
Task 36 |   L starting jobs: ...
Task 36 |   L executing post-start: ...
...
```

```text
Outcome: RAN
Detail:  cf deployed (system_domain=bosh-lite.com)
```

`cf-creds.yml` lands locally. The director knows about the deployment.

---

## configure-cf-cli + smoke-push

```bash
$ java ... setup step --name configure-cf-cli --write-hosts --host ssh://senshin
[bootstrap] downloading cf-cli 8.18.3
[bootstrap] cf: cf version 8.18.3+...
[hosts] adding cf-docker-cpi block to /etc/hosts (sudo)
[cf] api https://api.bosh-lite.com --skip-ssl-validation
[cf] auth admin (CF_PASSWORD piped via env from local cf-creds.yml)
[cf] org system already exists
[cf] space dev already exists in system
[cf] target -o system -s dev
API endpoint:   https://api.bosh-lite.com
org:            system
space:          dev

$ java ... setup step --name smoke-push --host ssh://senshin
[fetch] https://start.spring.io/starter.zip?...&dependencies=web,actuator
[build] ./mvnw package -DskipTests
[build] cf-smoke-0.0.1-SNAPSHOT.jar
[scp]   cf-smoke-0.0.1-SNAPSHOT.jar -> senshin:~/.cf-docker-cpi-work/cf-smoke.jar
[cf] push cf-smoke
...   #0   running   2026-05-20T01:00:55Z   ...
[probe] GET https://cf-smoke.bosh-lite.com/actuator/health (up to 120s)
[probe] attempt 1: 200 OK — body: {"groups":["liveness","readiness"],"status":"UP"}
```

---

## Final status

```text
verify-docker:          PASS    7 checks passed
host-setup:             PASS    inotify limits OK; nc.openbsd disabled
install-tools:          PASS    bosh 7.10.5 + cf 8.18.3
fetch-manifests:        PASS    bosh-deployment @ 3ad6e9bc + cf-deployment @ 5c4fc5f5
generate-director-vars: PASS    director_name=cf-docker-cpi
deploy-director:        PASS    director container c-0062cf74-2 up at 10.245.0.11
login-director:         PASS    alias cf-docker-cpi -> https://10.245.0.11:25555
upload-stemcell:        PASS    bosh-warden-boshlite-ubuntu-noble@1.364
update-cloud-config:    PASS    file_sha=f0c82014 applied_sha=e019e4e7
update-runtime-config:  PASS    dns_sha=72c5ddfa dns_wait_sha=c95625d3
deploy-cf:              PASS    cf deployed (system_domain=bosh-lite.com)
configure-cf-cli:       PASS    cf targeted https://api.bosh-lite.com (org=system space=dev)
smoke-push:             PASS    cf-smoke up at https://cf-smoke.bosh-lite.com/actuator/health
```

Independent verification:

```bash
$ ssh senshin 'curl -sk -w "HTTP %{http_code} | " \
                 https://cf-smoke.bosh-lite.com/actuator/health'
HTTP 200 | {"groups":["liveness","readiness"],"status":"UP"}
```

Notes:
- That's the happy path. Now let's talk about the four rabbit holes that exist BECAUSE the happy path looks this clean.
