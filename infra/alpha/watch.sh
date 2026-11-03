#!/usr/bin/env bash
# The alpha's basic alarm (docs/parts/infra.md §8, scaled down until M2's monitoring): .github/workflows/alpha-watch.yml
# runs this every 15 minutes from GitHub's runners, outside NCP, so it sees what a tester's phone sees. A problem
# opens one issue labelled alpha-alert that names the owner, which is what makes GitHub notify them; the issue gets
# no further comments while the problem lasts, and closes with a note once every check passes again. The run itself
# stays green while it reports a problem: a failing scheduled run mails its author every 15 minutes, so a red run
# means the watch itself is broken.
#
#   GEUNAL_WATCH_HOST=api.geunaleottae.com GEUNAL_WATCH_NOTIFY=<GitHub login> GH_TOKEN=... infra/alpha/watch.sh
#
# Checks:
# - /actuator/health answers 200 with "UP". The API's health includes free disk space (compose.yml sets the
#   threshold), so a server running out of disk shows up here as well.
# - The TLS certificate has at least GEUNAL_WATCH_CERT_DAYS days left. Caddy renews a third of the lifetime before the
#   end, so this only fires when renewal has been failing for days.
set -euo pipefail

host=${GEUNAL_WATCH_HOST:?set GEUNAL_WATCH_HOST}
notify=${GEUNAL_WATCH_NOTIFY:?set GEUNAL_WATCH_NOTIFY to the GitHub login to notify}
min_days=${GEUNAL_WATCH_CERT_DAYS:-14}
label=alpha-alert
problems=()

# Four tries over about two minutes, so a dropped packet or the API restarting during a deploy pages no one.
if body=$(curl -fsS --max-time 10 --retry 3 --retry-delay 30 --retry-all-errors "https://$host/actuator/health" 2>&1); then
  case "$body" in
    *'"status":"UP"'*) ;;
    *) problems+=("상태 검사가 UP이 아닙니다: \`$(printf '%s' "$body" | head -c 200)\`") ;;
  esac
else
  problems+=("\`https://$host/actuator/health\`에 닿지 않거나 오류로 답합니다: \`$(printf '%s' "$body" | tail -n 1 | head -c 200)\`")
fi

if end=$(openssl s_client -connect "$host:443" -servername "$host" </dev/null 2>/dev/null |
  openssl x509 -noout -enddate 2>/dev/null); then
  end=${end#notAfter=}
  days=$((($(date -u -d "$end" +%s) - $(date -u +%s)) / 86400))
  if [ "$days" -lt "$min_days" ]; then
    problems+=("TLS 인증서가 ${days}일 뒤(${end}) 끝납니다. Caddy의 갱신이 실패하고 있을 수 있습니다.")
  fi
else
  problems+=("TLS 인증서를 읽지 못했습니다.")
fi

open=$(gh issue list --label "$label" --state open --json number --jq '.[0].number // empty')

if [ "${#problems[@]}" -eq 0 ]; then
  echo "All checks passed for $host"
  if [ -n "$open" ]; then
    gh issue close "$open" --comment "모든 검사가 다시 통과합니다($(date -u +%Y-%m-%dT%H:%MZ))."
  fi
  exit 0
fi

printf 'Problem: %s\n' "${problems[@]}" >&2
if [ -z "$open" ]; then
  gh label create "$label" --color B60205 --description "The alpha server failed an outside check" --force >/dev/null
  {
    echo "@$notify 알파 서버(\`$host\`)가 바깥 검사에 실패했습니다($(date -u +%Y-%m-%dT%H:%MZ))."
    echo
    printf -- '- %s\n' "${problems[@]}"
    echo
    echo "확인 방법과 흔한 원인은 [infra/alpha/README.md](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:?}/blob/main/infra/alpha/README.md)의 \"경보\"에 있습니다. 모든 검사가 다시 통과하면 이 이슈는 저절로 닫힙니다."
  } | gh issue create --title "알파 서버 경보: $host" --label "$label" --body-file -
fi
