# cf-docker-cpi

A Spring Shell 4 CLI that deploys Cloud Foundry (OSS) onto a single x86_64 Linux host running Docker, via the bosh-docker-cpi. Targets the local Docker daemon or a remote host over SSH and drives the whole BOSH lifecycle end-to-end — `verify-docker → … → cf push a Spring Boot app`.

Built with Spring Boot 4 + Spring Shell 4. Requires Java 17.

## What it does

Thirteen idempotent, resumable steps that go from a freshly-installed Docker host to a deployed Cloud Foundry serving HTTP 200 on `cf-smoke.bosh-lite.com/actuator/health`:

```
verify-docker          host meets the bosh-docker-cpi prereqs
host-setup             bump inotify sysctls, disable noble's nc.openbsd AppArmor profile
install-tools          fetch pinned bosh + cf CLIs
fetch-manifests        clone bosh-deployment + cf-deployment at pinned SHAs
generate-director-vars write director-vars.yml
deploy-director        bosh create-env the BOSH director (with UAA + CredHub)
login-director         alias the env, log in as admin
upload-stemcell        pinned ubuntu-noble stemcell
update-cloud-config    cf-deployment's bosh-lite cloud-config + docker-CPI overrides
update-runtime-config  bosh-dns addon + dns-recursors override + the dns-wait addon
deploy-cf              bosh deploy cf-deployment (the long one, 25-35 min)
configure-cf-cli       cf api + auth + create system/dev — running on the docker host
smoke-push             build a Spring Boot starter locally, scp + cf push, probe 200
```

Per-step reference (inputs, outputs, cheap/deep checks, failure modes) is in [docs/setup-pipeline.md](docs/setup-pipeline.md). Architectural notes (resumable orchestrator, SSH transport, state directory) are in [docs/architecture.md](docs/architecture.md). For a guided walk-through, open the presentation at [docs/index.html](docs/index.html) (any static server — `jwebserver -d "$(pwd)/docs" -p 8000`).

## Repo layout

Multi-module Maven build:

```
pom.xml         ← parent (packaging=pom)
cli/            ← Spring Shell CLI
broker/         ← (planned) Spring Cloud Open Service Broker app for the optional marketplace
```

## Quickstart

Build (all modules):

```bash
./mvnw clean package
```

One-shot mode (recommended for the pipeline):

```bash
java -Dspring.shell.interactive.enabled=false \
  -jar cli/target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  setup step --name verify-docker --host ssh://user@host
```

Then run each subsequent step the same way (`--name host-setup`, `--name install-tools`, …). `setup status --host ssh://user@host` shows where you are.

Interactive shell:

```bash
java -jar cli/target/cf-docker-cpi-0.1.0-SNAPSHOT.jar
# at the prompt:
setup status --host ssh://user@host
setup step --name verify-docker --host ssh://user@host
```

> In one-shot mode, Spring options must be `-D` system properties (before `-jar`), not `--` flags. Spring Shell 4's `NonInteractiveShellRunner` will otherwise treat the first `--` token as the command name.

`--host` accepts `ssh://user@host`, `tcp://host:2375`, `unix:///path`, or a bare hostname (defaults to `ssh://`). SSH auth uses your existing `~/.ssh/config`, agent, and `known_hosts` — `ssh` must be on `$PATH`.

## Prereqs the tool does NOT automate

Three things you set up by hand on the docker host before the pipeline can run end-to-end. Details in [docs/setup-pipeline.md §6](docs/setup-pipeline.md):

1. **`dockerd` reconfigured for TLS-on-TCP at `:2376`** — bosh-docker-cpi 0.2.12's in-container CPI requires HTTPS to dockerd. Drop a systemd unit override pointing dockerd at `tcp://0.0.0.0:2376 --tlsverify` and install a CA + server cert (with SAN `10.245.0.1, 127.0.0.1`) under `/etc/docker/tls/`.
2. **Matching CA + client cert/key** under `~/.cf-docker-cpi-work/tls/{ca,client-cert,client-key}.pem` on the docker host. `deploy-director` injects them via `--var-file`.
3. **Passwordless `sudo`** for the SSH user on the docker host. Used by `host-setup` (sysctls + AppArmor) and `configure-cf-cli --write-hosts`.

`docs/setup-pipeline.md §6` has the exact `openssl` recipe and the dockerd systemd drop-in.

## WSL2 docker host notes

### Known limitation: garden-runc needs `securityfs`

The full pipeline runs cleanly on WSL2 through `update-runtime-config` (10 of 13 steps) but **`deploy-cf` fails at the diego-cell canary** because garden-runc tries to mount `/sys/kernel/security` inside each container it creates. Microsoft's stock WSL2 kernel ships **without `CONFIG_SECURITYFS`**:

```bash
$ zcat /proc/config.gz | grep SECURITY
# CONFIG_SECURITYFS is not set
# CONFIG_SECURITY_NETWORK is not set
# CONFIG_SECURITY_APPARMOR is not set

$ ls /sys/kernel/security/lsm
ls: cannot access '/sys/kernel/security/lsm': No such file or directory
```

