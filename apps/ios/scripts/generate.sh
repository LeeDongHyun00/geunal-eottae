#!/usr/bin/env bash
# Generate GeunalApp.xcodeproj from project.yml with the XcodeGen release scripts/tool.sh pins.
set -euo pipefail

# shellcheck source=apps/ios/scripts/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_macos

"$("$repo_root/scripts/tool.sh" xcodegen)" generate --spec "$ios_dir/project.yml" --project "$ios_dir" --quiet
echo "generated $project"
