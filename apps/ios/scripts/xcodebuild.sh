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
    rm -rf "$ios_dir/ui.xcresult"
    run_xcodebuild -only-testing:GeunalAppUITests -resultBundlePath "$ios_dir/ui.xcresult" test
    ;;
  *)
    echo "unknown action: $action (build|test|uitest)" >&2
    exit 1
    ;;
esac
