#!/usr/bin/env bash
# Trivy on what the apps ship (docs/security/dependencies.md): the libraries in the Android release APK and the iOS
# Swift packages. Fails on HIGH or CRITICAL findings that have a fix, like the other Trivy gates.
# The Gradle lock files also lock the build tools (the plugin classpath, Android Lint). Their findings never reach a
# device, so they are listed for the record without failing; Dependabot's Gradle updates bring their fixes.
# Usage: scripts/ci/audit-apps.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
trivy="$(scripts/tool.sh trivy)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Each lock file line is `group:name:version=configuration,...`. The app module's releaseRuntimeClasspath is what goes
# into the release APK, the library modules' runtime dependencies included.
lock=apps/android/app/gradle.lockfile
if [ -f "$lock" ]; then
  mkdir -p "$work/android"
  grep -E '=([^=]*,)?releaseRuntimeClasspath(,|$)' "$lock" >"$work/android/gradle.lockfile" || true
  # A renamed configuration would leave nothing to scan and pass in silence.
  if [ ! -s "$work/android/gradle.lockfile" ]; then
    echo "$lock has no releaseRuntimeClasspath entries; update scripts/ci/audit-apps.sh" >&2
    exit 1
  fi
fi
if [ -f apps/ios/Package.resolved ]; then
  mkdir -p "$work/ios"
  cp apps/ios/Package.resolved "$work/ios/"
fi

echo "==> Every Gradle lock file, build tools included (for the record, not a gate)"
"$trivy" fs --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed apps/android

echo "==> What the apps ship"
"$trivy" fs --quiet --scanners vuln --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$work"
