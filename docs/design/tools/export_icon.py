# Export the chosen app icon (direction A) as standalone SVG layers for the app parts.
# Geometry and paint come from gen_appicon so the files match the canvas board exactly.
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import gen_appicon as g  # noqa: E402

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)


def standalone(svg):
    head = svg[:svg.index('>') + 1]
    return svg.replace(head, '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">', 1) + '\n'


def mono_glyph():
    # Android tints the monochrome layer by alpha only, so regions must not overlap: the band and
    # the body are separate shapes and the strip is a hole filled with alpha steps.
    x, y, w, h, r = g.A_CARD
    band = f'<path d="{g.A_BAND}" fill="#000000" fill-opacity="0.55"></path>'
    body = ('<path fill-rule="evenodd" fill="#000000" d="M212,412 H812 V672 A120,120 0 0 1 692,792 H332 '
            'A120,120 0 0 1 212,672 Z M326,494 H698 A44,44 0 0 1 742,538 V650 A44,44 0 0 1 698,694 H326 '
            'A44,44 0 0 1 282,650 V538 A44,44 0 0 1 326,494 Z"></path>')
    rings = ''.join(f'<rect x="{rx}" y="{ry}" width="40" height="112" rx="20" fill="#000000"></rect>' for rx, ry in g.A_RINGS)
    alphas = (0.2, 0.4, 0.6, 0.8, 1.0)
    segs = (f'<path d="M326,494 H374 V694 H326 A44,44 0 0 1 282,650 V538 A44,44 0 0 1 326,494 Z" fill="#000000" fill-opacity="{alphas[0]}"></path>'
            + ''.join(f'<rect x="{374 + i * 92}" y="494" width="92" height="200" fill="#000000" fill-opacity="{alphas[i + 1]}"></rect>' for i in range(3))
            + f'<path d="M650,494 H698 A44,44 0 0 1 742,538 V650 A44,44 0 0 1 698,694 H650 Z" fill="#000000" fill-opacity="{alphas[4]}"></path>')
    inner = f'<g transform="translate(512 512) scale(0.8) translate(-512 -512)">{band}{body}{rings}{segs}</g>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">{inner}</svg>\n'


files = {
    'app-icon.svg': standalone(g.icon_a(1024, 'default')),
    'app-icon-dark.svg': standalone(g.icon_a(1024, 'dark')),
    'ios-background.svg': standalone(g.icon_a(1024, 'default', fg=False)),
    'ios-foreground.svg': standalone(g.icon_a(1024, 'default', bg=False)),
    'android-background.svg': standalone(g.icon_a(1024, 'default', fg=False)),
    'android-foreground.svg': standalone(g.icon_a(1024, 'default', scale=0.8, bg=False)),
    'android-monochrome.svg': mono_glyph(),
}
for name, text in files.items():
    (OUT / name).write_text(text, encoding='utf-8')
    print(name, len(text), 'bytes')
