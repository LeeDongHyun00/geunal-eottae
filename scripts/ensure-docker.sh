#!/usr/bin/env bash
# Make sure a Docker daemon answers before a target needs one. Claude Code cloud sessions start without a daemon and
# lose it whenever the machine restarts, so start it there; anywhere else only check and explain.
set -euo pipefail

ready() { docker info >/dev/null 2>&1; }

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is not installed; install Docker and retry." >&2
  exit 1
fi

if ready; then
  exit 0
fi

if [ "${CLAUDE_CODE_REMOTE:-false}" != true ] || ! command -v dockerd >/dev/null 2>&1; then
  echo "The Docker daemon is not running; start Docker and retry." >&2
  exit 1
fi

log=/var/log/geunal-dockerd.log
echo "Starting dockerd (log: $log)" >&2
# Docker Hub images come through Google's mirror: the session's shared egress address runs into Docker Hub's
# anonymous pull limit (429). The subshell detaches dockerd so it outlives this script and the shell that ran it.
(nohup dockerd --registry-mirror=https://mirror.gcr.io >"$log" 2>&1 &)

for _ in $(seq 60); do
  if ready; then
    exit 0
  fi
  sleep 1
done

echo "dockerd did not answer within 60 s; see $log" >&2
exit 1
