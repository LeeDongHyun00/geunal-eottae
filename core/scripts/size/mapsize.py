"""Sum an lld link map's input sections by library: which archive or object each byte of the output came from.

usage: python3 -I mapsize.py <map>
"""

import collections
import re
import sys

LINE = re.compile(r"^\s*([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+(\d+)\s(\s*)(.*)$")

by_owner = collections.Counter()
by_section = collections.defaultdict(collections.Counter)
out_section = None
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = LINE.match(raw)
    if not m:
        continue
    _, _, size, _, indent, rest = m.groups()
    size = int(size, 16)
    depth = len(indent)
    if depth == 0:
        out_section = rest.split()[0] if rest else None
        continue
    if not (rest.startswith("/") or rest.startswith("<")) or ":(" not in rest:
        continue  # a symbol line, or something that is not an input section
    path = rest.split(":(")[0]
    if "libcrypto" in path or "openssl" in path:
        owner = "openssl"
    elif "sqlite" in path or "sqlcipher" in path:
        owner = "sqlite"
    elif "ring" in path:
        owner = "ring"
    elif path.endswith(".o") and ("/deps/" in path or "rcgu" in path or "geunal_ffi" in path):
        owner = "rust (LTO)"
    elif "<internal>" in path:
        owner = "linker"
    elif "sysroot" in path or "clang" in path or "compiler-rt" in path:
        owner = "ndk runtime"
    else:
        owner = path.split("/")[-1][:40]
    by_owner[owner] += size
    by_section[owner][out_section] += size

total = sum(by_owner.values())
print(f"total {total / 1048576:.2f} MB")
for owner, size in by_owner.most_common(int(sys.argv[2]) if len(sys.argv) > 2 else 12):
    top = ", ".join(f"{s} {v / 1048576:.2f}" for s, v in by_section[owner].most_common(5))
    print(f"{size / 1048576:7.2f} MB  {owner:14s} {top}")
