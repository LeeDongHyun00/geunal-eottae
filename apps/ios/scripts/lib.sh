#!/usr/bin/env bash
# Shared by the iOS scripts: paths, the pinned Xcode, and the xcodebuild invocation. Sourced, not run.
# Every script needs macOS with Xcode (docs/parts/ios.md §8); the CI macOS runner is the only place that verifies them.
# shellcheck disable=SC2034 # the variables are for the scripts that source this file

ios_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$(cd "$ios_dir/../.." && pwd)"
project="$ios_dir/GeunalApp.xcodeproj"
scheme=GeunalApp
derived_data="$ios_dir/DerivedData"

require_macos() {
  if [ "$(uname -s)" != Darwin ]; then
    echo "$(basename "$0") needs macOS with Xcode; CI runs it on a macOS runner" >&2
    exit 1
  fi
}

# Export DEVELOPER_DIR for the Xcode release apps/ios/.xcode-version pins.
use_pinned_xcode() {
  local line
  line="$("$ios_dir/scripts/xcode-env.sh")"
  export "${line?}"
}

# The simulator destination: IOS_SIMULATOR names a device (default iPhone 17) and the newest installed runtime is used.
# When that device is missing, the first available iPhone stands in, so a runner image change does not fail the build.
simulator_destination() {
  local name="${IOS_SIMULATOR:-iPhone 17}" devices fallback
  devices="$(xcrun simctl list devices available)"
  if ! grep -qF "    $name (" <<<"$devices"; then
    fallback="$(grep -m1 -E '^    iPhone [^(]*\([0-9A-F-]{36}\)' <<<"$devices" | sed -E 's/^    (.*) \([0-9A-F-]{36}\).*$/\1/')"
    if [ -z "$fallback" ]; then
      echo "no iPhone simulator is installed" >&2
      exit 1
    fi
    echo "simulator '$name' is not installed; using '$fallback'" >&2
    name="$fallback"
  fi
  echo "platform=iOS Simulator,name=$name,OS=latest"
}

# Run xcodebuild on the app scheme for the simulator. Signing is off because simulator builds need none and the CI
# runner has no certificate. Extra arguments go to xcodebuild.
run_xcodebuild() {
  xcodebuild \
    -project "$project" \
    -scheme "$scheme" \
    -destination "$(simulator_destination)" \
    -derivedDataPath "$derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGN_IDENTITY= \
    "$@"
}
