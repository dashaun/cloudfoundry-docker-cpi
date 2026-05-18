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

When the docker host is a WSL2 distro on Windows 11, the cf-deployment bridge (`10.245.0.0/24`) lives inside Docker Desktop's helper VM and is **not** routable from the WSL2 shell by default. `deploy-director` will fail at:

```
Post "https://mbus:<redacted>@10.245.0.11:6868/agent": dial tcp 10.245.0.11:6868: i/o timeout
```

Fix once, on the Windows side, by enabling [mirrored networking](https://learn.microsoft.com/en-us/windows/wsl/networking#mirrored-mode-networking):

1. Edit (or create) `C:\Users\<you>\.wslconfig`:
   ```ini
   [wsl2]
   networkingMode=mirrored
   ```
2. From PowerShell (admin): `wsl --shutdown`
3. Re-open the WSL distro / re-SSH to the host.

Verify on the docker host:

```bash
ip route show | grep -E '10\.245|cf-docker'   # should print a route
nc -w3 -zv 10.245.0.11 6868                   # should connect once the director is up
```

Requires Windows 11 22H2+ and a recent Docker Desktop. If you can't enable mirrored networking (older Windows, group-policy restrictions), the alternative is to run `bosh create-env` from a sidecar container colocated on `cf-docker-cpi-net` — not currently supported by this CLI.

## Roadmap

`docker verify` is shipped. Next milestone is end-to-end CF deployment via the Docker CPI, culminating in a `cf push` of a Spring Boot smoke app. Progress tracked in the meta issue (`epic` label) and per-step issues (`step` label) on [GitHub Issues](https://github.com/dashaun/cloudfoundry-docker-cpi/issues).

## Architecture

See [docs/architecture.md](docs/architecture.md) for the resumable-pipeline design, SSH-tunnel lifecycle, and state directory layout. Per-step reference in [docs/setup-pipeline.md](docs/setup-pipeline.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
