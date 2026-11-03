#!/usr/bin/env bash
# Release builds of the app (infra/release/README.md):
#   check   archive the Release configuration for devices without signing, so CI sees a release that does not compile
#           long before anyone presses "Run workflow"
#   upload  archive with automatic signing in the cloud and send the build to TestFlight (`make release-ios`, run by
#           .github/workflows/release-ios.yml)
# Secrets never reach the command line or the log: the Kakao key goes through an xcconfig file only this user can
# read, and the App Store Connect key stays the file the workflow wrote. xcodebuild takes the key's ID and issuer only
# as arguments; they name the key and do not sign anything without the .p8.
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_macos
use_pinned_xcode

mode="${1:?usage: release.sh check|upload}"
if [ ! -d "$project" ]; then
  echo "$project is missing; run make project-ios first" >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
archive="$work/GeunalApp.xcarchive"

pinned=()
if [ -f "$ios_dir/Package.resolved" ]; then
  pinned=(-onlyUsePackageVersionsFromResolvedFile)
fi

archive_app() {
  xcodebuild \
    -project "$project" \
    -scheme "$scheme" \
    -configuration Release \
    -destination "generic/platform=iOS" \
    -derivedDataPath "$derived_data" \
    -archivePath "$archive" \
    ${pinned[@]+"${pinned[@]}"} \
    -quiet \
    "$@" \
    archive
}

case "$mode" in
  check)
    archive_app CODE_SIGNING_ALLOWED=NO
    ;;
  upload)
    missing=()
    for name in GEUNAL_BUILD_NUMBER GEUNAL_API_BASE GEUNAL_KAKAO_APP_KEY GEUNAL_APPLE_TEAM_ID \
      GEUNAL_ASC_KEY_PATH GEUNAL_ASC_KEY_ID GEUNAL_ASC_ISSUER_ID; do
      if [ -z "${!name:-}" ]; then
        missing+=("$name")
      fi
    done
    if [ "${#missing[@]}" -gt 0 ]; then
      echo "release.sh upload needs ${missing[*]} (infra/release/README.md)" >&2
      exit 1
    fi
    # The core refuses plain http outside the local stack, and then the store would not open at all.
    case "$GEUNAL_API_BASE" in
      https://*) ;;
      *)
        echo "GEUNAL_API_BASE must start with https://" >&2
        exit 1
        ;;
    esac
    # Each value becomes a line of an xcconfig file, so a stray newline or $(...) could set something else; checking the
    # shape also says early which secret was pasted wrong, without printing it.
    if ! [[ "$GEUNAL_BUILD_NUMBER" =~ ^[0-9]+$ ]]; then
      echo "GEUNAL_BUILD_NUMBER must be a whole number" >&2
      exit 1
    fi
    if ! [[ "$GEUNAL_KAKAO_APP_KEY" =~ ^[0-9a-f]{32}$ ]]; then
      echo "GEUNAL_KAKAO_APP_KEY must be the 32 hex digits of the Kakao native app key" >&2
      exit 1
    fi
    if ! [[ "$GEUNAL_APPLE_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]]; then
      echo "GEUNAL_APPLE_TEAM_ID must be the 10-character Apple team ID" >&2
      exit 1
    fi
    if ! [[ "$GEUNAL_API_BASE" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._/-]*)?$ ]]; then
      echo "GEUNAL_API_BASE must be a plain https:// address" >&2
      exit 1
    fi

    # xcconfig reads `//` as a comment, so the address is written https:/$()/… as in Secrets.example.xcconfig.
    settings="$work/release.xcconfig"
    (
      umask 077
      {
        echo "KAKAO_APP_KEY = $GEUNAL_KAKAO_APP_KEY"
        echo "GEUNAL_API_BASE = https:/\$()/${GEUNAL_API_BASE#https://}"
        echo "DEVELOPMENT_TEAM = $GEUNAL_APPLE_TEAM_ID"
        echo "CURRENT_PROJECT_VERSION = $GEUNAL_BUILD_NUMBER"
        echo "CODE_SIGN_STYLE = Automatic"
      } >"$settings"
    )
    auth=(
      -allowProvisioningUpdates
      -authenticationKeyPath "$GEUNAL_ASC_KEY_PATH"
      -authenticationKeyID "$GEUNAL_ASC_KEY_ID"
      -authenticationKeyIssuerID "$GEUNAL_ASC_ISSUER_ID"
    )
    archive_app -xcconfig "$settings" "${auth[@]}"

    options="$work/ExportOptions.plist"
    cat >"$options" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>upload</string>
	<key>teamID</key>
	<string>$GEUNAL_APPLE_TEAM_ID</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
	<key>uploadSymbols</key>
	<true/>
</dict>
</plist>
EOF
    xcodebuild -exportArchive \
      -archivePath "$archive" \
      -exportOptionsPlist "$options" \
      -exportPath "$work/export" \
      "${auth[@]}"
    echo "uploaded build $GEUNAL_BUILD_NUMBER to App Store Connect"
    ;;
  *)
    echo "unknown mode: $mode (check|upload)" >&2
    exit 1
    ;;
esac
