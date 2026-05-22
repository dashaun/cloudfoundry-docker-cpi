# cf-docker-cpi

A Spring Shell 4 CLI for setting up CloudFoundry (OSS) on x86_64/amd64 Linux hosts running Docker, via the Docker CPI. Targets the local Docker daemon or a remote host over SSH.

Built with Spring Boot 4 + Spring Shell 4. Requires Java 17.

## Quickstart

Build:

```bash
./mvnw clean package
```

Interactive shell (recommended):

```bash
java -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar
# at the prompt:
docker verify --host ssh://user@host
```

One-shot / scripting mode:

```bash
java -Dspring.shell.interactive.enabled=false \
  -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  docker verify --host ssh://user@host
```

> In one-shot mode, Spring options must be `-D` system properties (before `-jar`), not `--` flags. Spring Shell 4's `NonInteractiveShellRunner` will otherwise treat the first `--` token as the command name.

`--host` accepts `ssh://user@host`, `tcp://host:2375`, `unix:///path`, or a bare hostname (defaults to `ssh://`). SSH auth uses your existing `~/.ssh/config`, agent, and `known_hosts` — `ssh` must be on `$PATH`.

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

So `cf push` is unreachable on stock WSL2. The only known path forward is to build a [custom WSL2 kernel](https://learn.microsoft.com/en-us/windows/wsl/wsl-config#kernel) from [microsoft/WSL2-Linux-Kernel](https://github.com/microsoft/WSL2-Linux-Kernel) with `CONFIG_SECURITYFS=y`, `CONFIG_SECURITY_APPARMOR=y`, and `CONFIG_SECURITY_NETWORK=y`, then point `.wslconfig` `kernel=` at the resulting `bzImage`. Tracked in issue #22; not supported by this CLI today.

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

## Roadmap

`docker verify` is shipped. Next milestone is end-to-end CF deployment via the Docker CPI, culminating in a `cf push` of a Spring Boot smoke app. Progress tracked in the meta issue (`epic` label) and per-step issues (`step` label) on [GitHub Issues](https://github.com/dashaun/cloudfoundry-docker-cpi/issues).

## Architecture

See [docs/architecture.md](docs/architecture.md) for the resumable-pipeline design, SSH-tunnel lifecycle, and state directory layout. Per-step reference in [docs/setup-pipeline.md](docs/setup-pipeline.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
