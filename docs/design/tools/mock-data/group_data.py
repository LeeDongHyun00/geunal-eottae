"""Mock schedules for the 주말모임 demo group (5 members) and the free counts
the Rust core would hand the app: hourly strips per date, 30-min rows for 11/7."""
import datetime as dt
import json

MEMBERS = ["하린", "지영", "민수", "서연", "현우"]
# Weekly busy intervals in hours (start, end); 0=Mon .. 6=Sun.
WEEKLY = {
    "하린": {0: [(10, 12)], 1: [(13, 15)], 2: [(10, 12)], 3: [(13, 15)], 4: [(17, 22)], 5: [(8, 13)]},
    "지영": {0: [(9, 18)], 1: [(9, 18)], 2: [(9, 18)], 3: [(9, 18), (19, 21)], 4: [(9, 18)]},
    "민수": {0: [(10, 18)], 1: [(10, 18)], 2: [(10, 18)], 3: [(10, 18)], 4: [(10, 17)]},
    "서연": {0: [(10, 19)], 1: [(10, 19)], 3: [(10, 19)], 4: [(10, 16)]},
    "현우": {0: [(18, 22)], 1: [(18, 22)], 2: [(18, 22)], 3: [(18, 22)], 4: [(18, 22)], 6: [(13, 18)]},
}
# Date-specific busy intervals, added on top of the weekly ones.
EXTRA = {
    "2026-11-07": {"지영": [(11, 14)], "민수": [(9, 11), (15, 16)], "서연": [(13, 17)], "현우": [(21.5, 24)]},
    "2026-11-08": {"지영": [(10, 12)]},
    "2026-11-14": {"민수": [(9, 18)], "지영": [(14, 17)]},
    "2026-11-15": {"지영": [(10, 12)], "하린": [(15, 18)]},
    "2026-11-21": {"민수": [(18, 22)], "서연": [(18, 22)], "하린": [(18, 22)], "지영": [(12, 15)]},
    "2026-11-22": {"서연": [(11, 15)]},
    "2026-11-28": {"서연": [(0, 24)], "현우": [(14, 20)]},
    "2026-11-29": {"서연": [(0, 24)]},
    "2026-12-05": {"지영": [(13, 18)]},
}

def busy(member, day):
    iv = list(WEEKLY[member].get(day.weekday(), []))
    iv += EXTRA.get(day.isoformat(), {}).get(member, [])
    return iv

def free_whole(member, day, start, end):
    return all(not (s < end and start < e) for s, e in busy(member, day))

def step(k, n):
    if k == 0:
        return 0
    if k == n:
        return 5
    return min(4, 1 + (k - 1) * 4 // (n - 1))

n = len(MEMBERS)
strips = {}
day = dt.date(2026, 11, 1)
while day <= dt.date(2026, 12, 5):
    s = "".join(str(step(sum(free_whole(m, day, h, h + 1) for m in MEMBERS), n)) for h in range(24))
    strips[day.isoformat()] = s
    day += dt.timedelta(days=1)

d7 = dt.date(2026, 11, 7)
rows = {m: "".join("1" if free_whole(m, d7, i / 2, i / 2 + 0.5) else "0" for i in range(48)) for m in MEMBERS}
combined = "".join(str(step(sum(int(rows[m][i]) for m in MEMBERS), n)) for i in range(48))
counts = "".join(str(sum(int(rows[m][i]) for m in MEMBERS)) for i in range(48))
print(json.dumps({"strips": strips, "rows": rows, "combined": combined, "counts": counts}, ensure_ascii=False, indent=0))
