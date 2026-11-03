"""List the QA table's tests (docs/qa/m1.md 2.1-2.3) and the threat model's M1 tests, and say where each exists on
the given git ref: Rust snake_case, Kotlin camelCase or Swift testCamelCase.
Usage: python3 -I check_tests.py <m1.md> <threat-model.md> <repo> <ref>"""
import re, subprocess, sys

m1, tm, repo, ref = sys.argv[1:5]

def words(name):
    name = name.replace("'", "").replace("’", "")
    return re.findall(r"[A-Za-z0-9]+", name)

def forms(name):
    w = words(name)
    snake = "_".join(x.lower() for x in w)
    camel = w[0].lower() + "".join(x[:1].upper() + x[1:].lower() for x in w[1:])
    swift = "test" + "".join(x[:1].upper() + x[1:].lower() for x in w)
    return snake, camel, swift

def present(pattern):
    out = subprocess.run(["git", "-C", repo, "grep", "-il", "-E", pattern, ref, "--", "core", "apps", "server"],
                         capture_output=True, text=True).stdout
    return sorted({line.split(":", 2)[1] for line in out.splitlines()})

rows = []
for line in open(m1, encoding="utf-8"):
    m = re.match(r"\| ([RAD]\d+) \|", line)
    if not m:
        continue
    for name in re.findall(r"`([a-z][^`]*?)`", line.split("|")[3]):
        if " " in name:
            rows.append((m.group(1), name))
for line in open(tm, encoding="utf-8"):
    m = re.match(r"\| `([a-z][^`]+)` \| ([^|]+) \| (M1) \|", line)
    if m:
        rows.append(("TM:" + m.group(2).strip(), m.group(1)))

for tag, name in rows:
    snake, camel, swift = forms(name)
    hits = present("(" + "|".join(map(re.escape, (snake, camel, swift))) + ")")
    print(f"{tag:22} {'OK ' if hits else '-- '} {name}  {' '.join(h.split('/')[0] + '/' + h.split('/')[1] if '/' in h else h for h in hits)[:80]}")
