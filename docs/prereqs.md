<!-- .slide: data-background-color="#191e1e" -->

# Prereqs

### Three things you set up by hand

Before `setup step --name verify-docker` will get past its first check.

Notes:
**~5 min** — be honest about what's NOT in the tool. There are exactly three manual prereqs; everything else is automated. The dockerd-TLS recipe is the chunkiest of the three.

---

## 1 — A Linux host with Docker

- Bare metal, VM, or WSL2 with **native dockerd** (`apt install docker.io`), **not** Docker Desktop's WSL integration.
- Ubuntu 24.04 noble is the tested baseline. Anything with cgroup v2 (or v1 + working `securityfs`) should work; WSL2's kernel ships without `securityfs` so `cf push` doesn't reach the end on it — see Section 5.
- **16 GiB RAM** and **50 GiB free disk** under the docker root dir. cf-deployment is ~16 instance groups, each a stemcell container.
- `ssh` access from your laptop. Passwordless sudo for the user on the host.

```bash
ssh user@host 'sudo -n true && echo sudo OK'
ssh user@host 'docker info | grep -E "Server Version|Storage Driver|Cgroup"'
```

---

## 2 — dockerd reconfigured for TLS-on-TCP

bosh-docker-cpi 0.2.12's in-container CPI requires HTTPS to dockerd. It always renders a TLS block, even with `tcp://`, so plain TCP fails with `http: server gave HTTP response to HTTPS client`.

Drop a systemd unit override:

```bash
sudo tee /etc/systemd/system/docker.service.d/tcp-listen.conf >/dev/null <<'DROPIN'
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd \
  -H fd:// \
  -H tcp://0.0.0.0:2376 \
  --tlsverify \
  --tlscacert=/etc/docker/tls/ca.pem \
  --tlscert=/etc/docker/tls/server-cert.pem \
  --tlskey=/etc/docker/tls/server-key.pem \
  --containerd=/run/containerd/containerd.sock
DROPIN
sudo systemctl daemon-reload && sudo systemctl restart docker
```

---

## 2 (cont.) — the cert triple

A CA + server cert (SAN must include `10.245.0.1` — the bridge gateway the director container sees) + a matching client cert/key:

```bash
# CA
openssl genrsa -out ca-key.pem 4096
openssl req -new -x509 -days 3650 -key ca-key.pem -out ca.pem \
  -subj '/CN=cf-docker-cpi-ca'

# Server (SAN includes the bridge gateway)
openssl genrsa -out server-key.pem 4096
openssl req -new -key server-key.pem -out server.csr -subj '/CN=server'
cat > server-ext.cnf <<EOF
subjectAltName = IP:10.245.0.1,IP:127.0.0.1,DNS:localhost
extendedKeyUsage = serverAuth
EOF
openssl x509 -req -days 3650 -in server.csr -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -extfile server-ext.cnf

# Client (mirror process, extendedKeyUsage = clientAuth)
```

Server triple goes to `/etc/docker/tls/`, client triple to `~/.cf-docker-cpi-work/tls/`. `deploy-director` reads the client side via `--var-file` at deploy time.

---

## 3 — One Docker-29 daemon.json

`apt install docker.io` on Ubuntu noble installs Docker 29.x, which enables the **containerd snapshotter** by default. bosh-docker-cpi 0.2.12 loads stemcell images, but they aren't visible to its subsequent `docker create` call — the deploy then fails with `No such image: bosh.io/stemcells:img-...`.

One-line fix before `deploy-director`:

```bash
echo '{"storage-driver": "overlay2"}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker

# verify: dockerd log should show `containerd-snapshotter=false`
sudo journalctl -u docker --since '1 minute ago' | grep snapshotter
```

This setting is host-wide and benign for anything else you'd use the daemon for.

---

## What the tool DOES automate

Everything else. Specifically — these would normally be manual on a cf-deployment-on-docker run:

| Concern | Manual? | Where the tool handles it |
|---|---|---|
| `fs.inotify` sysctls for 16 stemcell containers | no | `host-setup` |
| Disabling noble's `nc.openbsd` AppArmor profile | no | `host-setup` (more on this in §5) |
| Pinned bosh + cf CLI versions | no | `install-tools` |
| `bosh-deployment` + `cf-deployment` checkouts | no | `fetch-manifests` |
| `cloud-config` overrides for the docker-CPI bridge | no | `update-cloud-config` |
| The `dns-wait` BOSH addon that holds diego-cell in pre-start | no | `update-runtime-config` (§5) |
| bosh-dns recursors so external DNS works | no | `update-runtime-config` (§5) |
| `/etc/hosts` for `cf api/login/uaa/cf-smoke` on the docker host | no (with `--write-hosts`) | `configure-cf-cli` |

Notes:
- The 3 manual prereqs are roughly a 15-minute setup if you've never done it before. After that, everything is `setup step --name X`.
