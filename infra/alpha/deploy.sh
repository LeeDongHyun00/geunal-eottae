#!/usr/bin/env bash
# Deploys the alpha server from the operator's machine (infra/alpha/README.md): copies infra/alpha to /opt/geunal,
# runs bootstrap.sh there, ships the API image of the last commit that changed server/api, and restarts the stack.
# The ACG opens SSH to the operator's address only (docs/parts/infra.md §2), which is why CI does not run this yet.
#
#   GEUNAL_ALPHA_SSH=root@<address> [GEUNAL_ALPHA_SSH_KEY=<key file>] [GEUNAL_API_HOST=<host name>] make deploy-alpha
set -euo pipefail

target=${GEUNAL_ALPHA_SSH:?set GEUNAL_ALPHA_SSH to root@<alpha server address>}
host=${GEUNAL_API_HOST:-}
case "$host" in
  *[!a-z0-9.-]*)
    echo "GEUNAL_API_HOST must be a lower-case host name, not '$host'" >&2
    exit 1
    ;;
esac

ssh_opts=(-o BatchMode=yes -o ConnectTimeout=10)
if [ -n "${GEUNAL_ALPHA_SSH_KEY:-}" ]; then
  ssh_opts+=(-i "$GEUNAL_ALPHA_SSH_KEY")
fi
# The commands are strings for the server's shell; the values in them are filled in here on purpose.
# shellcheck disable=SC2029
remote() { ssh "${ssh_opts[@]}" "$target" "$@"; }

cd "$(git rev-parse --show-toplevel)"

# An image is tagged with a commit and a tag never changes (infra.md §3), so the API's sources must be that commit's.
if [ -n "$(git status --porcelain -- server/api)" ]; then
  echo "server/api has uncommitted changes; commit them so the image tag names the code inside." >&2
  exit 1
fi
tag=$(git log -1 --format=%h --abbrev=12 -- server/api)

echo "Copying infra/alpha to $target:/opt/geunal" >&2
# COPYFILE_DISABLE and --no-xattrs keep macOS's tar from adding AppleDouble files and extended attributes.
COPYFILE_DISABLE=1 tar -C infra/alpha --no-xattrs -cf - compose.yml Caddyfile bootstrap.sh db-dump.sh \
  geunal-db-dump.service geunal-db-dump.timer |
  remote 'install -d -m 755 /opt/geunal && tar -C /opt/geunal --no-same-owner --warning=no-unknown-keyword -xf -'
remote /opt/geunal/bootstrap.sh

if remote docker image inspect "geunal/api:$tag" >/dev/null 2>&1; then
  echo "The server already has geunal/api:$tag" >&2
else
  make image-api
  arch=$(docker image inspect --format '{{.Architecture}}' geunal/api:local)
  if [ "$arch" != amd64 ]; then
    echo "geunal/api:local was built for $arch, but the alpha server runs amd64." >&2
    exit 1
  fi
  docker tag geunal/api:local "geunal/api:$tag"
  echo "Shipping geunal/api:$tag" >&2
  docker save "geunal/api:$tag" | gzip -1 | remote 'gunzip | docker load --quiet' >/dev/null
fi

remote "printf 'GEUNAL_API_TAG=%s\nGEUNAL_API_HOST=%s\n' '$tag' '$host' >/opt/geunal/.env"
if [ -n "$host" ]; then
  remote 'cd /opt/geunal && docker compose --profile https up --detach --wait --wait-timeout 300 --remove-orphans'
else
  # Without a host name nothing answers on 80 and 443; a Caddy left from an earlier deploy goes.
  remote 'cd /opt/geunal && docker compose up --detach --wait --wait-timeout 300 --remove-orphans &&
    docker compose --profile https rm --stop --force caddy'
fi

# Keep this tag and the one before it to roll back to; older API images only fill the 10 GB disk.
remote "docker images geunal/api --format '{{.Tag}}' | grep -vx '$tag' | tail -n +2 |
  xargs -r -I{} docker rmi 'geunal/api:{}' >/dev/null; docker image prune --force >/dev/null"

remote 'curl -fsS http://127.0.0.1:8080/actuator/health' >&2
echo >&2
if [ -n "$host" ]; then
  # Caddy asks Let's Encrypt for the certificate when it starts, so the first requests may come before it.
  curl -fsS --retry 20 --retry-delay 3 --retry-all-errors "https://$host/actuator/health" >&2
  echo >&2
fi
echo "Deployed geunal/api:$tag to $target${host:+ as https://$host}" >&2
