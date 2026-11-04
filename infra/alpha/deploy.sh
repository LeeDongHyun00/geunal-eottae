#!/usr/bin/env bash
# Deploys the alpha server from the operator's machine (infra/alpha/README.md): copies infra/alpha to /opt/geunal,
# runs bootstrap.sh there, ships the API and gateway images of the last commits that changed server/api and
# server/gateway, and restarts the stack. The ACG opens SSH to the operator's address only (docs/parts/infra.md §2),
# which is why CI does not run this yet.
#
#   GEUNAL_ALPHA_SSH=root@<address> [GEUNAL_ALPHA_SSH_KEY=<key file>] \
#     [GEUNAL_API_HOST=<host name> GEUNAL_RT_HOST=<host name>] make deploy-alpha
set -euo pipefail

target=${GEUNAL_ALPHA_SSH:?set GEUNAL_ALPHA_SSH to root@<alpha server address>}
host=${GEUNAL_API_HOST:-}
rt_host=${GEUNAL_RT_HOST:-}
for name in "$host" "$rt_host"; do
  case "$name" in
    *[!a-z0-9.-]*)
      echo "GEUNAL_API_HOST and GEUNAL_RT_HOST must be lower-case host names, not '$name'" >&2
      exit 1
      ;;
  esac
done
# Caddy serves both names or neither, and the gateway is reached only through Caddy.
if [ "${host:+set}" != "${rt_host:+set}" ]; then
  echo "Set GEUNAL_API_HOST and GEUNAL_RT_HOST together (e.g. api.geunaleottae.com and rt.geunaleottae.com)." >&2
  exit 1
fi

ssh_opts=(-o BatchMode=yes -o ConnectTimeout=10)
if [ -n "${GEUNAL_ALPHA_SSH_KEY:-}" ]; then
  ssh_opts+=(-i "$GEUNAL_ALPHA_SSH_KEY")
fi
# The commands are strings for the server's shell; the values in them are filled in here on purpose.
# shellcheck disable=SC2029
remote() { ssh "${ssh_opts[@]}" "$target" "$@"; }

cd "$(git rev-parse --show-toplevel)"

# An image is tagged with a commit and a tag never changes (infra.md §3), so its sources must be that commit's.
image_tag() {
  if [ -n "$(git status --porcelain -- "$1")" ]; then
    echo "$1 has uncommitted changes; commit them so the image tag names the code inside." >&2
    exit 1
  fi
  git log -1 --format=%h --abbrev=12 -- "$1"
}
api_tag=$(image_tag server/api)
gateway_tag=$(image_tag server/gateway)

# The network's range, which the gateway trusts for X-Forwarded-For (compose.yml).
subnet=$(awk '$1 == "x-subnet:" { print $3 }' infra/alpha/compose.yml)
: "${subnet:?infra/alpha/compose.yml has no x-subnet line}"

echo "Copying infra/alpha to $target:/opt/geunal" >&2
# COPYFILE_DISABLE and --no-xattrs keep macOS's tar from adding AppleDouble files and extended attributes.
COPYFILE_DISABLE=1 tar -C infra/alpha --no-xattrs -cf - compose.yml Caddyfile bootstrap.sh db-dump.sh \
  fetch-secrets.py geunal-db-dump.service geunal-db-dump.timer |
  remote 'install -d -m 755 /opt/geunal && tar -C /opt/geunal --no-same-owner --warning=no-unknown-keyword -xf -'
remote /opt/geunal/bootstrap.sh

# Builds geunal/<name>:local and loads it on the server as geunal/<name>:<tag>, unless the server has that tag. There
# is no registry yet.
ship() {
  local name=$1 tag=$2 arch
  if remote docker image inspect "geunal/$name:$tag" >/dev/null 2>&1; then
    echo "The server already has geunal/$name:$tag" >&2
    return
  fi
  make "image-$name"
  arch=$(docker image inspect --format '{{.Architecture}}' "geunal/$name:local")
  if [ "$arch" != amd64 ]; then
    echo "geunal/$name:local was built for $arch, but the alpha server runs amd64." >&2
    exit 1
  fi
  docker tag "geunal/$name:local" "geunal/$name:$tag"
  echo "Shipping geunal/$name:$tag" >&2
  docker save "geunal/$name:$tag" | gzip -1 | remote 'gunzip | docker load --quiet' >/dev/null
}
ship api "$api_tag"
# Without host names the gateway does not start (compose.yml), so its image would only take disk.
if [ -n "$host" ]; then
  ship gateway "$gateway_tag"
fi

# Caddy reads its Caddyfile only when it starts, and tar above replaced the file instead of writing into it, so a
# running Caddy keeps the old one. The file's hash goes into a label (compose.yml): a changed Caddyfile recreates the
# container, an unchanged one leaves it running.
caddyfile=$(git hash-object infra/alpha/Caddyfile)
remote "printf 'GEUNAL_API_TAG=%s\nGEUNAL_GATEWAY_TAG=%s\nGEUNAL_API_HOST=%s\nGEUNAL_RT_HOST=%s\nGEUNAL_CADDYFILE=%s\n' \
  '$api_tag' '$gateway_tag' '$host' '$rt_host' '$caddyfile' >/opt/geunal/.env"

# Docker cannot change the range of an existing network. A network made before compose.yml fixed it, or with another
# range, goes down with the stack once so that `up` makes it again; the volumes stay, and the API is away until it
# has started again.
current=$(remote "docker network inspect geunal_default --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}' \
  2>/dev/null || true")
if [ -n "$current" ] && [ "$current" != "$subnet" ]; then
  echo "Making the compose network again with $subnet ($current before); the stack stops until it is back up" >&2
  remote 'cd /opt/geunal && docker compose --profile https down'
fi

if [ -n "$host" ]; then
  remote 'cd /opt/geunal && docker compose --profile https up --detach --wait --wait-timeout 300 --remove-orphans'
else
  # Without host names nothing answers on 80 and 443; a Caddy or gateway left from an earlier deploy goes.
  remote 'cd /opt/geunal && docker compose up --detach --wait --wait-timeout 300 --remove-orphans &&
    docker compose --profile https rm --stop --force caddy gateway'
fi

# Keep each image's current tag and the one before it to roll back to; older images only fill the 10 GB disk.
for image in "api $api_tag" "gateway $gateway_tag"; do
  read -r name tag <<<"$image"
  remote "docker images geunal/$name --format '{{.Tag}}' | grep -vx '$tag' | tail -n +2 |
    xargs -r -I{} docker rmi 'geunal/$name:{}' >/dev/null"
done
remote 'docker image prune --force >/dev/null'

remote 'curl -fsS http://127.0.0.1:8080/actuator/health' >&2
echo >&2
if [ -n "$host" ]; then
  # Caddy asks Let's Encrypt for the certificates when it starts, so the first requests may come before them.
  curl -fsS --retry 20 --retry-delay 3 --retry-all-errors "https://$host/actuator/health" >&2
  echo >&2
  # 200 only while the gateway's Redis connections are up (docs/parts/gateway.md).
  curl -fsS --retry 20 --retry-delay 3 --retry-all-errors "https://$rt_host/healthz" >&2
  echo >&2
  echo "Deployed geunal/api:$api_tag and geunal/gateway:$gateway_tag to $target as https://$host and" \
    "wss://$rt_host" >&2
else
  echo "Deployed geunal/api:$api_tag to $target" >&2
fi
