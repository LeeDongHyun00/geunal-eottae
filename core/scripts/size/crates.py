import re, sys, collections
sizes = collections.Counter()
total = 0
pat = re.compile(r"^([0-9a-f]+) ([0-9a-f]+) (\S) (.*)$")
biggest = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = pat.match(line.rstrip("\n"))
    if not m:
        continue
    size = int(m.group(2), 16)
    kind, name = m.group(3), m.group(4)
    total += size
    biggest.append((size, kind, name))
    # Attribute "<A as B>::f" to A's crate; "a::b::c" to a; plain C names to [C].
    mm = re.match(r"^<+(?:&(?:mut )?)?(?:dyn )?(?:\[)?([A-Za-z_][A-Za-z0-9_]*)::", name)
    if mm:
        crate = mm.group(1)
    else:
        mm = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)::", name)
        crate = mm.group(1) if mm else f"[C {kind}]"
    sizes[crate] += size
print(f"total symbol bytes: {total/1048576:.3f} MB")
for crate, size in sizes.most_common(int(sys.argv[2]) if len(sys.argv) > 2 else 40):
    print(f"{size/1024:9.1f} KB  {crate}")
if len(sys.argv) > 3:
    print("--- biggest symbols")
    for size, kind, name in sorted(biggest, reverse=True)[: int(sys.argv[3])]:
        print(f"{size/1024:8.1f} KB {kind} {name[:150]}")
