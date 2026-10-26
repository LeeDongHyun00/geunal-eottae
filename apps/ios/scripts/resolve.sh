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

if [ -f "$committed" ]; then
  cp "$committed" "$resolved"
  xcodebuild -resolvePackageDependencies -onlyUsePackageVersionsFromResolvedFile \
    -project "$project" -scheme "$scheme" -derivedDataPath "$derived_data" >&2
else
  xcodebuild -resolvePackageDependencies -project "$project" -scheme "$scheme" -derivedDataPath "$derived_data" >&2
fi

if [ ! -f "$committed" ]; then
  # Bootstrapping the lockfile: Xcode's first resolution is the one to commit.
  echo "::warning::apps/ios/Package.resolved is missing; commit the file printed below"
  cat "$resolved"
  exit 0
fi

if ! python3 -I "$ios_dir/scripts/pins.py" "$committed" "$resolved"; then
  echo "Package.resolved Xcode settled on:" >&2
  cat "$resolved" >&2
  echo "Commit it as apps/ios/Package.resolved (make lock-ios on a Mac writes it)." >&2
  exit 1
fi
