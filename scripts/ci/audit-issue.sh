#!/usr/bin/env bash
# Keep exactly one open issue while the weekly dependency audit (.github/workflows/audit.yml) fails, and close it when
# the audit passes again. A scheduled workflow has no pull request to turn red, and GitHub only e-mails the person who
# last touched the workflow file, so the issue is what makes a failure visible to everyone (docs/security/dependencies.md).
# Usage: scripts/ci/audit-issue.sh <needs-json> <run-url>, where <needs-json> is `toJSON(needs)` of the report job.
# Needs GH_TOKEN with issues: write and GITHUB_REPOSITORY, both set by Actions.
set -euo pipefail

needs="${1:?usage: scripts/ci/audit-issue.sh <needs-json> <run-url>}"
run_url="${2:?usage: scripts/ci/audit-issue.sh <needs-json> <run-url>}"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"

label=security
title="주간 의존성 취약점 검사 실패"

# Every audited part must have passed; a cancelled or skipped job is not a pass, so it keeps the issue open.
failed="$(jq -r '[to_entries[] | select(.value.result != "success") | .key] | join(", ")' <<<"$needs")"

# The issue this script opened earlier, if it is still open. The label filter is the API's; the title is ours.
number="$(gh api "repos/$repo/issues?state=open&labels=$label&per_page=100" |
  jq -r --arg title "$title" '[.[] | select(.title == $title and (has("pull_request") | not))] | first | .number // empty')"

if [ -z "$failed" ]; then
  if [ -n "$number" ]; then
    gh issue close "$number" --repo "$repo" --comment "주간 검사가 다시 통과했습니다: $run_url"
    echo "closed #$number"
  else
    echo "audit passed; no open issue"
  fi
  exit 0
fi

body="$(cat <<MSG
\`main\`의 주간 의존성 취약점 검사가 실패했습니다.

- 실패한 작업: $failed
- 실행: $run_url

대응 절차는 [의존성 취약점 검사](${GITHUB_SERVER_URL:-https://github.com}/$repo/blob/main/docs/security/dependencies.md) 3장에 있습니다. 검사가 다시 통과하면 이 이슈는 자동으로 닫힙니다.
MSG
)"

if [ -n "$number" ]; then
  gh issue comment "$number" --repo "$repo" --body "$body"
  echo "commented on #$number"
else
  # --force updates the label when it already exists instead of failing.
  gh label create "$label" --repo "$repo" --color B60205 --force \
    --description "보안: 취약점, 의존성 권고, 위협 모델"
  gh issue create --repo "$repo" --title "$title" --label "$label" --body "$body"
fi
