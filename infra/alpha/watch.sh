#!/usr/bin/env bash
# The alpha's basic alarm (docs/parts/infra.md §8, scaled down until M2's monitoring): .github/workflows/alpha-watch.yml
# runs this every 15 minutes from GitHub's runners, outside NCP, so it sees what a tester's phone sees. A problem
# opens one issue labelled alpha-alert that names the owner, which is what makes GitHub notify them; the issue gets
# no further comments while the problem lasts, and closes with a note once every check passes again. The run itself
# stays green while it reports a problem: a failing scheduled run mails its author every 15 minutes, so a red run
# means the watch itself is broken.
#
#   GEUNAL_WATCH_HOST=api.geunaleottae.com [GEUNAL_WATCH_RT_HOST=rt.geunaleottae.com] \
#     GEUNAL_WATCH_NOTIFY=<GitHub login> GH_TOKEN=... infra/alpha/watch.sh
#
# Checks:
# - /actuator/health answers 200 with "UP". The API's health includes free disk space (compose.yml sets the
#   threshold), so a server running out of disk shows up here as well.
# - With GEUNAL_WATCH_RT_HOST, the realtime gateway's /healthz answers 200, which it does only while its Redis
#   connections are up (docs/parts/gateway.md). Without the variable the gateway is not looked at, so the script
#   still serves a server deployed without host names for the gateway.
# - Each host's TLS certificate has at least GEUNAL_WATCH_CERT_DAYS days left. Caddy renews a third of the lifetime
#   before the end, so this only fires when renewal has been failing for days.
# - The daily database dump's status file (db-dump.sh writes it, Caddy serves it at /_alpha/db-dump.json) names a
#   good run within GEUNAL_WATCH_DUMP_HOURS hours whose copy left the server. The dump runs once a day at 04:00 Seoul
#   time with up to ten minutes of delay, so 26 hours is one missed run plus slack.
set -euo pipefail

host=${GEUNAL_WATCH_HOST:?set GEUNAL_WATCH_HOST}
rt_host=${GEUNAL_WATCH_RT_HOST:-}
notify=${GEUNAL_WATCH_NOTIFY:?set GEUNAL_WATCH_NOTIFY to the GitHub login to notify}
min_days=${GEUNAL_WATCH_CERT_DAYS:-14}
max_dump_hours=${GEUNAL_WATCH_DUMP_HOURS:-26}
label=alpha-alert
problems=()

# What a server said goes into the issue as one short line of code: no backticks or line breaks that could end the
# code span and turn the rest into Markdown.
quote() { printf '%s' "$1" | tr -d '`' | tr '\n\r' '  ' | head -c 200; }

# Four tries over about two minutes, so a dropped packet or a service restarting during a deploy pages no one.
fetch() { curl -fsS --max-time 10 --retry 3 --retry-delay 30 --retry-all-errors "$1" 2>&1; }

if body=$(fetch "https://$host/actuator/health"); then
  case "$body" in
    *'"status":"UP"'*) ;;
    *) problems+=("상태 검사가 UP이 아닙니다: \`$(quote "$body")\`") ;;
  esac
else
  problems+=("\`https://$host/actuator/health\`에 닿지 않거나 오류로 답합니다: \`$(quote "$(printf '%s' "$body" | tail -n 1)")\`")
fi

if [ -n "$rt_host" ] && ! body=$(fetch "https://$rt_host/healthz"); then
  problems+=("실시간 게이트웨이 \`https://$rt_host/healthz\`에 닿지 않거나 오류로 답합니다: \`$(quote "$(printf '%s' "$body" | tail -n 1)")\`")
fi

# s_client has no timeout of its own for TCP, and a server that accepts the connection but never finishes the
# handshake would hold the run until the job's limit, so no alert would ever be opened.
check_cert() {
  local name=$1 end days
  if end=$(timeout 20 openssl s_client -connect "$name:443" -servername "$name" </dev/null 2>/dev/null |
    openssl x509 -noout -enddate 2>/dev/null); then
    end=${end#notAfter=}
    days=$((($(date -u -d "$end" +%s) - $(date -u +%s)) / 86400))
    if [ "$days" -lt "$min_days" ]; then
      problems+=("\`$name\`의 TLS 인증서가 ${days}일 뒤(${end}) 끝납니다. Caddy의 갱신이 실패하고 있을 수 있습니다.")
    fi
  else
    problems+=("\`$name\`의 TLS 인증서를 20초 안에 읽지 못했습니다.")
  fi
}
check_cert "$host"
if [ -n "$rt_host" ]; then
  check_cert "$rt_host"
fi

# The dump's status. It is read once the API has answered or failed above, so a deploy that is restarting Caddy has
# had its two minutes; three quick tries cover a dropped packet. Anything that is not the three expected values
# counts as missing: the file is the only thing that says the dump ran.
dump_url="https://$host/_alpha/db-dump.json"
# curl's messages go to a file of their own: a try that failed before one that succeeded leaves its message behind,
# and mixed into the body it would make a good status unreadable.
dump_err=$(mktemp)
trap 'rm -f "$dump_err"' EXIT
if body=$(curl -fsS --max-time 10 --retry 2 --retry-delay 5 --retry-all-errors "$dump_url" 2>"$dump_err"); then
  # The timestamp must have the exact shape db-dump.sh writes: `date -d` would also take words such as "tomorrow".
  if last=$(printf '%s' "$body" | jq -er '.last_success | strings' 2>/dev/null) &&
    [[ $last =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] &&
    last_s=$(date -u -d "$last" +%s 2>/dev/null) &&
    uploaded=$(printf '%s' "$body" | jq -r '.uploaded' 2>/dev/null); then
    age_hours=$((($(date -u +%s) - last_s) / 3600))
    if [ "$age_hours" -ge "$max_dump_hours" ]; then
      problems+=("마지막으로 성공한 DB 덤프가 ${age_hours}시간 전(\`$(quote "$last")\`)입니다. 매일 덤프가 실패하고 있습니다.")
    fi
    if [ "$uploaded" != true ]; then
      problems+=("마지막 DB 덤프(\`$(quote "$last")\`)가 서버 밖으로 올라가지 않았습니다(\`uploaded\`가 \`true\`가 아님).")
    fi
  else
    problems+=("DB 덤프 상태(\`$dump_url\`)를 읽을 수 없습니다: \`$(quote "$body")\`")
  fi
else
  problems+=("DB 덤프 상태(\`$dump_url\`)가 없거나 오류로 답합니다: \`$(quote "$(tail -n 1 "$dump_err")")\`")
fi

open=$(gh issue list --label "$label" --state open --json number --jq '.[0].number // empty')

if [ "${#problems[@]}" -eq 0 ]; then
  echo "All checks passed for $host${rt_host:+ and $rt_host}"
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
