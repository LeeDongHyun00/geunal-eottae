"""Check design markdown: table cell counts, relative links and anchors, unreferenced PNGs."""
import re
import sys
import unicodedata
from pathlib import Path

root = Path(sys.argv[1])
design = root / "docs/design"
files = sorted(design.glob("*.md"))
errors = []


def cells(line):
    # Split on pipes that are neither escaped nor inside inline code.
    out, cur, in_code = [], "", False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "`":
            in_code = not in_code
        if ch == "\\" and i + 1 < len(line) and line[i + 1] == "|":
            cur += "\\|"
            i += 2
            continue
        if ch == "|" and not in_code:
            out.append(cur)
            cur = ""
        else:
            cur += ch
        i += 1
    out.append(cur)
    return out[1:-1]


def slug(text):
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"`", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    kept = []
    for ch in text:
        cat = unicodedata.category(ch)
        if ch in " -_" or cat[0] in "LN" or cat == "Mn":
            kept.append(ch)
    return "".join(kept).replace(" ", "-")


def anchors(path):
    seen, result = {}, set()
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("```"):
            in_fence = not in_fence
        if in_fence:
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            s = slug(m.group(2))
            n = seen.get(s, 0)
            result.add(s if n == 0 else f"{s}-{n}")
            seen[s] = n + 1
    return result


referenced = set()
link_count = 0
for f in files:
    text = f.read_text(encoding="utf-8")
    lines = text.splitlines()
    in_fence = False
    header_n = None
    for no, line in enumerate(lines, 1):
        if line.startswith("```"):
            in_fence = not in_fence
            header_n = None
            continue
        if in_fence:
            continue
        if line.startswith("|"):
            n = len(cells(line))
            if header_n is None:
                header_n = n
            elif n != header_n:
                errors.append(f"{f.name}:{no}: {n} cells, header has {header_n}")
        else:
            header_n = None
    for m in re.finditer(r"(?:\]\(|src=\")([^)\"\s]+)", text):
        target = m.group(1)
        if re.match(r"^[a-z]+:", target):
            continue
        link_count += 1
        path_part, _, anchor = target.partition("#")
        dest = (f.parent / path_part).resolve() if path_part else f
        if not dest.exists():
            errors.append(f"{f.name}: missing {target}")
            continue
        if dest.suffix == ".png":
            referenced.add(dest)
        if anchor and dest.suffix == ".md" and anchor not in anchors(dest):
            errors.append(f"{f.name}: missing anchor {target}")

pngs = set(p.resolve() for p in (design / "screens").rglob("*.png"))
for p in sorted(pngs - referenced):
    errors.append(f"unreferenced {p.relative_to(design.resolve())}")

print(f"{len(files)} files, {link_count} links checked")
print("\n".join(errors) if errors else "OK")