So `cf push` is unreachable on stock WSL2. The only known path forward is to build a [custom WSL2 kernel](https://learn.microsoft.com/en-us/windows/wsl/wsl-config#kernel) from [microsoft/WSL2-Linux-Kernel](https://github.com/microsoft/WSL2-Linux-Kernel) with `CONFIG_SECURITYFS=y`, `CONFIG_SECURITY_APPARMOR=y`, and `CONFIG_SECURITY_NETWORK=y`, then point `.wslconfig` `kernel=` at the resulting `bzImage`. Tracked in [#22](https://github.com/dashaun/cloudfoundry-docker-cpi/issues/22); not supported by this CLI today.

If you don't need a working CF deploy and just want to validate the early pipeline steps (verify-docker through update-runtime-config), WSL2 with native dockerd is fine.

### Docker Desktop is not supported

Use **native dockerd installed inside the WSL2 distro** (`sudo apt install docker.io` after disabling Docker Desktop's WSL2 integration). bosh-docker-cpi 0.2.12's CPI requires TLS-on-TCP to dockerd; Docker Desktop doesn't expose a TLS-on-TCP endpoint out of the box, and the manual dockerd recipe in `docs/setup-pipeline.md §6` only applies when dockerd is a systemd unit you control.

### Docker 29 needs `daemon.json` to disable the containerd snapshotter

`apt install docker.io` on Ubuntu noble lands Docker 29.x, which enables the containerd snapshotter by default. bosh-docker-cpi 0.2.12's `create_stemcell` loads stemcell images but they aren't visible to the CPI's subsequent `docker create` call — the deploy then fails on `No such image: bosh.io/stemcells:img-...`. Fix before running `deploy-director`:

```bash
echo '{"storage-driver": "overlay2"}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
# verify: `docker info` should show `Storage Driver: overlay2` and dockerd logs
# should show `containerd-snapshotter=false`.
```

### Mirrored networking — only with Docker Desktop integration

Prior versions of this doc said WSL2 needs `.wslconfig` `networkingMode=mirrored` to make the `cf-docker-cpi-net` bridge routable from the WSL shell. That's true **only when Docker Desktop is in the picture** — Docker Desktop's helper VM hides the bridge. With native dockerd inside the distro (the supported setup), the bridge is created in the WSL distro's own network namespace and is locally routable; no `.wslconfig` change required. If you do happen to be debugging a Docker-Desktop-era setup:

```ini
[wsl2]
networkingMode=mirrored
```

then `wsl --shutdown` from PowerShell.

If you don't need a working CF deploy and just want to validate the early pipeline steps (verify-docker through update-runtime-config), WSL2 with native dockerd is fine.

### Docker Desktop is not supported

Use **native dockerd installed inside the WSL2 distro** (`sudo apt install docker.io` after disabling Docker Desktop's WSL2 integration). bosh-docker-cpi 0.2.12's CPI requires TLS-on-TCP to dockerd; Docker Desktop doesn't expose a TLS-on-TCP endpoint out of the box, and the manual dockerd recipe in `docs/setup-pipeline.md §6` only applies when dockerd is a systemd unit you control.

### Docker 29 needs `daemon.json` to disable the containerd snapshotter

`apt install docker.io` on Ubuntu noble lands Docker 29.x, which enables the containerd snapshotter by default. bosh-docker-cpi 0.2.12's `create_stemcell` loads stemcell images but they aren't visible to the CPI's subsequent `docker create` call — the deploy then fails on `No such image: bosh.io/stemcells:img-...`. Fix before running `deploy-director`:

```bash
echo '{"storage-driver": "overlay2"}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
# verify: `docker info` should show `Storage Driver: overlay2` and dockerd logs
# should show `containerd-snapshotter=false`.
```

### Mirrored networking — only with Docker Desktop integration

Prior versions of this doc said WSL2 needs `.wslconfig` `networkingMode=mirrored` to make the `cf-docker-cpi-net` bridge routable from the WSL shell. That's true **only when Docker Desktop is in the picture** — Docker Desktop's helper VM hides the bridge. With native dockerd inside the distro (the supported setup), the bridge is created in the WSL distro's own network namespace and is locally routable; no `.wslconfig` change required. If you do happen to be debugging a Docker-Desktop-era setup:

```ini
[wsl2]
networkingMode=mirrored
```

then `wsl --shutdown` from PowerShell.

## Status

Validated end-to-end on a noble bare-metal docker host (issue [#1](https://github.com/dashaun/cloudfoundry-docker-cpi/issues/1) closed). The full pipeline takes ~35 min wall-clock from scratch; `cf push` of a Spring Boot starter returns HTTP 200 on `/actuator/health`. Track open work and known limits via [GitHub Issues](https://github.com/dashaun/cloudfoundry-docker-cpi/issues).

## License

Apache-2.0. See [LICENSE](LICENSE).
