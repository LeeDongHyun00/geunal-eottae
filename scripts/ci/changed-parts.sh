#!/usr/bin/env bash
# Print "<part>=true|false" for each part CI knows about, in the format $GITHUB_OUTPUT expects.
# A pull request checks the parts its changes touch: its merge commit is compared with the first parent, the base
# branch tip. Any other event (a push to main, a manual run) checks every part, so main is always fully verified.
# Locally, `scripts/ci/changed-parts.sh <base> [<head>]` shows what a PR from <head> into <base> would run.
set -euo pipefail

parts="core api gateway e2e ios android"

# Files every part's CI runs through.
shared='^(\.github/workflows/ci\.yml|scripts/ensure-docker\.sh|Makefile)$|^scripts/ci/'

rule() {
  case "$1" in
    core) echo '^core/|^\.github/workflows/core\.yml$' ;;
    api) echo '^server/api/|^core/vectors/|^\.github/workflows/api\.yml$' ;;
    gateway) echo '^server/gateway/|^\.tool-versions$|^\.github/workflows/gateway\.yml$' ;;
    e2e) echo '^core/|^server/|^infra/|^\.tool-versions$|^\.github/workflows/e2e\.yml$' ;;
    ios) echo '^apps/ios/|^core/|^\.github/workflows/ios\.yml$' ;;
    android) echo '^apps/android/|^core/|^\.github/workflows/android\.yml$' ;;
  esac
}

all() { for part in $parts; do echo "$part=true"; done; }

if [ $# -gt 0 ]; then
  changed="$(git diff --name-only "$1...${2:-HEAD}")"
elif [ "${GITHUB_EVENT_NAME:-}" = pull_request ]; then
  changed="$(git diff --name-only HEAD^1 HEAD)"
else
  all
  exit 0
fi

if grep -qE "$shared" <<<"$changed"; then
  all
  exit 0
fi

for part in $parts; do
  if grep -qE "$(rule "$part")" <<<"$changed"; then
    echo "$part=true"
  else
    echo "$part=false"
  fi
done
