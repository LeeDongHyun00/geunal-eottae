#!/usr/bin/env bash
# Build geunal-ffi for the three Android ABIs and generate its Kotlin bindings (docs/parts/rust-core.md §7).
# Writes core/out/android/jniLibs/<abi>/libgeunal_ffi.so, core/out/android/kotlin/ and core/out/android/sizes.md.
set -euo pipefail

core="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(dirname "$core")"
out="$core/out/android"
abis=(arm64-v8a armeabi-v7a x86_64)
# 8 MB per ABI after stripping (rust-core.md §7). The budget binds from M1, so M0 only warns.
budget=$((8 * 1024 * 1024))

ndk="$("$repo/scripts/tool.sh" android-ndk)"
cargo_ndk="$("$repo/scripts/tool.sh" cargo-ndk)"

# rustup takes the toolchain from core/rust-toolchain.toml by working directory.
cd "$core"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
rm -rf "$out"

targets=()
for abi in "${abis[@]}"; do targets+=(--target "$abi"); done
# cargo-ndk only runs as a cargo subcommand, so the pinned binary goes first on PATH. minSdk 26 (docs/parts/android.md §1).
PATH="$(dirname "$cargo_ndk"):$PATH" ANDROID_NDK_HOME="$ndk" cargo ndk "${targets[@]}" --platform 26 \
  --output-dir "$out/jniLibs" build --locked --release -p geunal-ffi

cargo run --locked -q -p uniffi-bindgen -- generate --no-format --language kotlin \
  --library "$out/jniLibs/arm64-v8a/libgeunal_ffi.so" --out-dir "$out/kotlin"

# The libraries keep their symbols because uniffi-bindgen reads its metadata from them; the Android build strips
# them when it packages the app, so the budget applies to a stripped copy.
strip=("$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
{
  echo "| ABI | stripped size |"
  echo "| --- | --- |"
  for abi in "${abis[@]}"; do
    "${strip[0]}" --strip-all -o "$tmp/$abi.so" "$out/jniLibs/$abi/libgeunal_ffi.so"
    size=$(($(wc -c <"$tmp/$abi.so")))
    printf '| %s | %s |\n' "$abi" "$(awk -v b="$size" 'BEGIN { printf "%.2f MB", b / 1048576 }')"
    if [ "$size" -gt "$budget" ]; then
      message="libgeunal_ffi.so for $abi is over the 8 MB budget"
      if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::$message" >&2; else echo "warning: $message" >&2; fi
    fi
  done
} >"$out/sizes.md"
cat "$out/sizes.md"
