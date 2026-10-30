#!/usr/bin/env bash
# The alpha server's daily database dump (docs/parts/infra.md §2): PostgreSQL's custom format, kept for seven days
# under /var/backups/geunal. geunal-db-dump.timer runs it. The second copy infra.md asks for, in the backups bucket,
# waits for an Object Storage key of the server's own.
set -euo pipefail
umask 077

dir=/var/backups/geunal
mkdir -p "$dir"
out="$dir/geunal-$(date +%Y%m%d-%H%M).dump"
trap 'rm -f "$out.part"' EXIT
docker compose --file /opt/geunal/compose.yml exec -T postgres \
  pg_dump --username geunal --format custom geunal >"$out.part"
mv "$out.part" "$out"
find "$dir" -name 'geunal-*.dump' -mtime +6 -delete
