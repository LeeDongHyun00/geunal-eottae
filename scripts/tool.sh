#!/usr/bin/env bash
# Print the path of a pinned tool, installing it into .tools/ on first use. Every download is checked against the
# SHA-256 recorded here, so a replaced upstream asset stops the run instead of executing.
# Release binaries rather than container images keep linting independent of Docker and of Docker Hub's pull limits.
# Only the path goes to stdout; progress goes to stderr.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool="${1:?usage: scripts/tool.sh actionlint|shellcheck|cargo-deny|trivy|cargo-ndk|android-ndk|android-sdk}"

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
  trivy)
    version=0.76.1
    case "$platform" in
      linux-x86_64) asset=Linux-64bit sha256=c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0 ;;
      linux-aarch64) asset=Linux-ARM64 sha256=e174dff2a6dba5e3ae41f8d0537a6324405bd08132497340304c6b20e0f16a1f ;;
      darwin-x86_64) asset=macOS-64bit sha256=7aa762eacd582052d053b62dcf972a1ab058abe39a57ccae234ed8894945b6b7 ;;
      darwin-aarch64) asset=macOS-ARM64 sha256=11a9b6cd2752011b54f01ea4bd0dbfa9f0c41dc6538d5a3f9d23955f471bf4e5 ;;
    esac
    install_binary "https://github.com/aquasecurity/trivy/releases/download/v$version/trivy_${version}_$asset.tar.gz" \
      "$sha256" trivy "$repo_root/.tools/trivy-$version/trivy"
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
  android-sdk)
    # The SDK packages the Android app builds with (docs/parts/android.md §1, §6), as Google's own zips pinned by
    # SHA-256 (the SDK index publishes matching SHA-1s). sdkmanager would install the newest revision of each package
    # instead, and AGP's own download does the same, so gradle.properties turns that off. When ANDROID_HOME or
    # ANDROID_SDK_ROOT points at an SDK (GitHub's runners, a developer's machine), the missing packages go there, so the
    # emulator's adb and the build's adb are the same binary; otherwise the SDK lives in .tools/android-sdk.
    # AGP strips the core's native libraries with the NDK in ndk/<revision>, so the pinned NDK is linked in too.
    # Android 17 ships only as minor-versioned platforms (37.0, 37.1, ...); 37.0 is the base of API 37.
    platform_api=37.0
    build_tools=36.0.0
    platform_tools=37.0.1
    ndk_revision=28.2.13676358
    case "$platform" in
      linux-x86_64)
        build_tools_asset=linux build_tools_sha256=5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b
        platform_tools_asset=linux platform_tools_sha256=d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1
        ;;
      darwin-x86_64 | darwin-aarch64)
        build_tools_asset=macosx build_tools_sha256=04e7f3a72044de4926fa038fa0e251a37bba1e1c3fb8beab6f8401bfd9eb4bf3
        platform_tools_asset=darwin platform_tools_sha256=ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d
        ;;
      *)
        echo "install platform $platform_api, build-tools $build_tools and the NDK with the Android SDK manager and set ANDROID_HOME" >&2
        exit 1
        ;;
    esac
    sdk=""
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
      if [ -n "$candidate" ] && [ -d "$candidate" ]; then
        sdk="$candidate"
        break
      fi
    done
    [ -n "$sdk" ] || sdk="$repo_root/.tools/android-sdk"
    # Install the zip $1 (SHA-256 $2), whose single top-level directory is named $3, as $4.
    install_sdk_package() {
      if [ ! -f "$4/source.properties" ]; then
        echo "Downloading $(basename "$1")" >&2
        fetch "https://dl.google.com/android/repository/$1" "$2" "$tmp/package.zip"
        unzip -q "$tmp/package.zip" -d "$tmp/package"
        settle "$tmp/package/$3" "$4"
        rm -rf "$tmp/package"
      fi
    }
    install_sdk_package "platform-${platform_api}_r02.zip" \
      840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d "android-$platform_api" \
      "$sdk/platforms/android-$platform_api"
    # The zip carries only legacy source.properties, whose "37.0" API level AGP's loader cannot read, so the platform
    # gets the package.xml that sdkmanager would have written (the index entry minus the download details).
    platform_dir="$sdk/platforms/android-$platform_api"
    if [ ! -f "$platform_dir/package.xml" ]; then
      cat >"$platform_dir/package.xml" <<XML
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns3="http://schemas.android.com/sdk/android/repo/repository2/03">
  <localPackage path="platforms;android-$platform_api" obsolete="false">
    <type-details xsi:type="ns3:platformDetailsType" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <api-level>$platform_api</api-level>
      <codename></codename>
      <extension-level>22</extension-level>
      <base-extension>true</base-extension>
      <layoutlib api="15"/>
    </type-details>
    <revision>
      <major>2</major>
    </revision>
    <display-name>Android SDK Platform $platform_api</display-name>
  </localPackage>
</ns2:repository>
XML
    fi
    # Google names the build-tools directory inside the zip after the Android release (16), not the API level.
    install_sdk_package "build-tools_r${build_tools%.0.0}_$build_tools_asset.zip" "$build_tools_sha256" android-16 \
      "$sdk/build-tools/$build_tools"
    install_sdk_package "platform-tools_r$platform_tools-$platform_tools_asset.zip" "$platform_tools_sha256" \
      platform-tools "$sdk/platform-tools"
    if [ ! -f "$sdk/ndk/$ndk_revision/source.properties" ]; then
      ndk="$(ANDROID_HOME="$sdk" "$repo_root/scripts/tool.sh" android-ndk)"
      mkdir -p "$sdk/ndk"
      ln -sfn "$ndk" "$sdk/ndk/$ndk_revision"
    fi
    echo "$sdk"
    ;;
  *)
    echo "unknown tool: $tool" >&2
    exit 1
    ;;
esac
