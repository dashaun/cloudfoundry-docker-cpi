<!-- .slide: data-background-color="#191e1e" -->

# Recap

---

## What we covered

1. **Why a tool** — the cf-deployment-on-docker happy path is 13 steps deep with state that crosses them; fragility creeps in from the host distro, Docker version, and the BOSH addons.
2. **Three manual prereqs** — Linux + Docker, dockerd-TLS systemd drop-in, Docker-29 daemon.json. Everything else is `setup step`.
3. **Thirteen idempotent, resumable, ssh-driven steps** — per-host state directory, cheap + `--verify` checks, `bosh deploy`-style idempotency throughout.
4. **A real run** — ~35 min wall-clock from scratch on a noble host, ending in HTTP 200 on a Spring Boot app's `/actuator/health`.
5. **Four rabbit holes** — rep crash → dns-wait addon; garden post-start → nc.openbsd AppArmor disable; staging DNS → bosh-dns recursors ops; WSL2 → kernel `securityfs` wall.

---

## What's in the repo for you

```bash
git clone https://github.com/dashaun/cloudfoundry-docker-cpi
cd cloudfoundry-docker-cpi
./mvnw clean package
```

- **`docs/index.html`** — these slides + speaker notes (`jwebserver -d "$(pwd)/docs" -p 8000`).
- **`docs/setup-pipeline.md`** — per-step reference (inputs, outputs, cheap/deep checks, failure modes).
- **`docs/architecture.md`** — orchestrator + state directory + transport notes.
- **GitHub Issues** — every rabbit hole has an issue with the symptom, the wrong hypothesis, and the fix.

---

## If you only remember three things

1. **`bosh deploy` is idempotent — lean on it.** The tool's resumability is mostly BOSH's.
2. **Replacing OS plumbing is transitive.** Cutting systemd-resolved out of the path broke bosh-dns's recursors. Audit who else reads what you replace.
3. **Run end-to-end on a fresh host before you ship.** Three of the four rabbit holes only surface when state is empty and things race for the first time.

---

<!-- .slide: data-background-color="#6db33f" -->

# Thank you

@dashaun on most places · dashaun.com
