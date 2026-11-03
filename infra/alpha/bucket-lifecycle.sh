#!/usr/bin/env bash
# Puts the expiry rule on the alpha access-logs bucket: access logs are kept for 92 days and no longer
# (docs/parts/infra.md §1 and §9, the legal three months plus a margin). The NCP Terraform provider cannot set
# lifecycle rules yet (infra/terraform/README.md), so this calls PutBucketLifecycleConfiguration directly. It runs
# from the operator's machine with a key that may change bucket settings, not the server's write-only keys.
#
#   GEUNAL_LIFECYCLE_S3_ACCESS_KEY=... GEUNAL_LIFECYCLE_S3_SECRET_KEY=... infra/alpha/bucket-lifecycle.sh
#
# Running it again replaces the rule with the same one. It prints the rule the store holds afterwards.
set -euo pipefail

: "${GEUNAL_LIFECYCLE_S3_ACCESS_KEY:?set GEUNAL_LIFECYCLE_S3_ACCESS_KEY}"
: "${GEUNAL_LIFECYCLE_S3_SECRET_KEY:?set GEUNAL_LIFECYCLE_S3_SECRET_KEY}"
endpoint=${GEUNAL_LIFECYCLE_S3_ENDPOINT:-https://kr.object.ncloudstorage.com}
bucket=${GEUNAL_LIFECYCLE_BUCKET:-geunal-alpha-access-logs}
days=${GEUNAL_LIFECYCLE_DAYS:-92}

body="<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<LifecycleConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">
  <Rule>
    <ID>expire-after-${days}-days</ID>
    <Filter><Prefix></Prefix></Filter>
    <Status>Enabled</Status>
    <Expiration><Days>${days}</Days></Expiration>
  </Rule>
</LifecycleConfiguration>"

# S3 refuses a lifecycle body without Content-MD5; the SHA-256 goes in too so the signature covers the body.
md5=$(printf '%s' "$body" | openssl dgst -md5 -binary | base64)
sha=$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)
# The keys go through a config on stdin rather than the command line, where other users could read them in ps.
creds() { printf 'user = "%s:%s"\n' "$GEUNAL_LIFECYCLE_S3_ACCESS_KEY" "$GEUNAL_LIFECYCLE_S3_SECRET_KEY"; }

creds | curl --config - --fail --silent --show-error \
  --aws-sigv4 "aws:amz:kr-standard:s3" \
  --header "Content-MD5: $md5" \
  --header "x-amz-content-sha256: $sha" \
  --header "Content-Type: application/xml" \
  --request PUT --data-binary "$body" "$endpoint/$bucket?lifecycle"

echo "The lifecycle rule $bucket now holds:" >&2
creds | curl --config - --fail --silent --show-error \
  --aws-sigv4 "aws:amz:kr-standard:s3" \
  --header "x-amz-content-sha256: $(printf '' | sha256sum | cut -d' ' -f1)" \
  "$endpoint/$bucket?lifecycle"
echo
