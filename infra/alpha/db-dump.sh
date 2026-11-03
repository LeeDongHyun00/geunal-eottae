#!/usr/bin/env bash
# The alpha server's daily database dump (docs/parts/infra.md §2 and §9): PostgreSQL's custom format, kept for seven
# days under /var/backups/geunal, with a second copy in the backups bucket so a lost disk or VM does not lose the
# data. geunal-db-dump.timer runs it.
#
# The copy that leaves the server is encrypted with age to the operator's public key, so neither the bucket nor the
# server's own Object Storage key can read it; only the operator's private key, kept off the server, can. The upload
# needs /etc/geunal/dump.env (README.md "서버 밖 DB 덤프"); without it the dump stays local and the run says so.
set -euo pipefail
umask 077

dir=/var/backups/geunal
mkdir -p "$dir"
out="$dir/geunal-$(date +%Y%m%d-%H%M).dump"
trap 'rm -f "$out.part" "$out.age"' EXIT
docker compose --file /opt/geunal/compose.yml exec -T postgres \
  pg_dump --username geunal --format custom geunal >"$out.part"
mv "$out.part" "$out"
find "$dir" -name 'geunal-*.dump' -mtime +6 -delete

if [ ! -f /etc/geunal/dump.env ]; then
  echo "warning: /etc/geunal/dump.env is missing, so the dump stays on this server only" >&2
  exit 0
fi
# shellcheck source=/dev/null
. /etc/geunal/dump.env
: "${GEUNAL_DUMP_AGE_RECIPIENT:?dump.env needs GEUNAL_DUMP_AGE_RECIPIENT, the operator age public key}"
: "${GEUNAL_DUMP_S3_ACCESS_KEY:?dump.env needs the Object Storage access key of the server}"
: "${GEUNAL_DUMP_S3_SECRET_KEY:?dump.env needs the Object Storage secret key of the server}"
endpoint=${GEUNAL_DUMP_S3_ENDPOINT:-https://kr.object.ncloudstorage.com}
bucket=${GEUNAL_DUMP_S3_BUCKET:-geunal-alpha-backups}

age --encrypt --recipient "$GEUNAL_DUMP_AGE_RECIPIENT" --output "$out.age" "$out"
# One object per weekday: each run overwrites the one from a week ago, so the bucket keeps seven days (infra.md §2)
# without a lifecycle rule, which the NCP provider cannot set yet (modules/bucket). The API's own objects are UUIDs at
# the top of the bucket, so the db/ prefix cannot meet them.
key="db/geunal-$(date +%u).dump.age"
sha=$(sha256sum "$out.age" | cut -d' ' -f1)
# curl signs with SigV4 itself; the payload hash is sent so the store can check the body against the signature. The
# keys go through a config on stdin rather than the command line, where any user could read them in ps.
printf 'user = "%s:%s"\n' "$GEUNAL_DUMP_S3_ACCESS_KEY" "$GEUNAL_DUMP_S3_SECRET_KEY" |
  curl --config - --fail --silent --show-error --retry 3 \
    --aws-sigv4 "aws:amz:kr-standard:s3" \
    --header "x-amz-content-sha256: $sha" \
    --upload-file "$out.age" "$endpoint/$bucket/$key"
echo "Uploaded $key ($(stat -c %s "$out.age") bytes)" >&2
