#!/usr/bin/env python3
"""Generate the group colour ramps and check the design tokens.

    python3 docs/design/palette.py           # validate tokens.json, exit 1 on any failure
    python3 docs/design/palette.py --ramps   # print the generated 12 x 5 ramps for both modes

Steps are equal intervals of OKLab lightness inside one OKLCH hue, because equal OKLab steps
look evenly spaced while RGB or HSL steps do not. Chroma is only reduced as far as sRGB requires,
so the hue never drifts between steps. Standard library only, so any part can run it.
"""
import argparse
import json
import math
import re
import sys
from pathlib import Path

TOKENS = Path(__file__).with_name("tokens.json")

# (key, OKLCH hue in degrees, chroma scale). Cocoa is a low-chroma orange so it reads as brown.
HUES = [
  ("tomato", 27, 1.0), ("tangerine", 52, 1.0), ("mustard", 82, 1.0), ("olive", 122, 1.0),
  ("forest", 150, 1.0), ("mint", 182, 1.0), ("sky", 228, 1.0), ("sea", 262, 1.0),
  ("grape", 292, 1.0), ("plum", 328, 1.0), ("blossom", 358, 1.0), ("cocoa", 50, 0.42),
]
MODES = {
  "light": {"L1": 0.885, "C": [0.050, 0.090, 0.125, 0.150, 0.155]},
  "dark": {"L1": 0.285, "C": [0.045, 0.070, 0.095, 0.120, 0.155]},
}
L5_CAP = 0.600
# Step 5 carries white text (headers, buttons). Aim above 4.5 so 8-bit rounding never lands under AA.
WHITE_TARGET = 4.6

AA_TEXT = 4.5
AA_GRAPHIC = 3.0
MIN_STEP_DL = 0.06   # adjacent steps must stay distinguishable
MIN_EMPTY_DL = 0.05  # one free member must not look like an empty cell


def srgb_to_lin(c):
  return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lin_to_srgb(c):
  return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def oklab_to_lin(L, a, b):
  l_ = L + 0.3963377774 * a + 0.2158037573 * b
  m_ = L - 0.1055613458 * a - 0.0638541728 * b
  s_ = L - 0.0894841775 * a - 1.2914855480 * b
  l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
  return (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
          -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
          -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)


def lin_to_oklab(r, g, b):
  l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
  m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
  s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
  l_, m_, s_ = (math.copysign(abs(v) ** (1 / 3), v) for v in (l, m, s))
  return (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
          1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
          0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_)


def oklch_to_lin(L, C, h):
  return oklab_to_lin(L, C * math.cos(math.radians(h)), C * math.sin(math.radians(h)))


def in_gamut(rgb, eps=1e-6):
  return all(-eps <= v <= 1 + eps for v in rgb)


def fit_chroma(L, C, h):
  """Largest chroma <= C that stays inside sRGB at this lightness and hue."""
  if in_gamut(oklch_to_lin(L, C, h)):
    return C
  lo, hi = 0.0, C
  for _ in range(40):
    mid = (lo + hi) / 2
    lo, hi = (mid, hi) if in_gamut(oklch_to_lin(L, mid, h)) else (lo, mid)
  return lo


def to_hex(rgb_lin):
  return "#%02x%02x%02x" % tuple(round(lin_to_srgb(min(1.0, max(0.0, v))) * 255) for v in rgb_lin)


def hex_to_rgb(hx):
  hx = hx.lstrip("#")
  return tuple(int(hx[i:i + 2], 16) for i in (0, 2, 4))


def lightness(hx):
  return lin_to_oklab(*(srgb_to_lin(c / 255) for c in hex_to_rgb(hx)))[0]


def luminance(hx):
  r, g, b = (srgb_to_lin(c / 255) for c in hex_to_rgb(hx))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
  la, lb = luminance(a), luminance(b)
  return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def over(color, backdrop):
  """Composite a CSS colour (hex or rgba()) over an opaque hex backdrop, in sRGB like browsers do."""
  m = re.fullmatch(r"rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)", color.replace(" ", ""))
  if not m:
    return color
  r, g, b, a = int(m[1]), int(m[2]), int(m[3]), float(m[4])
  br, bg, bb = hex_to_rgb(backdrop)
  return "#%02x%02x%02x" % tuple(round(c * a + d * (1 - a)) for c, d in ((r, br), (g, bg), (b, bb)))


