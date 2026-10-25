#!/usr/bin/env bash
# swift-format from the pinned Xcode over the app and the packages, with apps/ios/.swift-format as the rules. Strict,
# so a formatting difference fails like a lint finding. The generated GeunalCore.swift is not ours and is skipped.
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_macos
use_pinned_xcode

cd "$ios_dir"
files=()
while IFS= read -r file; do
  files+=("$file")
done < <(git ls-files 'GeunalApp/*.swift' 'GeunalAppTests/*.swift' 'GeunalAppUITests/*.swift' 'Packages/*.swift')

xcrun swift-format lint --strict --configuration .swift-format --parallel "${files[@]}"
echo "swift-format: ${#files[@]} files clean"

# The tokens' Swift is generated; a token change has to come with it.
tokens=Packages/DesignSystem/Sources/DesignSystem/Tokens.swift
if ! diff -u "$tokens" <(python3 -I scripts/gen-tokens.py); then
  echo "$tokens is out of date: run apps/ios/scripts/gen-tokens.py > apps/ios/$tokens" >&2
  exit 1
fi
echo "design tokens: $tokens matches docs/design/tokens.json"
