#!/usr/bin/env bash
# Post the `gate` commit status that main's ruleset requires next to `ci-ok` (CLAUDE.md, "여러 세션이 함께 일할 때").
# Pull requests merge by auto-merge, so this status is what stops them:
# - while an open issue labelled `hold` pauses the project, unless the pull request is labelled `hold-exempt`;
# - on a pull request that changes docs/ until it carries `user-approved`, because the user confirms every document
#   and cannot approve a pull request that a session opened under the user's own account.
# A commit status rather than a job result, so that opening or closing a hold issue flips every open pull request at
# once without rerunning CI.
#
# Usage: scripts/gate.sh [<pr-number>]   (no argument: every open pull request)
# Needs GH_TOKEN and GITHUB_REPOSITORY. GATE_PUSHED=1 marks a new push: a pull request that changes docs/ then loses
# `user-approved`, because the user approved the commits they saw, not the ones pushed after.
set -euo pipefail

repo="${GITHUB_REPOSITORY:?set GITHUB_REPOSITORY to owner/name}"
link=()
if [ -n "${GITHUB_RUN_ID:-}" ]; then
  link=(-f "target_url=${GITHUB_SERVER_URL:-https://github.com}/$repo/actions/runs/$GITHUB_RUN_ID")
fi

# Up to three open hold issues as "#12, #34". The issues list also returns pull requests, which carry pull_request.
holds() {
  gh api "repos/$repo/issues?state=open&labels=hold&per_page=100" \
    --jq '[.[] | select(.pull_request == null) | "#\(.number)"] | .[:3] | join(", ")'
}

has() { grep -qx "$1" <<<"$2"; }

# Each step returns on failure itself: errexit does not apply inside a function called from `||`.
evaluate() {
  local pr="$1" sha labels files held state description docs=false
  sha="$(gh api "repos/$repo/pulls/$pr" --jq .head.sha)" || return 1
  labels="$(gh api "repos/$repo/issues/$pr/labels?per_page=100" --jq '.[].name')" || return 1
  files="$(gh api --paginate "repos/$repo/pulls/$pr/files?per_page=100" --jq '.[].filename')" || return 1
  if grep -q '^docs/' <<<"$files"; then docs=true; fi

  if [ "$docs" = true ] && [ "${GATE_PUSHED:-0}" = 1 ] && has user-approved "$labels"; then
    gh api -X DELETE "repos/$repo/issues/$pr/labels/user-approved" >/dev/null || return 1
    labels="$(grep -vx user-approved <<<"$labels" || true)"
  fi

  # Read the holds last, so a hold opened while this ran still turns the status red.
  held="$(holds)" || return 1
  if [ -n "$held" ] && ! has hold-exempt "$labels"; then
    state=failure description="보류 중입니다($held). 보류 이슈를 닫으면 풀립니다."
  elif [ "$docs" = true ] && ! has user-approved "$labels"; then
    state=failure description="docs/를 바꾸는 PR이라 사용자 확인 라벨 user-approved가 필요합니다."
  elif [ -n "$held" ]; then
    state=success description="보류 중이지만 hold-exempt 라벨로 예외입니다."
  else
    state=success description="보류가 없고 필요한 확인을 갖췄습니다."
  fi

  gh api -X POST "repos/$repo/statuses/$sha" -f state="$state" -f context=gate -f description="$description" \
    "${link[@]}" >/dev/null || return 1
  echo "#$pr ${sha:0:7}: $state, $description"
}

if [ $# -gt 0 ]; then
  evaluate "$1"
  exit
fi

# Keep going past one pull request's API error, so a hold still reaches the others, then fail the run.
prs="$(gh api --paginate "repos/$repo/pulls?state=open&per_page=100" --jq '.[].number')"
failed=0
for pr in $prs; do
  evaluate "$pr" || { echo "#$pr: could not post the gate status" >&2; failed=1; }
done
exit "$failed"
