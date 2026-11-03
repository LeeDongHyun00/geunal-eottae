#!/usr/bin/env bash
# Build geunal-ffi for one ABI with a link map, against the trimmed OpenSSL, in a separate target dir.
# usage: map-build.sh <abi>
set -euo pipefail
abi="$1"
repo="$(git rev-parse --show-toplevel)"
scratch="${SCRATCH:-$repo/core/target/size}"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  armeabi-v7a) triple=armv7-linux-androideabi ;;
  x86_64) triple=x86_64-linux-android ;;
esac
ndk="$("$repo/scripts/tool.sh" android-ndk)"
cargo_ndk="$("$repo/scripts/tool.sh" cargo-ndk)"
dir="$("$repo/core/scripts/build-openssl.sh" "$abi" 26)"
var="$(echo "$triple" | tr '[:lower:]-' '[:upper:]_')_OPENSSL_DIR"
export "$var=$dir"
map="$scratch/maps/$abi.map"
out="$scratch/maps/out-$abi"
rm -rf "$out"
cd "$repo/core"
CARGO_TARGET_DIR="$scratch/maps/target" PATH="$(dirname "$cargo_ndk"):$PATH" ANDROID_NDK_HOME="$ndk" \
  cargo ndk --target "$abi" --platform 26 --output-dir "$out" build --locked --release -p geunal-ffi \
  --config "target.$triple.rustflags=[\"-C\",\"link-arg=-Wl,--pack-dyn-relocs=android\",\"-C\",\"link-arg=-Wl,-Map=$map\"]" \
  >"$scratch/maps/$abi.log" 2>&1 || { tail -30 "$scratch/maps/$abi.log"; exit 1; }
bin=("$ndk"/toolchains/llvm/prebuilt/*/bin)
"${bin[0]}/llvm-strip" --strip-all -o "$out/stripped.so" "$out/$abi/libgeunal_ffi.so"
printf '%s stripped %.2f MB\n' "$abi" "$(awk -v b="$(wc -c <"$out/stripped.so")" 'BEGIN { print b / 1048576 }')"
"${bin[0]}/llvm-size" -A "$out/stripped.so" | awk '$2 > 20000 { printf "    %-20s %7.2f MB\n", $1, $2 / 1048576 }'
