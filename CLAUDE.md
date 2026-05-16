# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`cf-docker-cpi` is a Spring Shell 4 CLI for setting up CloudFoundry (OSS) on x86_64/amd64 Linux hosts running Docker, via the Docker CPI. It can target the local Docker daemon or a remote host over SSH. The first (and currently only) feature is `docker verify`, which probes a Docker host and reports whether it is suitable for a Docker CPI deployment.

Stack: Spring Boot 4.0.6, Spring Shell 4.0.2, docker-java 3.5.1, Java 17.

## Build & run

```bash
./mvnw clean package                          # build fat jar to target/cf-docker-cpi-0.1.0-SNAPSHOT.jar
./mvnw test                                   # unit tests
./mvnw -Dtest=ClassName#method test           # single test

# Interactive shell (default, JLine-backed)
java -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar
# then at the prompt:  docker verify --host ssh://user@host

# One-shot / scripting mode
java -Dspring.shell.interactive.enabled=false \
  -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar \
  docker verify --host ssh://user@host
```

**Spring options must be `-D` system properties, NOT `--` args, in one-shot mode.** Spring Shell 4's `NonInteractiveShellRunner` does not strip Spring Boot's `--spring.X=Y` flags before parsing, so it treats the first `--` token as the command name and fails with `CommandNotFoundException`. Interactive mode does not have this problem. Command-level options (e.g. `--host`) go after the jar as usual.

## Architecture

### Command pipeline
`docker verify` flows: `DockerCommands.verify` → `DockerTargetResolver.resolve` (parses `--host` / `DOCKER_HOST` / default `unix:///var/run/docker.sock`; bare hostnames default to `ssh://`) → `VerificationService.verify` (which opens a `DockerSession` via `DockerClientFactory` and runs the check sequence) → render to text.

Check sequence in `VerificationService`: ping → version → info → host OS (must be Linux) → architecture (must be x86_64/amd64) → resources → CPI prereqs → API version (must be ≥ 1.41). Each step produces a `CheckResult` (PASS/FAIL/WARN/SKIP). The overall `VerificationReport.ok()` is false if any check FAILed.

### SSH transport (the big one)
**docker-java 3.5.1 has no `ssh://` transport** — its Apache HC5 transport supports only `tcp://`, `unix://`, `npipe://`. The workaround in `SshTunnel`:

1. Pick a free local TCP port via `new ServerSocket(0)`.
2. Spawn the system `ssh` binary: `ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ConnectTimeout=10 -L <localPort>:/var/run/docker.sock user@host`.
3. Poll `localhost:<port>` until connectable (or timeout / ssh exits).
4. Point docker-java at `tcp://localhost:<port>`.
5. Drain ssh stderr in a background thread so failure messages surface in the `CheckResult` detail.

Implications: the host needs `ssh` on `$PATH`; SSH auth uses the user's existing `~/.ssh/config`, agent, and `known_hosts` (we do not re-implement any of that); the remote socket path is configurable via `--remote-socket` (default `/var/run/docker.sock`). If you ever want a pure-Java SSH transport, swap the implementation behind `DockerClientFactory`; the rest of the code is transport-agnostic.

### Spring Shell 4 wiring (the second big one)
**Do not use `@EnableCommand` in this project.** That annotation registers a basic `SystemShellRunner` and, via `@ConditionalOnMissingBean(annotation = EnableCommand.class)`, *disables* `SpringShellAutoConfiguration` — which is what wires the JLine interactive runner. The result is a silent exit on launch (no usable runner picked up).

Instead, command classes are `@Component @CommandGroup(prefix = "...")` with `@Command`-annotated methods. `CommandRegistryAutoConfiguration` discovers them automatically. The main application class is a vanilla `@SpringBootApplication` with no scanning annotations.

Spring Shell 4 split JLine into its own module: both `spring-shell-starter` *and* `spring-shell-jline` are required for the interactive shell.

### Spring Shell 4 API quirks
- Annotation package is `org.springframework.shell.core.command.annotation` (note the `core`).
- `@Command` has `name` (`String[]`) and `value` (single `String` shorthand). There is no `command` field.
- `@Option` uses singular `longName` (String) and `shortName` (char), not the 3.x plural variants.
- docker-java `Info`: methods are `getCGroupDriver()` / `getCGroupVersion()` with capital `G`, not `getCgroup*`.

## Layout

- `com.dashaun.cfdockercpi.commands` — Spring Shell command classes.
- `com.dashaun.cfdockercpi.docker` — target resolution, SSH tunnel, docker-java client factory, verification checks and result types.
- `src/main/resources/application.properties` — silences Spring banner / startup info, sets logging levels, enables interactive mode by default.
