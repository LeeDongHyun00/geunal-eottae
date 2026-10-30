#!/usr/bin/env bash
# Prepares the alpha server for infra/alpha/compose.yml (docs/parts/infra.md §2). deploy.sh copies infra/alpha to
# /opt/geunal and runs this there as root before every deploy, so each step looks before it changes anything.
set -euo pipefail

if [ "$(id -u)" -ne 0 ] || [ ! -f /opt/geunal/compose.yml ]; then
  echo "Run this as root on the alpha server from /opt/geunal; infra/alpha/deploy.sh does." >&2
  exit 1
fi

# Swap: the Micro has 1 GB of memory and nothing to fall back on, while the JVM, PostgreSQL and Docker together pass
# that at peaks (infra.md §2 sizes it at 2 GB). A low swappiness keeps it for those peaks.
if ! swapon --show=NAME --noheadings | grep -qx /swapfile; then
  if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
echo 'vm.swappiness=10' >/etc/sysctl.d/90-geunal.conf
sysctl --quiet --load=/etc/sysctl.d/90-geunal.conf

# Services a VM has no use for. fwupd alone held 230 MB, a quarter of the memory, and rpcbind listened on every
# address (the ACG keeps it off the internet, but nothing here needs it). Masking stops D-Bus and sockets from
# starting them again.
for unit in fwupd.service fwupd-refresh.timer ModemManager.service upower.service rpcbind.socket rpcbind.service; do
  if [ "$(systemctl is-enabled "$unit" 2>/dev/null || true)" != masked ]; then
    systemctl disable --now "$unit" >/dev/null 2>&1 || true
    systemctl mask "$unit" >/dev/null 2>&1
  fi
done

# Ubuntu's own Docker packages, so their security fixes arrive with the rest of the system through unattended-upgrades.
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q docker.io docker-compose-v2 >/dev/null
fi
# Installed packages' downloads (about 200 MB after Docker) have no further use on a 10 GB disk.
apt-get clean
# Rotated, compressed container logs: json-file, the default, grows without bound on a 10 GB disk.
daemon_json='{"log-driver": "local", "log-opts": {"max-size": "10m", "max-file": "3"}}'
if [ "$(cat /etc/docker/daemon.json 2>/dev/null || true)" != "$daemon_json" ]; then
  echo "$daemon_json" >/etc/docker/daemon.json
  systemctl restart docker
fi
systemctl enable --now docker >/dev/null 2>&1

# Secrets are made here, once, so they never leave the server (CLAUDE.md "비밀값과 로그"). The files are root-only. A
# line is only added when its name is missing, so lines put in by hand, such as the M1 provider keys, stay.
install -d -m 700 /etc/geunal
umask 077
if ! grep -q '^POSTGRES_PASSWORD=' /etc/geunal/postgres.env 2>/dev/null; then
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" >>/etc/geunal/postgres.env
fi
touch /etc/geunal/api.env
if ! grep -q '^SPRING_DATASOURCE_PASSWORD=' /etc/geunal/api.env; then
  echo "SPRING_DATASOURCE_PASSWORD=$(sed -n 's/^POSTGRES_PASSWORD=//p' /etc/geunal/postgres.env)" >>/etc/geunal/api.env
fi
if ! grep -q '^GEUNAL_AUTH_JWT_KEYS=' /etc/geunal/api.env; then
  # The access JWT's ES256 key as the JWK Set of api-server.md §4. openssl writes it as SEC1 DER, whose layout for
  # P-256 is fixed: the private scalar in bytes 7 to 38 and the uncompressed point 04||x||y at the end.
  keys=$(openssl ecparam -name prime256v1 -genkey -noout | openssl ec -outform DER 2>/dev/null | python3 -I -c '
import base64, json, sys
der = sys.stdin.buffer.read()
assert len(der) == 121 and der[:7] == bytes.fromhex("30770201010420") and der[-65] == 4, "unexpected key encoding"
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
key = {"kty": "EC", "crv": "P-256", "use": "sig", "alg": "ES256", "d": b64(der[7:39]), "x": b64(der[-64:-32]), "y": b64(der[-32:])}
print(json.dumps({"keys": [key]}, separators=(",", ":")))
')
  echo "GEUNAL_AUTH_JWT_KEYS='$keys'" >>/etc/geunal/api.env
fi
umask 022

# The daily database dump (db-dump.sh).
install -m 644 /opt/geunal/geunal-db-dump.service /opt/geunal/geunal-db-dump.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now geunal-db-dump.timer >/dev/null 2>&1
