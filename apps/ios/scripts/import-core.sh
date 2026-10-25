#!/usr/bin/env bash
# Copy the core's iOS outputs (make build-ios, core/out/ios) into the GeunalCore package: the XCFramework and the Swift
# UniFFI generated. The package's binary target has to live inside the package, and the app never reads core/ directly.
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

out="$repo_root/core/out/ios"
package="$ios_dir/Packages/GeunalCore"

if [ ! -d "$out/GeunalCoreFFI.xcframework" ] || [ ! -f "$out/swift/GeunalCore.swift" ]; then
  echo "core/out/ios is missing the XCFramework or GeunalCore.swift; run make build-ios first" >&2
  exit 1
fi

rm -rf "$package/GeunalCoreFFI.xcframework"
cp -R "$out/GeunalCoreFFI.xcframework" "$package/GeunalCoreFFI.xcframework"
cp "$out/swift/GeunalCore.swift" "$package/Sources/GeunalCore/GeunalCore.swift"
echo "imported the core into $package"
