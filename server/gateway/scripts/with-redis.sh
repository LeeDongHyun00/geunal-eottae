#!/usr/bin/env bash
# Run a command with REDIS_URL set: as given (CI's Redis service), or else pointing at a throwaway Redis container on
# a free loopback port that goes away with the command. The tests wait for Redis to answer, so this does not.
set -euo pipefail

if [ -n "${REDIS_URL:-}" ]; then
  exec "$@"
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
"$repo_root/scripts/ensure-docker.sh"

# The image infra/docker-compose.yml runs.
redis="$(docker run --detach --rm --publish 127.0.0.1::6379 redis:7.4.11)"
trap 'docker rm --force "$redis" >/dev/null' EXIT
port="$(docker port "$redis" 6379/tcp | head -n 1)"

REDIS_URL="redis://127.0.0.1:${port##*:}" "$@"
