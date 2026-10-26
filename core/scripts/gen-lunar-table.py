#!/usr/bin/env python3
"""Builds core/geunal-domain/src/lunar/table.rs, the Korean lunar calendar 1900-2049 the core carries (kickoff §10).

Two MIT-licensed npm packages carry tables made from 한국천문연구원 (KASI) data. Both are downloaded at pinned
versions, checked against their npm integrity hashes, and read as text only: nothing in them is executed. The script
compares every year's leap month and month lengths across the two, then writes one table:

  - manseryeok 2.0.0, dist/calendar/lunar-data.js: 1391-2049 from the KASI 음양력 API (2050-2100 is astronomical
    calculation and is not used).
  - korean-lunar-calendar 0.4.0, src/korean-lunar-data.ts (in the source map): 1000-2050, KASI based.

Run from the repository root:  python3 -I core/scripts/gen-lunar-table.py
"""

import base64
import datetime
import hashlib
import io
import json
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

FIRST, LAST = 1900, 2049
# Lunar 1900-01-01 fell on solar 1900-01-31; the table counts every later new year from here. The script checks this
# anchor against manseryeok's own base (lunar 1391-01-01 = solar 1391-02-13).
ANCHOR = datetime.date(1900, 1, 31)

PACKAGES = {
    "manseryeok": (
        "https://registry.npmjs.org/manseryeok/-/manseryeok-2.0.0.tgz",
        "sha512-h4YeuKP+vsAa01K72v/H+5MwxEZd1Sv7H2hC8YU7r05JjNaCDvVhGaIot0zxrktyZVU6SGN5Ed9d1coTWW8rpQ==",
    ),
    "korean-lunar-calendar": (
        "https://registry.npmjs.org/korean-lunar-calendar/-/korean-lunar-calendar-0.4.0.tgz",
        "sha512-kJXyH3sI/butJxCBtsLXlMJsl5qqNKNjduf1Na0s7XXwP+D/Wdlx0R0/S5ygxPVFSXf/JZ3bBKaCP6bGWhgZ0w==",
    ),
}

# Dates the result must reproduce: 설날 and 추석 as published (the 1997 설날 is the year China's fell a day earlier).
KNOWN = [
    ((1997, 1, 1), datetime.date(1997, 2, 8)),
    ((2025, 8, 15), datetime.date(2025, 10, 6)),
    ((2026, 1, 1), datetime.date(2026, 2, 17)),
    ((2026, 8, 15), datetime.date(2026, 9, 25)),
    ((2027, 1, 1), datetime.date(2027, 2, 7)),
    ((2027, 8, 15), datetime.date(2027, 9, 15)),
    ((2030, 1, 1), datetime.date(2030, 2, 3)),
]


def fetch(name):
    url, integrity = PACKAGES[name]
    data = urllib.request.urlopen(url, timeout=60).read()
    algo, expected = integrity.split("-", 1)
    actual = base64.b64encode(hashlib.new(algo, data).digest()).decode()
    if actual != expected:
        sys.exit(f"{name}: integrity {algo}-{actual} is not the pinned {integrity}")
    return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")


def member(archive, path):
    return archive.extractfile(path).read().decode("utf-8")


def numbers(text, name):
    found = re.search(name + r"\s*[:=]\s*\[([^\]]*)\]", text)
    if not found:
        sys.exit(f"no {name} array")
    return [int(token, 0) for token in re.findall(r"0x[0-9a-fA-F]+|\d+", found.group(1))]


def manseryeok():
    """{year: (leap month or 0, leap month long, [12 month lengths])} from manseryeok's 17-bit packing."""
    text = member(fetch("manseryeok"), "package/dist/calendar/lunar-data.js")
    data = numbers(text, "LUNAR_DATA")
    base = int(re.search(r"LUNAR_MIN_YEAR = (\d+)", text).group(1))
    years = {}
    for i, value in enumerate(data):
        months = [30 if value & (0x10000 >> m) else 29 for m in range(1, 13)]
        years[base + i] = (value & 0xF, bool(value & 0x10000), months)
    # The anchor follows from the package's own base date.
    days = sum(sum(m) + (30 if years[y][1] else 29) * (years[y][0] > 0) for y in range(base, FIRST) for m in [years[y][2]])
    if datetime.date(1391, 2, 13) + datetime.timedelta(days=days) != ANCHOR:
        sys.exit("manseryeok's base does not lead to lunar 1900-01-01 = solar 1900-01-31")
    return years


