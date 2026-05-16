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

## Roadmap

`docker verify` is shipped. Next milestone is end-to-end CF deployment via the Docker CPI, culminating in a `cf push` of a Spring Boot smoke app. Progress tracked in the meta issue (`epic` label) and per-step issues (`step` label) on [GitHub Issues](https://github.com/dashaun/cloudfoundry-docker-cpi/issues).

## Architecture

See [docs/architecture.md](docs/architecture.md) for the resumable-pipeline design, SSH-tunnel lifecycle, and state directory layout. Per-step reference in [docs/setup-pipeline.md](docs/setup-pipeline.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
