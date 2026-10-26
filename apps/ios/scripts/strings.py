#!/usr/bin/env python3
"""Keep the String Catalogs and the Swift sources in step.

`check` (run by `make lint-ios`) fails when a localized literal in the sources has no key in its catalog, when a
catalog key is no longer used, or when package code looks a string up in the wrong bundle. `sync` adds the missing keys
and drops the unused ones, for a developer to run after editing screens.

Each Swift package keeps its strings in `Sources/<Name>/Resources/Localizable.xcstrings` and must pass
`bundle: .module`; the app target keeps its own in `GeunalApp/Localizable.xcstrings`. Korean is the source language,
so a key is the Korean text itself, with Swift interpolations written as format specifiers (`%lld` for integers).
"""
import json
import re
import sys
from pathlib import Path

IOS = Path(__file__).resolve().parents[1]

# (sources directory, catalog, whether lookups must name the package bundle)
TARGETS = [
    (IOS / "GeunalApp", IOS / "GeunalApp/Localizable.xcstrings", False),
]
for package in sorted((IOS / "Packages").iterdir()):
    sources = package / "Sources" / package.name
    catalog = sources / "Resources/Localizable.xcstrings"
    if catalog.exists():
        TARGETS.append((sources, catalog, True))

# Calls whose first string literal is a localization key.
LOOKUPS = re.compile(r'\b(?:Text|LocalizedStringKey)\(\s*"|\bString\(\s*localized:\s*"')
# SwiftUI initializers that take a key and look it up in the main bundle; in a package they would miss its catalog.
MAIN_BUNDLE_ONLY = re.compile(
    r'\b(Button|Label|Toggle|TextField|SecureField|Section|Picker|Menu|Link|LabeledContent)\(\s*"'
    r'|\.(navigationTitle|accessibilityLabel|accessibilityHint|help)\(\s*"')


def literal_at(text, start):
    """The Swift string literal starting at the quote `start`, as (raw contents, end index)."""
    assert text[start] == '"'
    if text.startswith('"""', start):
        end = text.index('"""', start + 3)
        return text[start + 3:end], end + 3
    i, depth = start + 1, 0
    while True:
        ch = text[i]
        if ch == "\\" and text[i + 1] == "(":
            depth += 1
            i += 2
            continue
        if ch == "\\":
            i += 2
            continue
        if depth and ch == "(":
            depth += 1
        elif depth and ch == ")":
            depth -= 1
        elif not depth and ch == '"':
            return text[start + 1:i], i + 1
        i += 1


def key_of(raw):
    """A source literal as its catalog key: escapes resolved, interpolations as `%lld`."""
    out, i = [], 0
    while i < len(raw):
        if raw.startswith("\\(", i):
            depth, j = 1, i + 2
            while depth:
                depth += {"(": 1, ")": -1}.get(raw[j], 0)
                j += 1
            out.append("%lld")
            i = j
        elif raw[i] == "\\":
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(raw[i + 1], raw[i + 1]))
            i += 2
        else:
            out.append(raw[i])
            i += 1
    return "".join(out)


def normalized(key):
    return re.sub(r"%(lld|ld|d|@|u|llu|f|\.\d+f)", "%_", key)


def without_comments(text):
    """`text` with `//` comments blanked out (keeping line numbers), so documentation that quotes code is not read as
    a lookup. String literals are skipped over, so `//` inside one stays."""
    out, i, in_string = [], 0, False
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                out.append(text[i:i + 2])
                i += 2
                continue
            if ch == '"':
                in_string = False
            out.append(ch)
        elif ch == '"':
            in_string = True
            out.append(ch)
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = len(text) if end == -1 else end
            out.append(" " * (end - i))
            i = end
            continue
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def scan(sources, packaged):
    keys, problems = {}, []
    for path in sorted(sources.rglob("*.swift")):
        text = without_comments(path.read_text(encoding="utf-8"))
        for match in LOOKUPS.finditer(text):
            quote = match.end() - 1
            raw, end = literal_at(text, quote)
            line = text.count("\n", 0, quote) + 1
            if "verbatim:" in text[match.start():quote]:
                continue
            keys.setdefault(key_of(raw), []).append("%s:%d" % (path.relative_to(IOS), line))
            if packaged:
                call_end = text.find("\n", end)
                tail = text[end:call_end if call_end != -1 else len(text)]
                # The bundle argument follows the literal on the same line or, wrapped, on the next.
                nxt = text[call_end:text.find("\n", call_end + 1)] if call_end != -1 else ""
                if "bundle: .module" not in tail and "bundle: .module" not in nxt:
                    problems.append("%s:%d: a package string needs `bundle: .module`" % (path.relative_to(IOS), line))
        if packaged:
            for match in MAIN_BUNDLE_ONLY.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                problems.append(
                    "%s:%d: `%s` looks the key up in the main bundle; pass `Text(\"…\", bundle: .module)`"
                    % (path.relative_to(IOS), line, match.group(0).rstrip('"').strip()))
    return keys, problems


def load(catalog):
    if catalog.exists():
        return json.loads(catalog.read_text(encoding="utf-8"))
    return {"sourceLanguage": "ko", "strings": {}, "version": "1.0"}


def write(catalog, data):
    data["strings"] = dict(sorted(data["strings"].items()))
    text = json.dumps(data, ensure_ascii=False, indent=2, separators=(",", " : "))
    catalog.parent.mkdir(parents=True, exist_ok=True)
    catalog.write_text(text + "\n", encoding="utf-8")


def main(mode):
    failures = []
    for sources, catalog, packaged in TARGETS:
        keys, problems = scan(sources, packaged)
        failures.extend(problems)
        data = load(catalog)
        known = {normalized(k): k for k in data["strings"]}
        used = {normalized(k): k for k in keys}
        missing = [k for n, k in used.items() if n not in known]
        stale = [k for n, k in known.items() if n not in used]
        if mode == "sync":
            for key in missing:
                data["strings"][key] = {}
            for key in stale:
                del data["strings"][key]
            write(catalog, data)
            print("%s: +%d -%d" % (catalog.relative_to(IOS), len(missing), len(stale)))
        else:
            for key in missing:
                failures.append("%s: no key for %r (%s)" % (catalog.relative_to(IOS), key, keys[key][0]))
            for key in stale:
                failures.append("%s: %r is not used any more" % (catalog.relative_to(IOS), key))
    if failures:
        print("\n".join(failures), file=sys.stderr)
        print("Run apps/ios/scripts/strings.py sync to update the catalogs.", file=sys.stderr)
        return 1
    if mode == "check":
        print("strings: every localized literal has its catalog key")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("check", "sync"):
        sys.exit("usage: strings.py check|sync")
    sys.exit(main(sys.argv[1]))
