#!/usr/bin/env bash
# `docker build` that also works in a Claude Code cloud session. Containers there reach the internet only through the
# session's proxy, which listens on the host's loopback and re-signs TLS with its own CA. So the build joins the host
# network, gets the proxy as https_proxy (a predefined build argument, which stays out of the image history) and the CA
# as the build secret `ca`. A Dockerfile that downloads anything trusts that secret when it is present.
# Elsewhere this is plain `docker build`.
set -euo pipefail

args=()
if [ "${CLAUDE_CODE_REMOTE:-false}" = true ] && [ -n "${HTTPS_PROXY:-}" ] && [ -f "${SSL_CERT_FILE:-}" ]; then
  args=(--network host --build-arg "https_proxy=$HTTPS_PROXY" --secret "id=ca,src=$SSL_CERT_FILE")
fi

# The guarded expansion keeps an empty array from tripping `set -u` in macOS's bash 3.2.
exec docker build ${args[@]+"${args[@]}"} "$@"