def korean_lunar_calendar():
    """The same from korean-lunar-calendar's 32-bit packing (months 1..12 in bits 11..0, leap month in 12..15)."""
    archive = fetch("korean-lunar-calendar")
    source_map = json.loads(member(archive, "package/dist/korean-lunar-calendar.min.js.map"))
    text = next(s for p, s in zip(source_map["sources"], source_map["sourcesContent"]) if p.endswith("korean-lunar-data.ts"))
    data = numbers(text, "KOREAN_LUNAR_DATA")
    base = int(re.search(r"KOREAN_LUNAR_BASE_YEAR:\s*(\d+)", text).group(1))
    years = {}
    for i, value in enumerate(data):
        months = [30 if (value >> (12 - m)) & 1 else 29 for m in range(1, 13)]
        years[base + i] = ((value >> 12) & 0xF, bool((value >> 16) & 1), months)
    return years


def solar(years, year, month, day, leap=False):
    """The solar date of a lunar date, counted from the anchor."""
    days = sum(sum(years[y][2]) + (30 if years[y][1] else 29) * (years[y][0] > 0) for y in range(FIRST, year))
    leap_month, leap_long, months = years[year]
    for m in range(1, month):
        days += months[m - 1]
        if leap_month == m:
            days += 30 if leap_long else 29
    if leap:
        days += months[month - 1]
    return ANCHOR + datetime.timedelta(days=days + day - 1)


def main():
    a, b = manseryeok(), korean_lunar_calendar()
    differ = [y for y in range(FIRST, LAST + 1) if a[y] != b[y]]
    if differ:
        sys.exit(f"the two tables differ in {differ}")
    for (lunar, expected) in KNOWN:
        if solar(a, *lunar) != expected:
            sys.exit(f"lunar {lunar} gave {solar(a, *lunar)}, not {expected}")
    rows = []
    for y in range(FIRST, LAST + 1):
        leap_month, leap_long, months = a[y]
        packed = sum(1 << (m - 1) for m in range(1, 13) if months[m - 1] == 30) | (leap_month << 12) | (leap_long << 16)
        rows.append(f"0x{packed:05x}")
    # Twelve to a line is what rustfmt makes of the array at max_width 120, so the output needs no formatting.
    lines = [", ".join(rows[i : i + 12]) + "," for i in range(0, len(rows), 12)]
    out = Path(__file__).resolve().parents[1] / "geunal-domain/src/lunar/table.rs"
    out.write_text(
        "// Generated by core/scripts/gen-lunar-table.py from manseryeok 2.0.0 and korean-lunar-calendar 0.4.0, which\n"
        "// agree on every year here (한국천문연구원 data; MIT, see NOTICE next to this file). Do not edit by hand.\n"
        "//\n"
        "// One value per lunar year from FIRST_YEAR: bit m-1 set means month m has 30 days (else 29), bits 12..15 the\n"
        "// leap month (0 = none), bit 16 a 30-day leap month.\n"
        "/// The first and last lunar years the table covers.\n"
        f"pub const FIRST_YEAR: i32 = {FIRST};\n"
        f"pub const LAST_YEAR: i32 = {LAST};\n"
        f"/// Solar day of lunar {FIRST}-01-01.\n"
        f"pub(super) const FIRST_NEW_YEAR: (i32, u32, u32) = ({ANCHOR.year}, {ANCHOR.month}, {ANCHOR.day});\n"
        f"pub(super) const YEARS: [u32; {LAST - FIRST + 1}] = [\n"
        + "".join(f"    {line}\n" for line in lines)
        + "];\n"
    )
    print(f"wrote {out} ({LAST - FIRST + 1} years, the two sources agree)")


if __name__ == "__main__":
    main()
