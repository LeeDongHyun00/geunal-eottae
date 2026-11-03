#!/usr/bin/env bash
# Post the `gate` commit status that main's ruleset requires next to `ci-ok` (CLAUDE.md, "여러 세션이 함께 일할 때").
# Pull requests merge by auto-merge, so this status is what stops them:
# - while an open issue labelled `hold` pauses the project, unless the pull request is labelled `hold-exempt`;
# - on a pull request that changes docs/ (renames out of docs/ included) until the user approves its head commit.
#   The user labels it `user-approved`, and gate.yml then marks the commit the label was added on with a
#   `user-approved` status. Both must be present, so an approval never carries over to commits pushed after it. A
#   label stands in for a review because sessions open pull requests under the user's account, and GitHub does not
#   let an author approve their own pull request.
#
# Every run evaluates every open pull request from what it reads now, and gate.yml runs one evaluation at a time,
# newest last. So no run can overwrite a newer result with a stale one, and a run that a newer event cancels loses
# nothing. Pull requests that share a head commit share its status, so a commit is green only if every pull request
# on it is.
#
# Usage: scripts/gate.sh   (needs GH_TOKEN, GITHUB_REPOSITORY and jq)
# PUSHED_PR names a pull request that just received a push: if it changes docs/, it loses `user-approved`, so the
# label on GitHub matches the approval the gate sees.
set -euo pipefail

repo="${GITHUB_REPOSITORY:?set GITHUB_REPOSITORY to owner/name}"
link=()
if [ -n "${GITHUB_RUN_ID:-}" ]; then
  link=(-f "target_url=${GITHUB_SERVER_URL:-https://github.com}/$repo/actions/runs/$GITHUB_RUN_ID")
fi

has() { grep -qx "$1" <<<"$2"; }

# Up to three open hold issues as "#12, #34". The issues list also returns pull requests, which carry pull_request.
held="$(gh api "repos/$repo/issues?state=open&labels=hold&per_page=100" \
  --jq '[.[] | select(.pull_request == null) | "#\(.number)"] | .[:3] | join(", ")')"
pulls="$(gh api --paginate "repos/$repo/pulls?state=open&per_page=100" \
  --jq '.[] | {number, sha: .head.sha, labels: [.labels[].name]} | @json')"

declare -A verdict note current
failed=0

# Sets state and description for one pull request.
decide() {
  local docs="$1" labels="$2" approved="$3"
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

# Decides one pull request and folds the result into its head commit's verdict, failure first. Each step returns on
# failure itself: errexit does not apply inside a function called from `||`.
evaluate() {
  local pull="$1" pr sha labels files status docs=false approved=false state description
  pr="$(jq -r .number <<<"$pull")" || return 1
  sha="$(jq -r .sha <<<"$pull")" || return 1
  labels="$(jq -r '.labels[]' <<<"$pull")" || return 1
  files="$(gh api --paginate "repos/$repo/pulls/$pr/files?per_page=100" \
    --jq '.[] | .filename, (.previous_filename // empty)')" || return 1
  if grep -q '^docs/' <<<"$files"; then docs=true; fi

  status="$(gh api "repos/$repo/commits/$sha/status?per_page=100")" || return 1
  if jq -e '.statuses | any(.context == "user-approved" and .state == "success")' <<<"$status" >/dev/null; then
    approved=true
  fi
  current[$sha]="$(jq -r '.statuses[] | select(.context == "gate") | "\(.state) \(.description)"' <<<"$status")"

  # A label left from an earlier commit goes; one the user added after this push already marked the head and stays.
  if [ "$docs" = true ] && [ "${PUSHED_PR:-}" = "$pr" ] && [ "$approved" != true ] && has user-approved "$labels"; then
    gh api -X DELETE "repos/$repo/issues/$pr/labels/user-approved" >/dev/null || return 1
    labels="$(grep -vx user-approved <<<"$labels" || true)"
  fi

  decide "$docs" "$labels" "$approved"
  echo "#$pr ${sha:0:7}: $state, $description"
  if [ "${verdict[$sha]:-success}" = success ]; then
    if [ "$state" = failure ] || [ -z "${verdict[$sha]:-}" ]; then
      verdict[$sha]="$state" note[$sha]="$description"
    fi
  fi
}

# Keep going past one pull request's API error, so a hold still reaches the others, then fail the run. A pull
# request that could not be evaluated turns its commit red rather than keeping a status it may no longer deserve.
while IFS= read -r pull; do
  [ -n "$pull" ] || continue
  sha="$(jq -r .sha <<<"$pull")"
  if ! evaluate "$pull"; then
    echo "could not evaluate $pull" >&2
    failed=1
    verdict[$sha]=failure note[$sha]="이 PR을 평가하지 못했습니다. gate 워크플로를 다시 돌려 주세요."
  fi
done <<<"$pulls"

# Post only what changed, so every run does not add a status to every open pull request.
for sha in "${!verdict[@]}"; do
  if [ "${current[$sha]:-}" = "${verdict[$sha]} ${note[$sha]}" ]; then continue; fi
  gh api -X POST "repos/$repo/statuses/$sha" -f state="${verdict[$sha]}" -f context=gate \
    -f description="${note[$sha]}" "${link[@]}" >/dev/null || { echo "${sha:0:7}: could not post" >&2; failed=1; }
done
exit "$failed"
