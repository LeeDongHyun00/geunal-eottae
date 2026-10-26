#!/usr/bin/env bash
# Build geunal-ffi for the three Android ABIs and generate its Kotlin bindings (docs/parts/rust-core.md §7).
# Writes core/out/android/jniLibs/<abi>/libgeunal_ffi.so, core/out/android/kotlin/ and core/out/android/sizes.md.
set -euo pipefail

core="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(dirname "$core")"
out="$core/out/android"
abis=(arm64-v8a armeabi-v7a x86_64)
# 8 MB after stripping for each ABI phones download (rust-core.md §7); over it the build fails. Play hands a device only
# its own ABI, and x86_64 runs on emulators and x86 Chromebooks, so its size is only recorded.
budget=$((8 * 1024 * 1024))

ndk="$("$repo/scripts/tool.sh" android-ndk)"
cargo_ndk="$("$repo/scripts/tool.sh" cargo-ndk)"

# rustup takes the toolchain from core/rust-toolchain.toml by working directory.
cd "$core"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
rm -rf "$out"

# minSdk 26 (docs/parts/android.md §1), for both the Rust code and the OpenSSL below.
platform=26

# SQLCipher links a libcrypto trimmed to what it calls (core/scripts/build-openssl.sh). libsqlite3-sys reads the
# OpenSSL location per target, so each ABI gets its own. It does not rerun its build script when that OpenSSL changes,
# but its build output names the directory it linked, and every OpenSSL build has its own directory. Output naming
# another one is stale, whether an earlier run, a standalone build-openssl.sh or a restored cache left it, so the
# target's libsqlite3-sys is cleaned; otherwise SQLCipher would keep the previous headers and library. Should a
# libsqlite3-sys release word its output differently, nothing matches and it is rebuilt every time, slower but right.
for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;;
  esac
  dir="$("$core/scripts/build-openssl.sh" "$abi" "$platform")"
  for output in "${CARGO_TARGET_DIR:-$core/target}/$triple/release/build/"libsqlite3-sys-*/output; do
    if [ -f "$output" ] && ! grep -qxF "cargo:rustc-link-search=$dir/lib" "$output"; then
      cargo clean --release --target "$triple" -p libsqlite3-sys
      break
    fi
  done
  var="$(echo "$triple" | tr '[:lower:]-' '[:upper:]_')_OPENSSL_DIR"
  export "$var=$dir"
done

targets=()
for abi in "${abis[@]}"; do targets+=(--target "$abi"); done
# cargo-ndk only runs as a cargo subcommand, so the pinned binary goes first on PATH.
PATH="$(dirname "$cargo_ndk"):$PATH" ANDROID_NDK_HOME="$ndk" cargo ndk "${targets[@]}" --platform "$platform" \
  --output-dir "$out/jniLibs" build --locked --release -p geunal-ffi

cargo run --locked -q -p uniffi-bindgen -- generate --no-format --language kotlin \
  --library "$out/jniLibs/arm64-v8a/libgeunal_ffi.so" --out-dir "$out/kotlin"

# The libraries keep their symbols because uniffi-bindgen reads its metadata from them; the Android build strips
# them when it packages the app, so the budget applies to a stripped copy.
strip=("$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
over=()
{
  echo "| ABI | stripped size | budget |"
  echo "| --- | --- | --- |"
  for abi in "${abis[@]}"; do
    "${strip[0]}" --strip-all -o "$tmp/$abi.so" "$out/jniLibs/$abi/libgeunal_ffi.so"
    size=$(($(wc -c <"$tmp/$abi.so")))
    case "$abi" in
      arm64-v8a | armeabi-v7a)
        limit="8 MB"
        if [ "$size" -gt "$budget" ]; then over+=("$abi"); fi
        ;;
      *) limit="recorded only" ;;
    esac
    printf '| %s | %s | %s |\n' "$abi" "$(awk -v b="$size" 'BEGIN { printf "%.2f MB", b / 1048576 }')" "$limit"
  done
} >"$out/sizes.md"
cat "$out/sizes.md"
# The table above is written first, so a failing build still shows every size.
if [ "${#over[@]}" -gt 0 ]; then
  for abi in "${over[@]}"; do
    message="libgeunal_ffi.so for $abi is over the 8 MB budget"
    if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::$message" >&2; else echo "error: $message" >&2; fi
  done
  exit 1
fi
