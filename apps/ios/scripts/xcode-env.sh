#!/usr/bin/env bash
# Print `DEVELOPER_DIR=<path>` for the Xcode release apps/ios/.xcode-version pins, in the form $GITHUB_ENV takes.
# GitHub's macOS runners carry several releases as /Applications/Xcode_<version>.app; a developer's Mac usually has one
# Xcode.app, which passes when its version matches. Anything else stops here rather than building with a stray Xcode.
set -euo pipefail

ios_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
want="$(tr -d '[:space:]' <"$ios_dir/.xcode-version")"

if [ "$(uname -s)" != Darwin ]; then
  echo "Xcode $want is needed; this environment has no macOS" >&2
  exit 1
fi

candidates=("/Applications/Xcode_$want.app" /Applications/Xcode.app)
if [ -n "${DEVELOPER_DIR:-}" ]; then
  candidates+=("${DEVELOPER_DIR%/Contents/Developer}")
fi

for app in "${candidates[@]}"; do
  [ -f "$app/Contents/Info.plist" ] || continue
  version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist" 2>/dev/null || true)"
  if [ "$version" = "$want" ]; then
    echo "DEVELOPER_DIR=$app/Contents/Developer"
    exit 0
  fi
done

echo "Xcode $want (apps/ios/.xcode-version) is not installed. Installed:" >&2
ls -d /Applications/Xcode*.app >&2 || true
exit 1
