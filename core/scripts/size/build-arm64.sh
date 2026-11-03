#!/usr/bin/env bash
# Builds geunal-ffi for arm64-v8a only, the way core/scripts/build-android.sh does, and prints the stripped size.
set -euo pipefail
repo="$(git rev-parse --show-toplevel)"
core=$repo/core
ndk="$("$repo/scripts/tool.sh" android-ndk)"
cargo_ndk="$("$repo/scripts/tool.sh" cargo-ndk)"
cd "$core"
dir="$("$core/scripts/build-openssl.sh" arm64-v8a 26)"
for output in "$core/target/aarch64-linux-android/release/build/"libsqlite3-sys-*/output; do
  if [ -f "$output" ] && ! grep -qxF "cargo:rustc-link-search=$dir/lib" "$output"; then
    cargo clean --release --target aarch64-linux-android -p libsqlite3-sys
    break
  fi
done
export AARCH64_LINUX_ANDROID_OPENSSL_DIR="$dir"
out="${1:-${SCRATCH:-$repo/core/target/size}/probe}"
mkdir -p "$out"
PATH="$(dirname "$cargo_ndk"):$PATH" ANDROID_NDK_HOME="$ndk" cargo ndk --target arm64-v8a --platform 26 \
  --output-dir "$out/jniLibs" build ${LOCKED---locked} --release -p geunal-ffi ${EXTRA_CARGO_ARGS:-}
strip=("$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
"${strip[0]}" --strip-all -o "$out/stripped.so" "$out/jniLibs/arm64-v8a/libgeunal_ffi.so"
size=$(($(wc -c <"$out/stripped.so")))
awk -v b="$size" 'BEGIN { printf "arm64-v8a stripped: %.3f MB (%d bytes)\n", b / 1048576, b }'
