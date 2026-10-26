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
# into the release APK, the library modules' runtime dependencies included. A moved module, a lock turned off or a
# renamed configuration would leave nothing to scan and pass in silence, so each of them fails instead.
lock=apps/android/app/gradle.lockfile
if [ -d apps/android ]; then
  if [ ! -f "$lock" ]; then
    echo "$lock is missing; update scripts/ci/audit-apps.sh" >&2
    exit 1
  fi
  mkdir -p "$work/android"
  grep -E '=([^=]*,)?releaseRuntimeClasspath(,|$)' "$lock" >"$work/android/gradle.lockfile" || true
  if [ ! -s "$work/android/gradle.lockfile" ]; then
    echo "$lock has no releaseRuntimeClasspath entries; update scripts/ci/audit-apps.sh" >&2
    exit 1
  fi
fi

# The remote Swift packages of the iOS app are pinned in one file, which must exist once a manifest names one.
resolved=apps/ios/Package.resolved
if grep -qs '\.package(url:' apps/ios/Packages/*/Package.swift && [ ! -f "$resolved" ]; then
  echo "$resolved is missing while apps/ios/Packages use remote packages" >&2
  exit 1
fi
if [ -f "$resolved" ]; then
  mkdir -p "$work/ios"
  cp "$resolved" "$work/ios/"
fi

if [ -d apps/android ]; then
  echo "==> Every Gradle lock file, build tools included (for the record, not a gate)"
  "$trivy" fs --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed apps/android
fi

echo "==> What the apps ship"
"$trivy" fs --quiet --scanners vuln --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$work"
