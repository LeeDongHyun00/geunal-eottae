#!/usr/bin/env python3
"""Compare the pins of two Package.resolved files: package identity, location, version and revision.

The `originHash` is left out: it hashes the dependency declarations, which a manifest edit changes without moving a
version. Exits 0 when the pins agree, 1 when they differ or the committed file is missing.
"""
import json
import sys
from pathlib import Path


def pins(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {
        pin["identity"]: (pin.get("location"), pin["state"].get("version"), pin["state"].get("revision"))
        for pin in data.get("pins", [])
    }


def main(committed, resolved):
    if not Path(committed).exists():
        print("apps/ios/Package.resolved is missing", file=sys.stderr)
        return 1
    want, got = pins(committed), pins(resolved)
    if want == got:
        print("Swift packages: %s" % ", ".join("%s %s" % (k, v[1] or v[2]) for k, v in sorted(got.items())))
        return 0
    for identity in sorted(set(want) | set(got)):
        if want.get(identity) != got.get(identity):
            print("%s: committed %s, resolved %s" % (identity, want.get(identity), got.get(identity)), file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
