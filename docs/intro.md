<!-- .slide: data-background-color="#6db33f" -->

# Cloud Foundry on the Docker CPI

### From a fresh Docker host to `cf push`

DaShaun Carter | dashaun.com

Notes:
**Running this deck**
- `jwebserver -d "$(pwd)/docs" -p 8000` (JDK 18+) or `python3 -m http.server -d docs 8000`.
- Open `http://localhost:8000` in a presenter window. `S` for speaker view.

**Suggested timing (~30 min + Q&A)**

| Section | Time |
|---|---|
| Intro: why a tool for this | 4 min |
| Prereqs: what you set up by hand | 5 min |
| Pipeline: 13 idempotent steps | 5 min |
| Walkthrough: one real run, end to end | 8 min |
| What broke (and how it got fixed) | 6 min |
| Recap | 2 min |
| Q&A | open |

**Narrative**
- Cloud Foundry on Docker has been "possible" for years but the path is full of small landmines. This tool turns the path into 13 idempotent, resumable, ssh-driven steps.
- The interesting bits aren't the happy path — they're the four rabbit holes we fell into validating end-to-end on a real host. We'll spend time on those at the end.

---

## The shape of the thing

```text
┌──────────────────┐        ssh + sudo         ┌────────────────────────────┐
│  Your laptop     │  ──────────────────────▶  │   Linux host (noble)        │
│                  │                           │   dockerd + TLS on :2376    │
│  Spring Shell 4  │                           │                             │
│  Java 17         │                           │   ┌─────────────────────┐   │
│  ./cf-docker-cpi │  ssh -L (cf push only) ─▶ │   │ cf-docker-cpi-net   │   │
└──────────────────┘                           │   │  ┌───────────────┐  │   │
                                               │   │  │ BOSH director │  │   │
                                               │   │  │  + UAA + CH   │  │   │
                                               │   │  └───────────────┘  │   │
                                               │   │  ┌─ ~15 CF VMs ─┐   │   │
                                               │   │  │ api  diego   │   │   │
                                               │   │  │ uaa  nats    │   │   │
                                               │   │  │ ...          │   │   │
                                               │   │  └──────────────┘   │   │
                                               │   └─────────────────────┘   │
                                               └────────────────────────────┘
```

The tool drives everything over `ssh` and a single `bash -s` heredoc per step. The docker host doesn't have to know what year it is.

---

## Why a tool

cf-deployment + bosh-docker-cpi is the Cloud Foundry-on-Docker story. It's been around forever. So why a CLI?

- The **happy path is 13 steps**, each with prereqs the next one depends on. State leaks across them (BOSH state, docker images, runtime-configs, /etc/hosts). Without orchestration you re-run the wrong step at the wrong time.
- Several steps are **transitively fragile** — Ubuntu noble shipped a new AppArmor profile that broke `garden/post-start` halfway through validation. Docker 29 changed the default snapshotter and broke `create_stemcell`. A tool that's been validated end-to-end catches those for you.
- **Recoverability**: every step has a cheap check and a deep `--verify` check. If a deploy dies at the 28-minute mark, you re-run from where it died.

---

## What you'll see

```text
Section 2: Prereqs            — the 3 things you set up by hand
Section 3: Pipeline           — the 13 steps + state directory + resumability
Section 4: Walkthrough        — one real run, command by command
Section 5: What broke         — four genuine rabbit holes:
                                 - rep crashes on noble's systemd-resolved
                                 - garden times out on noble's nc.openbsd AppArmor
                                 - bosh-dns has no recursors after we take over resolv.conf
                                 - WSL2's kernel doesn't compile in securityfs
Section 6: Recap
```

---

## The repo

```bash
git clone https://github.com/dashaun/cloudfoundry-docker-cpi
cd cloudfoundry-docker-cpi
./mvnw clean package
java -jar target/cf-docker-cpi-0.1.0-SNAPSHOT.jar
```

- **All slides + speaker notes** are in this repo at `docs/` (you're reading them).
- **Per-step reference** lives in `docs/setup-pipeline.md`.
- **Architecture** notes in `docs/architecture.md`.

Notes:
- Prerequisites for the demo: a Linux host with Docker, ssh access (passwordless sudo) to it, JDK 17 on the laptop. The host needs ~16 GiB RAM and ~50 GiB free disk for the cf-deployment release set.
