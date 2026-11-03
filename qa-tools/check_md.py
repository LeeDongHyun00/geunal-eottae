# Check relative links and table column counts in the given Markdown files.
import re, sys, pathlib
bad = 0
for name in sys.argv[1:]:
    path = pathlib.Path(name)
    text = path.read_text()
    in_code = False
    header_cols = None
    for n, line in enumerate(text.split("\n"), 1):
        if line.startswith("```"):
            in_code = not in_code
            continue
        if in_code:
            continue
        for target in re.findall(r"\]\(([^)]+)\)", line):
            if re.match(r"[a-z]+:", target) or target.startswith("#"):
                continue
            file = target.split("#")[0]
            if not (path.parent / file).exists():
                print(f"{name}:{n}: missing link target {target}")
                bad += 1
        if line.startswith("|"):
            # Escaped pipes and pipes inside code spans are not column separators.
            cells = re.sub(r"`[^`]*`", "``", line.replace("\\|", ""))
            cols = cells.count("|") - 1
            if header_cols is None:
                header_cols = cols
            elif cols != header_cols:
                print(f"{name}:{n}: {cols} columns, header has {header_cols}")
                bad += 1
        else:
            header_cols = None
print("problems:", bad)
