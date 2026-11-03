#!/usr/bin/env bash
# `make release-android`: the signed app bundle for Play's internal testing track (infra/release/README.md). The
# release workflow fills the GEUNAL_* variables; this checks them, hands the non-secret ones to Gradle as properties
# through the environment and leaves the key and its passwords for app/build.gradle.kts to read from the environment.
# No secret goes on a command line or into the log.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
  echo "release-android: $1" >&2
  exit 1
}

need() {
  [ -n "${!1:-}" ] || fail "$1 is not set"
}

for name in GEUNAL_BUILD_NUMBER GEUNAL_API_BASE GEUNAL_KAKAO_APP_KEY GEUNAL_APPLE_SERVICE_ID GEUNAL_APP_LINK_HOST \
  GEUNAL_UPLOAD_KEYSTORE GEUNAL_UPLOAD_KEYSTORE_PASSWORD GEUNAL_UPLOAD_KEY_ALIAS GEUNAL_UPLOAD_KEY_PASSWORD; do
  need "$name"
done
[[ "$GEUNAL_BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || fail "GEUNAL_BUILD_NUMBER must be a positive whole number"
# Play's limit on versionCode.
((GEUNAL_BUILD_NUMBER <= 2100000000)) || fail "GEUNAL_BUILD_NUMBER is above Play's versionCode limit"
[ -f "$GEUNAL_UPLOAD_KEYSTORE" ] || fail "GEUNAL_UPLOAD_KEYSTORE is not a file"

case "${GEUNAL_PLAY_UPLOAD:-false}" in
  true)
    need GEUNAL_PLAY_SERVICE_ACCOUNT
    [ -f "$GEUNAL_PLAY_SERVICE_ACCOUNT" ] || fail "GEUNAL_PLAY_SERVICE_ACCOUNT is not a file"
    tasks=(bundleRelease publishReleaseBundle)
    ;;
  false)
    # Without the service account the publishing tasks stay off, so nothing reaches Play.
    unset GEUNAL_PLAY_SERVICE_ACCOUNT
    tasks=(bundleRelease)
    ;;
  *) fail "GEUNAL_PLAY_UPLOAD must be true or false" ;;
esac

export ORG_GRADLE_PROJECT_geunalBuildNumber="$GEUNAL_BUILD_NUMBER"
export ORG_GRADLE_PROJECT_geunalApiBase="$GEUNAL_API_BASE"
export ORG_GRADLE_PROJECT_geunalKakaoAppKey="$GEUNAL_KAKAO_APP_KEY"
export ORG_GRADLE_PROJECT_geunalAppleServiceId="$GEUNAL_APPLE_SERVICE_ID"
export ORG_GRADLE_PROJECT_geunalAppLinkHost="$GEUNAL_APP_LINK_HOST"

ANDROID_HOME="$(../../scripts/tool.sh android-sdk)"
export ANDROID_HOME

# An older bundle left in the output directory would be checked, and uploaded as an artifact, in place of this one.
outputs=app/build/outputs/bundle/release
if [ -d "$outputs" ]; then
  find "$outputs" -maxdepth 1 -name '*.aab' -delete
fi

# Without the configuration cache: its entry would keep the passwords Gradle read from the environment on disk.
./gradlew --no-configuration-cache "${tasks[@]}"

bundles=("$outputs"/*.aab)
[ -f "${bundles[0]}" ] || fail "no bundle in $outputs"
# A bundle Play would refuse for its signature should fail here, not after the upload. Not -strict: an upload key is
# self-signed, which -strict counts as an error. An unsigned bundle passes -verify too, saying "jar is unsigned".
jarsigner -verify "${bundles[0]}" | grep -x 'jar verified.' >/dev/null || fail "${bundles[0]} is not signed"
echo "release-android: ${bundles[0]} (versionCode $GEUNAL_BUILD_NUMBER)"
