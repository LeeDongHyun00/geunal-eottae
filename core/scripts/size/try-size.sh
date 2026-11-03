#!/usr/bin/env bash
# Build geunal-ffi for one Android ABI with extra cargo arguments and print the stripped size.
# usage: try-size.sh <abi> <label> [cargo --config args...]   (RUSTFLAGS-style env passes through)
set -euo pipefail
abi="$1"; label="$2"; shift 2
repo="$(git rev-parse --show-toplevel)"
core="$repo/core"
scratch="${SCRATCH:-$repo/core/target/size}"
ndk="$("$repo/scripts/tool.sh" android-ndk)"
cargo_ndk="$("$repo/scripts/tool.sh" cargo-ndk)"
out="$scratch/try/$label"
rm -rf "$out"; mkdir -p "$(dirname "$out")"
cd "$core"
PATH="$(dirname "$cargo_ndk"):$PATH" ANDROID_NDK_HOME="$ndk" cargo ndk --target "$abi" --platform 26 \
  --output-dir "$out" build --locked --release -p geunal-ffi "$@" >"$out.log" 2>&1 || { tail -30 "$out.log"; exit 1; }
bin=("$ndk"/toolchains/llvm/prebuilt/*/bin)
"${bin[0]}/llvm-strip" --strip-all -o "$out/stripped.so" "$out/$abi/libgeunal_ffi.so"
size=$(wc -c <"$out/stripped.so")
printf '%-28s %-12s %8.2f MB\n' "$label" "$abi" "$(awk -v b="$size" 'BEGIN { print b / 1048576 }')"
"${bin[0]}/llvm-size" -A "$out/stripped.so" | awk '$1 ~ /^\.(text|rodata|data\.rel\.ro|rela\.dyn|rel\.dyn|eh_frame|gcc_except_table)$/ { printf "    %-18s %7.2f MB\n", $1, $2 / 1048576 }'