def free_step(k, n):
  """Ramp step for k free members out of n: 0 is empty, everyone free is always step 5."""
  if k == 0:
    return 0
  if k == n:
    return 5
  return min(4, 1 + (k - 1) * 4 // (n - 1))


def build_ramps():
  ramps = {}
  for key, h, scale in HUES:
    ramps[key] = {}
    for mode, spec in MODES.items():
      chroma = [c * scale for c in spec["C"]]
      L5 = L5_CAP
      while contrast(to_hex(oklch_to_lin(L5, fit_chroma(L5, chroma[4], h), h)), "#ffffff") < WHITE_TARGET:
        L5 -= 0.001
      Ls = [spec["L1"] + (L5 - spec["L1"]) * i / 4 for i in range(5)]
      ramps[key][mode] = [to_hex(oklch_to_lin(L, fit_chroma(L, C, h), h)) for L, C in zip(Ls, chroma)]
  return ramps


class Report:
  def __init__(self):
    self.failures = 0

  def check(self, ok, label, detail):
    self.failures += not ok
    print(f"{'PASS' if ok else 'FAIL'}  {label:<34} {detail}")


def validate(tokens):
  rep = Report()
  color = tokens["color"]
  generated = build_ramps()

  print("== group ramps")
  for key, _, _ in HUES:
    group = color["group"][key]
    for mode in MODES:
      steps = group[mode]
      Ls = [lightness(s) for s in steps]
      dls = [abs(Ls[i + 1] - Ls[i]) for i in range(4)]
      ordered = all(Ls[i] > Ls[i + 1] for i in range(4)) if mode == "light" else all(Ls[i] < Ls[i + 1] for i in range(4))
      white = contrast(steps[4], "#ffffff")
      empty_dl = abs(Ls[0] - lightness(color[mode]["emptyCell"]))
      ok = (steps == generated[key][mode] and ordered and min(dls) >= MIN_STEP_DL
            and white >= AA_TEXT and empty_dl >= MIN_EMPTY_DL)
      rep.check(ok, f"{group['name']} {mode}",
                f"matches generator={steps == generated[key][mode]} ordered={ordered} "
                f"minΔL={min(dls):.3f} white/5={white:.2f} step1-empty ΔL={empty_dl:.3f}")

  print("== text on surfaces")
  for mode in MODES:
    c = color[mode]
    for surface in ("background", "groupedBackground"):
      for text in ("ink", "secondaryText", "tertiaryText", "sunday", "saturday"):
        ratio = contrast(c[text], c[surface])
        rep.check(ratio >= AA_TEXT, f"{mode} {text} on {surface}", f"{ratio:.2f}")

  print("== brand and navigation")
  brand, glass, nav = color["brand"], tokens["glass"], tokens["nav"]["tabBar"]
  rep.check(contrast("#ffffff", brand["grape"]) >= AA_TEXT, "white on brand grape",
            f"{contrast('#ffffff', brand['grape']):.2f}")
  # The selected pill sits on glass over the page. Busy content is the blurred average of a calendar
  # page behind the bar (#f0f0f0); text and chips under the glass make it greyer than a blank page.
  for name, page in (("plain page", color["light"]["background"]), ("busy content", "#f0f0f0")):
    pill = over(nav["activePill"]["light"], over(glass["light"]["fill"], page))
    ratio = contrast(brand["tabText"]["light"], pill)
    rep.check(ratio >= AA_TEXT, f"light tab text, {name}", f"{ratio:.2f} on {pill}")
  dark_pill = over(nav["activePill"]["dark"], over(glass["dark"]["fill"], color["dark"]["background"]))
  ratio = contrast(brand["tabText"]["dark"], dark_pill)
  rep.check(ratio >= AA_TEXT, "dark tab text on pill", f"{ratio:.2f} on {dark_pill}")
  for mode in MODES:
    ratio = contrast(nav["inactive"][mode], over(glass[mode]["fill"], color[mode]["background"]))
    rep.check(ratio >= AA_TEXT, f"{mode} inactive tab text", f"{ratio:.2f}")
  ratio = contrast(brand["tabText"]["light"], brand["androidBar"])
  rep.check(ratio >= AA_TEXT, "android label on bar", f"{ratio:.2f}")
  ratio = contrast(brand["tabText"]["light"], brand["androidIndicator"])
  rep.check(ratio >= AA_GRAPHIC, "android icon on indicator", f"{ratio:.2f} (graphic, 3:1)")

  print("== free-count steps")
  expected = {2: [1, 5], 3: [1, 3, 5], 5: [1, 2, 3, 4, 5], 10: [1, 1, 1, 2, 2, 3, 3, 4, 4, 5]}
  for n, want in expected.items():
    got = [free_step(k, n) for k in range(1, n + 1)]
    rep.check(got == want, f"{n} members", " ".join(map(str, got)))

  print(f"\n{rep.failures} failure(s)")
  return rep.failures


def main():
  parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
  parser.add_argument("--ramps", action="store_true", help="print generated ramps as JSON")
  args = parser.parse_args()
  if args.ramps:
    json.dump(build_ramps(), sys.stdout, indent=2)
    print()
    return 0
  return 1 if validate(json.loads(TOKENS.read_text(encoding="utf-8"))) else 0


if __name__ == "__main__":
  sys.exit(main())
