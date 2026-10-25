#!/bin/bash
# SessionStart hook: give every Claude Code cloud session the repo's toolchain before its first command by running
# scripts/dev-setup.sh (Erlang/OTP and Elixir, the Gradle Maven mirror, a running Docker daemon). It also runs on
# resume, because a resumed session can land on a restarted machine without its Docker daemon; finished steps are
# skipped, so a warm run takes a second or two. Local sessions are left alone: there `make setup` is run by hand.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
exec "$root/scripts/dev-setup.sh"
