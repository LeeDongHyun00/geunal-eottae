#!/usr/bin/env bash
# Build geunal-ffi for iOS devices and the Apple-silicon simulator, generate the Swift bindings and wrap both static
# libraries in an XCFramework (docs/parts/rust-core.md §7). Needs macOS with Xcode.
# Writes core/out/ios/GeunalCoreFFI.xcframework and core/out/ios/swift/.
set -euo pipefail

if [ "$(uname -s)" != Darwin ]; then
  echo "build-ios needs macOS with Xcode; CI builds it on a macOS runner" >&2
  exit 1
fi

core="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$core/out/ios"
targets=(aarch64-apple-ios aarch64-apple-ios-sim)
# The apps support iOS 17 and later (docs/parts/ios.md §1).
export IPHONEOS_DEPLOYMENT_TARGET=17.0

# rustup takes the toolchain from core/rust-toolchain.toml by working directory.
cd "$core"
rustup target add "${targets[@]}"
rm -rf "$out"
mkdir -p "$out/headers"

for target in "${targets[@]}"; do
  cargo build --locked --release -p geunal-ffi --target "$target"
done

# uniffi-bindgen reads its metadata from a dynamic library, so the bindings come from the host build.
cargo build --locked --release -p geunal-ffi
cargo run --locked -q -p uniffi-bindgen -- generate --no-format --language swift \
  --library target/release/libgeunal_ffi.dylib --out-dir "$out/swift"
mv "$out/swift/GeunalCoreFFI.h" "$out/headers/"
mv "$out/swift/GeunalCoreFFI.modulemap" "$out/headers/module.modulemap"

xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libgeunal_ffi.a -headers "$out/headers" \
  -library target/aarch64-apple-ios-sim/release/libgeunal_ffi.a -headers "$out/headers" \
  -output "$out/GeunalCoreFFI.xcframework"
