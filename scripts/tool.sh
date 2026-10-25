#!/usr/bin/env bash
# Print the path of a pinned tool, downloading it into .tools/ on first use. Every release file is checked against the
# SHA-256 recorded here, so a replaced upstream asset stops the run instead of executing.
# Release binaries rather than container images keep linting independent of Docker and of Docker Hub's pull limits.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool="${1:?usage: scripts/tool.sh actionlint|shellcheck}"

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

case "$tool" in
  actionlint)
    version=1.7.12
    case "$platform" in
      linux-x86_64) asset=linux_amd64 sha256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 ;;
      linux-aarch64) asset=linux_arm64 sha256=325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6 ;;
      darwin-x86_64) asset=darwin_amd64 sha256=5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644 ;;
      darwin-aarch64) asset=darwin_arm64 sha256=aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f ;;
    esac
    url="https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_$asset.tar.gz"
    member=actionlint
    ;;
  shellcheck)
    version=0.11.0
    case "$platform" in
      linux-x86_64) sha256=8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198 ;;
      linux-aarch64) sha256=12b331c1d2db6b9eb13cfca64306b1b157a86eb69db83023e261eaa7e7c14588 ;;
      darwin-x86_64) sha256=3c89db4edcab7cf1c27bff178882e0f6f27f7afdf54e859fa041fca10febe4c6 ;;
      darwin-aarch64) sha256=56affdd8de5527894dca6dc3d7e0a99a873b0f004d7aabc30ae407d3f48b0a79 ;;
    esac
    url="https://github.com/koalaman/shellcheck/releases/download/v$version/shellcheck-v$version.${platform/-/.}.tar.xz"
    member="shellcheck-v$version/shellcheck"
    ;;
  *)
    echo "unknown tool: $tool" >&2
    exit 1
    ;;
esac

bin="$repo_root/.tools/$tool-$version/$tool"
if [ ! -x "$bin" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL "$url" -o "$tmp/archive"
  if command -v sha256sum >/dev/null 2>&1; then
    got="$(sha256sum "$tmp/archive")"
  else
    got="$(shasum -a 256 "$tmp/archive")"
  fi
  if [ "${got%% *}" != "$sha256" ]; then
    echo "checksum mismatch for $url" >&2
    exit 1
  fi
  tar -xf "$tmp/archive" -C "$tmp"
  mkdir -p "$(dirname "$bin")"
  # Rename inside the target directory so an interrupted copy never leaves a half-written executable behind.
  mv "$tmp/$member" "$bin.partial"
  mv "$bin.partial" "$bin"
fi
echo "$bin"
