#!/usr/bin/env bash
# Post the `gate` commit status that main's ruleset requires next to `ci-ok` (CLAUDE.md, "여러 세션이 함께 일할 때").
# Pull requests merge by auto-merge, so this status is what stops them:
# - while an open issue labelled `hold` pauses the project, unless the pull request is labelled `hold-exempt`;
# - on a pull request that changes docs/ (renames out of docs/ included) until the user approves its head commit.
#   The user labels it `user-approved`, and gate.yml then marks the commit the label was added on with a
#   `user-approved` status. Both must be present, so an approval never carries over to commits pushed after it, even
#   when the run that would drop the label is cancelled. A label stands in for a review because sessions open pull
#   requests under the user's account, and GitHub does not let an author approve their own pull request.
# A commit status rather than a job result, so that opening or closing a hold issue flips every open pull request at
# once without rerunning CI.
#
# Usage: scripts/gate.sh [<pr-number>]   (no argument: every open pull request)
# Needs GH_TOKEN and GITHUB_REPOSITORY. PUSHED_PR names a pull request that just received a push: if it changes
# docs/, it loses `user-approved`, so the label on GitHub matches the approval the gate sees.
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

# Sets state and description from the holds, whether docs/ changed, the labels and the head commit's approval.
decide() {
  local held="$1" docs="$2" labels="$3" approved="$4"
  if [ -n "$held" ] && ! has hold-exempt "$labels"; then
    state=failure description="보류 중입니다($held). 보류 이슈를 닫으면 풀립니다."
  elif [ "$docs" = true ] && ! has user-approved "$labels"; then
    state=failure description="docs/를 바꾸는 PR이라 사용자 확인 라벨 user-approved가 필요합니다."
  elif [ "$docs" = true ] && [ "$approved" != true ]; then
    state=failure description="이 커밋에는 사용자 확인이 없습니다. user-approved 라벨을 뗐다가 다시 달아 주세요."
  elif [ -n "$held" ]; then
    state=success description="보류 중이지만 hold-exempt 라벨로 예외입니다."
  else
    state=success description="보류가 없고 필요한 확인을 갖췄습니다."
  fi
}

post() {
  gh api -X POST "repos/$repo/statuses/$1" -f state="$state" -f context=gate -f description="$description" \
    "${link[@]}" >/dev/null
}

# Each step returns on failure itself: errexit does not apply inside a function called from `||`.
evaluate() {
  local pr="$1" sha labels files marks docs=false approved=false held now state description
  sha="$(gh api "repos/$repo/pulls/$pr" --jq .head.sha)" || return 1
  labels="$(gh api "repos/$repo/issues/$pr/labels?per_page=100" --jq '.[].name')" || return 1
  files="$(gh api --paginate "repos/$repo/pulls/$pr/files?per_page=100" \
    --jq '.[] | .filename, (.previous_filename // empty)')" || return 1
  if grep -q '^docs/' <<<"$files"; then docs=true; fi

  if [ "$docs" = true ]; then
    if [ "${PUSHED_PR:-}" = "$pr" ] && has user-approved "$labels"; then
      gh api -X DELETE "repos/$repo/issues/$pr/labels/user-approved" >/dev/null || return 1
      labels="$(grep -vx user-approved <<<"$labels" || true)"
    fi
    marks="$(gh api "repos/$repo/commits/$sha/status?per_page=100" \
      --jq '[.statuses[] | select(.context == "user-approved" and .state == "success")] | length')" || return 1
    if [ "$marks" -gt 0 ]; then approved=true; fi
  fi

  held="$(holds)" || return 1
  decide "$held" "$docs" "$labels" "$approved"
  post "$sha" || return 1
  # Another run may have posted for this commit with the other hold state while this one ran; whichever wrote last
  # wins. Reading the holds again after writing and correcting a stale result means the last write matches them.
  now="$(holds)" || return 1
  if [ "$now" != "$held" ]; then
    decide "$now" "$docs" "$labels" "$approved"
    post "$sha" || return 1
  fi
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
