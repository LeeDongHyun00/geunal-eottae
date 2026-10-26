#!/usr/bin/env bash
# Build the OpenSSL libcrypto that SQLCipher links on Android for one ABI, and print its install directory
# (docs/parts/rust-core.md §7). Only what SQLCipher calls stays in: AES-256-CBC, SHA-1/256/512, HMAC, PBKDF2 and the
# random generator. The crate's vendored OpenSSL builds everything else too, about 3 MB of each 8 MB library.
#
#   core/scripts/build-openssl.sh <arm64-v8a|armeabi-v7a|x86_64> <android api>   build (once) and print the directory
#   core/scripts/build-openssl.sh --check                                          fail when a newer patch release exists
#
# The source is checked against the SHA-256 published with the release. Each ABI's build lands in .tools/openssl/<abi>
# with a key of everything that shaped it (key file); a change here rebuilds it. libsqlite3-sys does not notice a new
# OpenSSL by itself, so build-android.sh compares the key around this call. Progress goes to stderr.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version=3.6.3
sha256=243a86649cf6f23eeb6a2ff2456e09e5d77dd9018a54d3d96b0c6bdd6ba6c7f1
# The last day upstream fixes this series (openssl-library.org/policies/releasestrat). After it no patch comes, so a
# newer patch can no longer be the signal.
supported_until=2026-11-01
url="https://github.com/openssl/openssl/releases/download/openssl-$version/openssl-$version.tar.gz"

# Security fixes arrive as patch releases of a series, and RustSec no longer sees this OpenSSL, so the weekly audit
# asks upstream for a newer patch instead, and fails once the series is out of support (docs/security/dependencies.md).
if [ "${1:-}" = "--check" ]; then
  if [[ "$(date -u +%F)" > "$supported_until" ]]; then
    echo "OpenSSL ${version%.*} has had no fixes since $supported_until; move core/scripts/build-openssl.sh to a supported series." >&2
    exit 1
  fi
  series="${version%.*}"
  newest="$(git ls-remote --tags --refs https://github.com/openssl/openssl.git "refs/tags/openssl-$series.*" |
    sed -n "s|.*refs/tags/openssl-\($series\.[0-9][0-9]*\)$|\1|p" | sort -V | tail -1)"
  if [ -z "$newest" ]; then
    echo "could not list the OpenSSL $series releases" >&2
    exit 1
  fi
  if [ "$newest" != "$version" ]; then
    echo "OpenSSL $newest is out; core/scripts/build-openssl.sh pins $version. Check its advisories and bump." >&2
    exit 1
  fi
  echo "OpenSSL $version is the newest $series release" >&2
  exit 0
fi

abi="${1:?usage: core/scripts/build-openssl.sh arm64-v8a|armeabi-v7a|x86_64 <android api> | --check}"
api="${2:?usage: core/scripts/build-openssl.sh <abi> <android api>}"
case "$abi" in
  arm64-v8a) target=android-arm64 ;;
  armeabi-v7a) target=android-arm ;;
  x86_64) target=android-x86_64 ;;
  *)
    echo "no OpenSSL target for ABI $abi" >&2
    exit 1
    ;;
esac

# Everything SQLCipher does not call is off. Its provider uses EVP ciphers, EVP_MAC HMAC, PKCS5_PBKDF2_HMAC and
# RAND_bytes; the error strings go too, since it logs only error codes. Assembly stays on, so AES and SHA keep the
# CPU's instructions and table-free code paths.
options=(
  no-shared no-module no-tests no-docs no-apps no-legacy no-engine no-dso no-comp no-zlib no-async
  no-autoload-config no-ui-console no-stdio no-sock no-http no-quic no-multiblock
  no-ec no-ec2m no-sm2 no-sm3 no-sm4 no-dh no-dsa no-chacha no-poly1305 no-siphash no-blake2
  no-camellia no-aria no-bf no-cast no-des no-idea no-md4 no-mdc2 no-rc2 no-rc4 no-rc5 no-rmd160 no-seed
  no-whirlpool no-scrypt no-ocb no-siv no-gost no-cms no-ocsp no-ts no-ct no-srp no-psk no-cmac no-ecx
  no-ml-kem no-ml-dsa no-slh-dsa no-lms no-argon2 no-err no-deprecated no-filenames no-uplink no-padlockeng
  no-afalgeng no-thread-pool no-default-thread-pool no-rfc3779
)
# Position-independent for the shared library it ends up in; one section per function so the final link drops what
# SQLCipher does not reach; -Oz because the C code sits outside the Rust LTO.
cflags=(-fPIC -ffunction-sections -fdata-sections -Oz)

# macOS ships shasum instead of sha256sum.
digest() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

ndk="$("$repo/scripts/tool.sh" android-ndk)"
key="$(printf '%s\n' "$version" "$sha256" "$api" "$ndk" "${options[@]}" "${cflags[@]}" | digest | cut -c1-12)"
out="$repo/.tools/openssl/$abi"
if [ -f "$out/lib/libcrypto.a" ] && [ "$(cat "$out/key" 2>/dev/null)" = "$key" ]; then
  echo "$out"
  exit 0
fi

archive="$repo/.tools/downloads/openssl-$version.tar.gz"
check() {
  local got
  got="$(digest "$1")"
  [ "${got%% *}" = "$sha256" ]
}
if [ ! -f "$archive" ] || ! check "$archive"; then
  mkdir -p "$(dirname "$archive")"
  echo "downloading OpenSSL $version" >&2
  curl -fsSL "$url" -o "$archive.partial"
  if ! check "$archive.partial"; then
    rm -f "$archive.partial"
    echo "checksum mismatch for $url" >&2
    exit 1
  fi
  mv "$archive.partial" "$archive"
fi

work="$(mktemp -d "$repo/.tools/openssl-build.XXXXXX")"
trap 'rm -rf "$work"' EXIT
tar -xzf "$archive" -C "$work" --strip-components=1
log="$work/build.log"
bin=("$ndk"/toolchains/llvm/prebuilt/*/bin)
echo "building OpenSSL $version for $abi" >&2
(
  cd "$work"
  export ANDROID_NDK_ROOT="$ndk"
  export PATH="${bin[0]}:$PATH"
  jobs="$(getconf _NPROCESSORS_ONLN)"
  ./Configure "$target" "-D__ANDROID_API__=$api" "${options[@]}" "${cflags[@]}"
  # libcrypto alone: libssl is never linked.
  make -j"$jobs" build_generated
  make -j"$jobs" libcrypto.a
) >"$log" 2>&1 || {
  tail -40 "$log" >&2
  exit 1
}

# Headers from the tree (the generated ones included) and the static library are all libsqlite3-sys needs.
rm -rf "$out.partial"
mkdir -p "$out.partial/include" "$out.partial/lib"
cp -R "$work/include/openssl" "$out.partial/include/"
cp "$work/libcrypto.a" "$out.partial/lib/"
echo "$key" >"$out.partial/key"
rm -rf "$out"
mv "$out.partial" "$out"
echo "$out"
