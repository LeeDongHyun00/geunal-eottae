#!/usr/bin/env bash
# Resolve the Swift packages with the versions apps/ios/Package.resolved pins: the Kakao SDK and what it brings
# (Alamofire). The pinned file is copied into the generated project before resolving; afterwards the pins Xcode
# settled on must be the committed ones, so a dependency never moves without a reviewed lockfile change.
# `--update` writes Xcode's result back to apps/ios/Package.resolved instead (make lock-ios).
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_macos
use_pinned_xcode

committed="$ios_dir/Package.resolved"
workspace="$project/project.xcworkspace/xcshareddata/swiftpm"
resolved="$workspace/Package.resolved"
mkdir -p "$workspace"

if [ "${1:-}" = --update ]; then
  rm -f "$resolved"
  xcodebuild -resolvePackageDependencies -project "$project" -scheme "$scheme" -derivedDataPath "$derived_data" >&2
  cp "$resolved" "$committed"
  echo "wrote $committed"
  exit 0
fi

if [ ! -f "$committed" ]; then
  echo "apps/ios/Package.resolved is missing: run make lock-ios on a Mac and commit it" >&2
  exit 1
fi

cp "$committed" "$resolved"
xcodebuild -resolvePackageDependencies -onlyUsePackageVersionsFromResolvedFile \
  -project "$project" -scheme "$scheme" -derivedDataPath "$derived_data" >&2

if ! python3 -I "$ios_dir/scripts/pins.py" "$committed" "$resolved"; then
  echo "Package.resolved Xcode settled on:" >&2
  cat "$resolved" >&2
  echo "Commit it as apps/ios/Package.resolved (make lock-ios on a Mac writes it)." >&2
  exit 1
fi
