#!/usr/bin/env bash
# Build or test the app on the simulator: `build`, `test` (the unit test bundle) or `uitest` (the UI test bundle), in
# the order docs/parts/ios.md §8 gives CI. Tests write a result bundle under apps/ios for CI to upload on failure.
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_macos
use_pinned_xcode

action="${1:?usage: xcodebuild.sh build|test|uitest}"
if [ ! -d "$project" ]; then
  echo "$project is missing; run make project-ios first" >&2
  exit 1
fi

case "$action" in
  build)
    run_xcodebuild -quiet build
    ;;
  test)
    rm -rf "$ios_dir/unit.xcresult"
    run_xcodebuild -only-testing:GeunalAppTests -resultBundlePath "$ios_dir/unit.xcresult" test
    ;;
  uitest)
    rm -rf "$ios_dir/ui.xcresult" "$ios_dir/screenshots"
    status=0
    run_xcodebuild -only-testing:GeunalAppUITests -resultBundlePath "$ios_dir/ui.xcresult" test || status=$?
    # The screen-by-screen captures go out whether the tests passed or not: a failing screen is the one to look at.
    attachments="$(mktemp -d)"
    if xcrun xcresulttool export attachments --path "$ios_dir/ui.xcresult" --output-path "$attachments" >/dev/null; then
      python3 -I "$ios_dir/scripts/export-screenshots.py" "$attachments" "$ios_dir/screenshots"
    else
      echo "::warning::could not export the UI test screenshots from ui.xcresult"
    fi
    rm -rf "$attachments"
    exit "$status"
    ;;
  *)
    echo "unknown action: $action (build|test|uitest)" >&2
    exit 1
    ;;
esac
