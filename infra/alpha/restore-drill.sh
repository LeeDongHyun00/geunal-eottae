#!/usr/bin/env bash
# The restore drill of the alpha's daily database dump (infra/alpha/README.md "복원 연습"): a backup that has never
# been restored is only a hope. This restores one dump into a throwaway PostgreSQL of the image compose.yml pins and
# checks what came back. It runs on the operator's machine, where the age private key is, and touches neither the
# alpha server nor its database.
#
#   infra/alpha/restore-drill.sh geunal-20261117-0403.dump               # a dump as db-dump.sh keeps it on the server
#   infra/alpha/restore-drill.sh geunal-2.dump.age ~/.age/geunal.key     # the bucket's copy and the age identity file
#
# It passes when pg_restore reports no error, Flyway's history is there with every migration successful, and prints
# the row count of every table. Counts only: the rows are testers' data and never reach the terminal or a log.
set -euo pipefail
umask 077

dump=${1:?usage: restore-drill.sh <dump file | dump file.age> [age identity file]}
identity=${2:-}
if [ ! -f "$dump" ]; then
  echo "No such dump file: $dump" >&2
  exit 1
fi

here=$(cd "$(dirname "$0")" && pwd)
# The same image as the server's, so the drill also proves that this version reads the dump.
image=$(awk '$1 == "image:" && $2 ~ /^postgres:/ { print $2 }' "$here/compose.yml")
: "${image:?infra/alpha/compose.yml names no postgres image}"

container=geunal-restore-drill-$$
work=$(mktemp -d)
# Every way out, a failed check and Ctrl-C included, removes the container with its data volume and the decrypted
# dump: both hold the testers' data in the clear.
cleanup() {
  docker rm --force --volumes "$container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

case "$dump" in
  *.age)
    : "${identity:?an .age dump needs the age identity file (the private key) as the second argument}"
    command -v age >/dev/null 2>&1 || { echo "age is not installed (https://age-encryption.org)" >&2; exit 1; }
    echo "Decrypting $(basename "$dump")" >&2
    age --decrypt --identity "$identity" --output "$work/geunal.dump" "$dump"
    plain=$work/geunal.dump
    ;;
  *) plain=$dump ;;
esac

echo "Starting a throwaway $image as $container" >&2
# No published port: only `docker exec` reaches it. The role and database are named as on the server, so the dump's
# owners exist. The password is never used (connections inside the container go through the local socket) but the
# image refuses to start without one.
docker run --detach --name "$container" \
  --env POSTGRES_USER=geunal --env POSTGRES_DB=geunal --env POSTGRES_PASSWORD="drill-$$-$RANDOM$RANDOM" \
  "$image" >/dev/null

# Over TCP on purpose: while the image sets the database up it runs a first server on the socket only and then
# restarts, so a check through the socket would pass too early.
ready=
for _ in $(seq 60); do
  if docker exec "$container" pg_isready --quiet --host 127.0.0.1 --username geunal --dbname geunal; then
    ready=1
    break
  fi
  sleep 1
done
if [ -z "$ready" ]; then
  echo "FAIL: the throwaway PostgreSQL did not start within a minute" >&2
  docker logs --tail 20 "$container" >&2 || true
  exit 1
fi

sql() { docker exec -i "$container" psql --username geunal --dbname geunal --no-psqlrc --quiet --tuples-only --no-align \
  --set ON_ERROR_STOP=1 "$@"; }

echo "Restoring $(basename "$dump") ($(wc -c <"$plain" | tr -d ' ') bytes)" >&2
# Without --exit-on-error pg_restore goes on after an error, prints every one and still exits non-zero, so one run
# shows all that is wrong with a dump. Its messages name objects, never row contents.
if ! docker exec -i "$container" pg_restore --username geunal --dbname geunal <"$plain"; then
  echo "FAIL: pg_restore reported errors" >&2
  exit 1
fi

if [ "$(sql --command "select to_regclass('public.flyway_schema_history') is not null")" != t ]; then
  echo "FAIL: the restored database has no flyway_schema_history, so it is not the API's database" >&2
  exit 1
fi
applied=$(sql --command "select count(*) from flyway_schema_history where success")
failed=$(sql --command "select count(*) from flyway_schema_history where not success")
latest=$(sql --command "select coalesce(version, description) from flyway_schema_history
  order by installed_rank desc limit 1")
if [ "$failed" -ne 0 ] || [ "$applied" -eq 0 ]; then
  echo "FAIL: flyway_schema_history has $applied successful and $failed failed migrations" >&2
  exit 1
fi
echo "Flyway: $applied migrations, all successful; the latest is ${latest:-none}"
# For comparison only: a dump older than the last deploy rightly has fewer.
migrations=$here/../../server/api/src/main/resources/db/migration
if [ -d "$migrations" ]; then
  echo "This checkout has $(find "$migrations" -name 'V*.sql' | wc -l | tr -d ' ') versioned migrations"
fi

# Exact counts of every table outside the system schemas, partitions included (the log is partitioned by month).
# query_to_xml runs one count per table inside a single statement; planner statistics would be empty right after a
# restore.
echo "Rows per table:"
sql --field-separator ' ' --command "
  select format('%-48s', n.nspname || '.' || c.relname),
         (xpath('/row/n/text()', query_to_xml(format('select count(*) as n from %I.%I', n.nspname, c.relname),
                                              false, true, '')))[1]::text
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema')
  order by 1" | sed 's/^/  /'

echo "PASS: $(basename "$dump") restores into $image"
