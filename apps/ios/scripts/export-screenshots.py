#!/usr/bin/env python3
"""Copy the screenshots the UI tests attach (`XCTAttachment`, named like the design PNGs) out of the exported result
bundle attachments into one folder, under their own names, for the CI artifact that the design session compares with
docs/design/screens."""
import json
import re
import shutil
import sys
from pathlib import Path


def attachments(node):
    if isinstance(node, dict):
        if "exportedFileName" in node:
            yield node
        for value in node.values():
            yield from attachments(value)
    elif isinstance(node, list):
        for value in node:
            yield from attachments(value)


def main(exported, target):
    exported, target = Path(exported), Path(target)
    manifest = exported / "manifest.json"
    if not manifest.exists():
        print("no attachment manifest in %s" % exported, file=sys.stderr)
        return 0
    target.mkdir(parents=True, exist_ok=True)
    count = 0
    for item in attachments(json.loads(manifest.read_text(encoding="utf-8"))):
        source = exported / item["exportedFileName"]
        if not source.exists() or source.suffix.lower() not in (".png", ".jpg", ".jpeg", ".heic"):
            continue
        name = item.get("suggestedHumanReadableName") or source.name
        # Xcode appends "_<index>_<UUID>" to the attachment's name.
        name = re.sub(r"_\d+_[0-9A-Fa-f-]{36}(?=\.\w+$)", "", name)
        shutil.copyfile(source, target / name)
        count += 1
    print("exported %d screenshots to %s" % (count, target))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
