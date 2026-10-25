#!/usr/bin/env bash
# Print the path of a pinned tool, installing it into .tools/ on first use. Every download is checked against the
# SHA-256 recorded here, so a replaced upstream asset stops the run instead of executing.
# Release binaries rather than container images keep linting independent of Docker and of Docker Hub's pull limits.
# Only the path goes to stdout; progress goes to stderr.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool="${1:?usage: scripts/tool.sh actionlint|shellcheck|cargo-deny|cargo-ndk|android-ndk}"

case "$(uname -sm)" in
  "Linux x86_64") platform=linux-x86_64 ;;
  "Linux aarch64") platform=linux-aarch64 ;;
  "Darwin x86_64") platform=darwin-x86_64 ;;
  "Darwin arm64") platform=darwin-aarch64 ;;
  *)
    echo "no pinned $tool build for $(uname -sm)" >&2
    exit 1
    ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Download $1 to $3 and stop unless its SHA-256 is $2.
fetch() {
  local got
  curl -fsSL "$1" -o "$3"
  if command -v sha256sum >/dev/null 2>&1; then
    got="$(sha256sum "$3")"
  else
    got="$(shasum -a 256 "$3")"
  fi
  if [ "${got%% *}" != "$2" ]; then
    echo "checksum mismatch for $1" >&2
    exit 1
  fi
}

# Move a finished download into place with a rename inside the target directory, so an interrupted copy never leaves
# a half-written tool behind.
settle() {
  mkdir -p "$(dirname "$2")"
  rm -rf "$2.partial"
  mv "$1" "$2.partial"
  mv "$2.partial" "$2"
}

# Install the archive member $3 of release archive $1 (SHA-256 $2) as executable $4.
install_binary() {
  if [ ! -x "$4" ]; then
    fetch "$1" "$2" "$tmp/archive"
    tar -xf "$tmp/archive" -C "$tmp"
    settle "$tmp/$3" "$4"
  fi
  echo "$4"
}

case "$tool" in
  actionlint)
    version=1.7.12
    case "$platform" in
      linux-x86_64) asset=linux_amd64 sha256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 ;;
      linux-aarch64) asset=linux_arm64 sha256=325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6 ;;
      darwin-x86_64) asset=darwin_amd64 sha256=5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644 ;;
      darwin-aarch64) asset=darwin_arm64 sha256=aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f ;;
    esac
    install_binary "https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_$asset.tar.gz" \
      "$sha256" actionlint "$repo_root/.tools/actionlint-$version/actionlint"
    ;;
  shellcheck)
    version=0.11.0
    case "$platform" in
      linux-x86_64) sha256=8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198 ;;
      linux-aarch64) sha256=12b331c1d2db6b9eb13cfca64306b1b157a86eb69db83023e261eaa7e7c14588 ;;
      darwin-x86_64) sha256=3c89db4edcab7cf1c27bff178882e0f6f27f7afdf54e859fa041fca10febe4c6 ;;
      darwin-aarch64) sha256=56affdd8de5527894dca6dc3d7e0a99a873b0f004d7aabc30ae407d3f48b0a79 ;;
    esac
    install_binary \
      "https://github.com/koalaman/shellcheck/releases/download/v$version/shellcheck-v$version.${platform/-/.}.tar.xz" \
      "$sha256" "shellcheck-v$version/shellcheck" "$repo_root/.tools/shellcheck-$version/shellcheck"
    ;;
  cargo-deny)
    version=0.20.2
    case "$platform" in
      linux-x86_64) triple=x86_64-unknown-linux-musl sha256=9f12ed4c49936e09b48bf862b595cde2fe64fcbd9d74dfacac6131ca824c8d5f ;;
      linux-aarch64) triple=aarch64-unknown-linux-musl sha256=995c82be0defc7a025cae49a2aa2644ce8245c9a3318fc4103907c6a285e8c7d ;;
      darwin-x86_64) triple=x86_64-apple-darwin sha256=248da7f581724e470071990c088ffc55c811981715f4cbdb258621fb79f8b7a6 ;;
      darwin-aarch64) triple=aarch64-apple-darwin sha256=fe67d82a10d8597a3549364cb733a3f9cc1bfff9031b7ae46384a9f2a72090c3 ;;
    esac
    install_binary \
      "https://github.com/EmbarkStudios/cargo-deny/releases/download/$version/cargo-deny-$version-$triple.tar.gz" \
      "$sha256" "cargo-deny-$version-$triple/cargo-deny" "$repo_root/.tools/cargo-deny-$version/cargo-deny"
    ;;
  cargo-ndk)
    # cargo-ndk publishes no release binaries; cargo builds it from crates.io and checks each crate's checksum.
    version=4.1.2
    root="$repo_root/.tools/cargo-ndk-$version"
    if [ ! -x "$root/bin/cargo-ndk" ]; then
      cargo install --quiet --locked --version "$version" --root "$tmp/cargo-ndk" cargo-ndk >&2
      settle "$tmp/cargo-ndk" "$root"
    fi
    echo "$root/bin/cargo-ndk"
    ;;
  android-ndk)
    # r28c. An NDK of this revision installed through the Android SDK (GitHub's Ubuntu runners carry several) saves the
    # 700 MB download; otherwise Linux fetches Google's archive, whose SHA-1 in the SDK index matches this SHA-256.
    revision=28.2.13676358
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
      if [ -n "$sdk" ] && [ -f "$sdk/ndk/$revision/source.properties" ]; then
        echo "$sdk/ndk/$revision"
        exit 0
      fi
    done
    if [ "$platform" != linux-x86_64 ]; then
      echo "install NDK $revision with the Android SDK manager and set ANDROID_HOME" >&2
      exit 1
    fi
    dir="$repo_root/.tools/android-ndk-r28c"
    if [ ! -f "$dir/source.properties" ]; then
      echo "Downloading Android NDK r28c (700 MB)" >&2
      fetch https://dl.google.com/android/repository/android-ndk-r28c-linux.zip \
        dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f "$tmp/ndk.zip"
      unzip -q "$tmp/ndk.zip" -d "$tmp"
      settle "$tmp/android-ndk-r28c" "$dir"
    fi
    echo "$dir"
    ;;
  *)
    echo "unknown tool: $tool" >&2
    exit 1
    ;;
esac
