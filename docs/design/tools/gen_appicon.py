# Generate the app icon draft board. Icons are drawn on a 1024 grid as full-bleed squares;
# the corner mask is applied by the container, mirroring how iOS and Android mask icons.
import sys

_n = [0]


def uid(prefix):
    # Gradient and clip ids must be unique per instance because one document holds many icons.
    _n[0] += 1
    return f'{prefix}{_n[0]}'


BUBBLE = ('M332,262 H692 A120,120 0 0 1 812,382 V592 A120,120 0 0 1 692,712 H460 '
          'L300,822 L344,712 H332 A120,120 0 0 1 212,592 V382 A120,120 0 0 1 332,262 Z')
CELLS = [(304, 354), (452, 354), (600, 354), (304, 500), (452, 500)]
PICKED = (600, 500)

B_VARIANTS = {
    'default': dict(bg=('#8b73d8', '#6f56c2'), bubble='#ffffff', cell='#e4def9', pick='#7d63cb'),
    'dark': dict(bg=('#2c2738', '#16141b'), bubble='#ece8f8', cell='#d4ccef', pick='#8f78e0'),
    'clear': dict(bg=None, bubble='rgba(255,255,255,0.82)', cell='rgba(30,30,40,0.10)', pick='rgba(30,30,40,0.34)'),
    'tinted': dict(bg=('#26211a', '#14110c'), bubble='#f0c46a', cell='rgba(20,17,12,0.22)', pick='#14110c'),
    'mono': dict(bg=('#e7e1f4', '#e7e1f4'), bubble='#3d3851', cell='#e7e1f4', pick='#3d3851'),
}


def bg_rect(colors):
    if not colors:
        return ''
    gid = uid('g')
    return (f'<defs><linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{colors[0]}"></stop><stop offset="1" stop-color="{colors[1]}"></stop>'
            f'</linearGradient></defs><rect width="1024" height="1024" fill="url(#{gid})"></rect>')


def svg(size, body):
    return f'<svg viewBox="0 0 1024 1024" width="{size}" height="{size}" aria-hidden="true" style="display: block">{body}</svg>'


def icon_b(size, variant='default', scale=1.0, bg=True, fg=True):
    v = B_VARIANTS[variant]
    parts = [bg_rect(v['bg']) if bg else '']
    if fg:
        cells = ''.join(f'<rect x="{x}" y="{y}" width="120" height="120" rx="30" fill="{v["cell"]}"></rect>' for x, y in CELLS)
        px, py = PICKED
        if variant == 'mono':
            # Themed icons are one colour, so the chosen day is a filled square inside a cut-out cell.
            pick = (f'<rect x="{px}" y="{py}" width="120" height="120" rx="30" fill="{v["cell"]}"></rect>'
                    f'<rect x="{px + 26}" y="{py + 26}" width="68" height="68" rx="18" fill="{v["pick"]}"></rect>')
        else:
            pick = f'<rect x="{px}" y="{py}" width="120" height="120" rx="30" fill="{v["pick"]}"></rect>'
        g = f'<path d="{BUBBLE}" fill="{v["bubble"]}"></path>{cells}{pick}'
        if scale != 1.0:
            g = f'<g transform="translate(512 512) scale({scale}) translate(-512 -512)">{g}</g>'
        parts.append(g)
    return svg(size, ''.join(parts))


RAMP = ['#d9d4f8', '#c1b6f6', '#aa98f0', '#937ce3', '#7d63cb']

# Direction A: a calendar card carrying the five free-count steps. Each appearance only swaps paint.
A_VARIANTS = {
    'default': dict(bg=('#8b73d8', '#6f56c2'), card='#ffffff', band='#e4def9', ring='#ffffff', strip=RAMP),
    'dark': dict(bg=('#2c2738', '#16141b'), card='#ece8f8', band='#d4ccef', ring='#ece8f8', strip=RAMP),
    'clear': dict(bg=None, card='rgba(255,255,255,0.82)', band='rgba(30,30,40,0.10)', ring='rgba(255,255,255,0.9)',
                  strip=[f'rgba(30,30,40,{a})' for a in (0.10, 0.17, 0.24, 0.31, 0.38)]),
    'tinted': dict(bg=('#26211a', '#14110c'), card='#f0c46a', band='rgba(20,17,12,0.16)', ring='#f0c46a',
                   strip=[f'rgba(20,17,12,{a})' for a in (0.12, 0.24, 0.36, 0.48, 0.60)]),
    # Android themed icons tint the monochrome layer by its alpha, so alpha steps keep the five levels.
    'mono': dict(bg=('#e7e1f4', '#e7e1f4'), card='#3d3851', band='rgba(231,225,244,0.45)', ring='#3d3851',
                 strip=[f'rgba(61,56,81,{a})' for a in (0.2, 0.4, 0.6, 0.8, 1.0)], cut='#e7e1f4'),
}
A_CARD = (212, 272, 600, 520, 120)          # x, y, w, h, radius
A_BAND = 'M332,272 H692 A120,120 0 0 1 812,392 V412 H212 V392 A120,120 0 0 1 332,272 Z'
A_RINGS = [(368, 216), (616, 216)]          # 40 x 112 pills straddling the card top
A_STRIP = (282, 494, 460, 200, 44)          # five equal segments


def a_foreground(v):
    x, y, w, h, r = A_CARD
    sx, sy, sw, sh, sr = A_STRIP
    cid = uid('c')
    seg_w = sw / 5
    segs = ''.join(f'<rect x="{sx + i * seg_w:.1f}" y="{sy}" width="{seg_w + 0.5:.1f}" height="{sh}" fill="{c}"></rect>'
                   for i, c in enumerate(v['strip']))
    cut = f'<rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="{sr}" fill="{v["cut"]}"></rect>' if v.get('cut') else ''
    rings = ''.join(f'<rect x="{rx}" y="{ry}" width="40" height="112" rx="20" fill="{v["ring"]}"></rect>' for rx, ry in A_RINGS)
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{v["card"]}"></rect>'
            f'<path d="{A_BAND}" fill="{v["band"]}"></path>{rings}{cut}'
            f'<defs><clipPath id="{cid}"><rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="{sr}"></rect></clipPath></defs>'
            f'<g clip-path="url(#{cid})">{segs}</g>')


def icon_a(size, variant='default', scale=1.0, bg=True, fg=True):
    v = A_VARIANTS[variant]
    parts = [bg_rect(v['bg']) if bg else '']
    if fg:
        g = a_foreground(v)
        if scale != 1.0:
            g = f'<g transform="translate(512 512) scale({scale}) translate(-512 -512)">{g}</g>'
        parts.append(g)
    return svg(size, ''.join(parts))


def icon_c(size):
    body = (bg_rect(('#8b73d8', '#6f56c2')) +
            '<text x="512" y="712" text-anchor="middle" font-family="Pretendard, sans-serif" font-weight="600" '
            'font-size="560" letter-spacing="-12" fill="#ffffff">날</text>')
    return svg(size, body)


def masked(inner, size, radius='22.37%', extra=''):
    return (f'<span style="flex: none; display: block; width: {size}px; height: {size}px; border-radius: {radius}; '
            f'overflow: hidden; {extra}">{inner}</span>')


GLASS = 'box-shadow: inset 0 1.5px 0 rgba(255,255,255,0.35), inset 0 0 0 1px rgba(255,255,255,0.12), 0 6px 18px rgba(0,0,0,0.18);'


def size_row(fn):
    cells = []
    for s in (60, 40, 29):
        cells.append(f'<span style="display: flex; flex-direction: column; align-items: center; gap: 6px">{masked(fn(s), s)}'
                     f'<span style="font-size: 12px; color: #696973; font-variant-numeric: tabular-nums">{s}</span></span>')
    return f'<div style="display: flex; align-items: flex-end; gap: 18px">{"".join(cells)}</div>'


def card(letter, name, fn, desc, rec=False):
    chip = ('<span style="height: 26px; padding: 0 10px; border-radius: 999px; background: #1b1b1f; color: #ffffff; '
            'display: flex; align-items: center; font-size: 13px; font-weight: 600">선정</span>') if rec else ''
    ring = 'box-shadow: inset 0 0 0 2px #1b1b1f;' if rec else ''
    return f'''<article style="border-radius: 24px; background: #f4f4f6; padding: 32px; display: flex; flex-direction: column; gap: 24px; {ring}">
<div style="display: flex; align-items: center; gap: 10px"><span style="width: 30px; height: 30px; border-radius: 9px; background: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 15px; font-weight: 600">{letter}</span><h3 style="flex: 1 1 0; margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">{name}</h3>{chip}</div>
<div style="display: flex; align-items: flex-end; justify-content: space-between">{masked(fn(160), 160, extra='box-shadow: 0 8px 24px rgba(60,40,120,0.18);')}{size_row(fn)}</div>
<p style="margin: 0; font-size: 15px; line-height: 1.6; color: #3a3a40">{desc}</p>
</article>'''


def labeled(inner, label, color='#ffffff'):
    return (f'<span style="display: flex; flex-direction: column; align-items: center; gap: 10px">{inner}'
            f'<span style="font-size: 14px; font-weight: 600; color: {color}">{label}</span></span>')


ios_items = ''.join([
    labeled(masked(icon_a(112, 'default'), 112, extra=GLASS), '기본'),
    labeled(masked(icon_a(112, 'dark'), 112, extra=GLASS), '어두운'),
    # The clear variant paints no background; the frosted container stands in for it.
    labeled(masked(icon_a(112, 'clear'), 112,
                   extra='background: rgba(255,255,255,0.16); backdrop-filter: blur(12px) saturate(150%); box-shadow: inset 0 0 0 1.5px rgba(255,255,255,0.45), inset 0 1.5px 0 rgba(255,255,255,0.6), 0 6px 18px rgba(0,0,0,0.12);'), '맑은'),
    labeled(masked(icon_a(112, 'tinted'), 112, extra=GLASS), '색조'),
])


# A darker checkerboard keeps the white foreground layer visible.
checker = ('background-color: #dcdce2; background-image: linear-gradient(45deg, #c9c9d0 25%, transparent 25%), '
           'linear-gradient(-45deg, #c9c9d0 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #c9c9d0 75%), '
           'linear-gradient(-45deg, transparent 75%, #c9c9d0 75%); background-size: 16px 16px; '
           'background-position: 0 0, 0 8px, 8px -8px, -8px 0;')
safe = ('<span aria-hidden="true" style="position: absolute; left: 50%; top: 50%; width: 61%; height: 61%; '
        'transform: translate(-50%, -50%); border-radius: 50%; border: 1.5px dashed #c92f26"></span>')
fg_layer = (f'<span style="position: relative; flex: none; display: block; width: 88px; height: 88px; border-radius: 8px; overflow: hidden; {checker}">'
            f'{icon_a(88, "default", scale=0.8, bg=False)}{safe}</span>')
bg_layer = f'<span style="flex: none; display: block; width: 88px; height: 88px; border-radius: 8px; overflow: hidden">{icon_a(88, "default", fg=False)}</span>'
op = lambda t: f'<span aria-hidden="true" style="font-size: 22px; color: #696973; padding-bottom: 28px">{t}</span>'
android_items = ''.join([
    labeled(bg_layer, '배경', '#3a3a40'), op('+'), labeled(fg_layer, '전경', '#3a3a40'), op('→'),
    labeled(masked(icon_a(88, 'default', scale=0.8), 88, radius='50%'), '원형', '#3a3a40'),
    labeled(masked(icon_a(88, 'default', scale=0.8), 88, radius='30%'), '둥근 사각', '#3a3a40'),
    labeled(masked(icon_a(88, 'mono', scale=0.8), 88, radius='50%'), '테마', '#3a3a40'),
])


def generic(size, bg, glyph):
    return masked(f'<svg viewBox="0 0 60 60" width="{size}" height="{size}" aria-hidden="true" style="display: block"><rect width="60" height="60" fill="{bg}"></rect>{glyph}</svg>', size)


G = {
    '사진': ('#ffffff', '<path d="M14 42l10-12 8 9 5-6 9 9z" fill="#8e8e96"></path><circle cx="40" cy="20" r="5" fill="#c6c6cc"></circle>'),
    '지도': ('#d5e6d9', '<path d="M30 14a10 10 0 0 1 10 10c0 8-10 20-10 20S20 32 20 24a10 10 0 0 1 10-10z" fill="#5c6f63"></path><circle cx="30" cy="24" r="4" fill="#d5e6d9"></circle>'),
    '날씨': ('#8fb0d3', '<circle cx="25" cy="25" r="8" fill="#f3e3b5"></circle><path d="M20 42h20a7 7 0 0 0 0-14 9 9 0 0 0-17 3 6 6 0 0 0-3 11z" fill="#ffffff"></path>'),
    '메모': ('#ebe8e1', '<path d="M17 20h26M17 29h26M17 38h16" stroke="#8a857a" stroke-width="3.5" stroke-linecap="round"></path>'),
    '음악': ('#2f3440', '<path d="M25 40V20l16-4v20" fill="none" stroke="#ffffff" stroke-width="3.5" stroke-linejoin="round"></path><circle cx="21" cy="40" r="5" fill="#ffffff"></circle><circle cx="37" cy="36" r="5" fill="#ffffff"></circle>'),
    '시계': ('#1b1b1f', '<circle cx="30" cy="30" r="16" fill="none" stroke="#ffffff" stroke-width="3"></circle><path d="M30 21v10l6 4" fill="none" stroke="#ffffff" stroke-width="3" stroke-linecap="round"></path>'),
    '설정': ('#a7a7ad', '<circle cx="30" cy="30" r="11" fill="none" stroke="#ffffff" stroke-width="5" stroke-dasharray="5 3.6"></circle><circle cx="30" cy="30" r="5" fill="#ffffff"></circle>'),
}


def home_icon(label, inner):
    return (f'<span style="display: flex; flex-direction: column; align-items: center; gap: 6px">{inner}'
            f'<span style="font-size: 12px; font-weight: 600; color: #ffffff; text-shadow: 0 1px 2px rgba(0,0,0,0.25)">{label}</span></span>')


home = ''.join([
    home_icon('사진', generic(64, *G['사진'])),
    home_icon('그날어때', masked(icon_a(64, 'default'), 64, extra=GLASS)),
    home_icon('지도', generic(64, *G['지도'])),
    home_icon('날씨', generic(64, *G['날씨'])),
    home_icon('메모', generic(64, *G['메모'])),
    home_icon('음악', generic(64, *G['음악'])),
    home_icon('시계', generic(64, *G['시계'])),
    home_icon('설정', generic(64, *G['설정'])),
])

SPEC = [
    ('원본', '1024×1024 정사각형, 모서리를 깎지 않고 꽉 채웁니다. 모서리는 시스템이 깎습니다.'),
    ('iOS 26', 'Icon Composer에 배경과 전경(카드·고리·띠) 두 층으로 넣습니다. 유리 질감과 기본·어두운·맑은·색조 모습은 시스템이 만듭니다.'),
    ('안드로이드', '적응형 아이콘 108dp 두 층. 카드와 띠는 가운데 지름 66dp 안에 둡니다. 13부터는 단색 층을 더해 테마 아이콘을 지원합니다.'),
    ('스토어', 'App Store 1024×1024(투명 없음), Google Play 512×512 PNG.'),
    ('작게', '29px에서 흰 카드와 짙어지는 띠만 남아도 알아볼 수 있어야 합니다. 글자는 넣지 않습니다. 앱 이름은 아이콘 아래에 나옵니다.'),
    ('파일', '저장소 docs/design/icon/에 원본, 층별 SVG, 스토어용 PNG가 있습니다.'),
]
spec_items = ''.join(f'<li style="display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 16px; padding: 16px 0; border-top: 1px solid #e1e1e6"><b style="font-size: 15px; font-weight: 600">{k}</b><span style="font-size: 15px; line-height: 1.6; color: #3a3a40">{v}</span></li>' for k, v in SPEC)

HTML = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>앱 아이콘</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<style>
@font-face{{font-family:"Pretendard";src:url(/_blob/6c25f752af04cd1acf08b3de90d860de) format("woff2");font-weight:45 920;font-style:normal;font-display:swap}}
body{{margin:0}}
</style>
</helmet>
<div style="width: 1440px; height: __H__px; box-sizing: border-box; padding: 80px; background: #ffffff; color: #1b1b1f; font-family: Pretendard, -apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; letter-spacing: -0.01em; word-break: keep-all; overflow-wrap: break-word; display: flex; flex-direction: column; gap: 64px">

<header style="display: flex; flex-direction: column; gap: 16px">
<h1 style="margin: 0; font-size: 56px; font-weight: 600; letter-spacing: -0.03em">앱 아이콘</h1>
<p style="margin: 0; font-size: 21px; line-height: 1.55; color: #5c5c66">A 띠 달력으로 정했습니다. 브랜드 색 포도 위 흰 달력 카드에 그룹 캘린더의 비어 있는 사람 띠를 올렸습니다.</p>
</header>

<section style="display: flex; flex-direction: column; gap: 20px">
<h2 style="margin: 0; font-size: 28px; font-weight: 600; letter-spacing: -0.02em">검토한 세 방향 <span style="font-size: 16px; font-weight: 400; color: #5c5c66; letter-spacing: 0">오른쪽은 실제 크기(60·40·29px)</span></h2>
<div style="display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px">
{card('A', '띠 달력', icon_a, '그룹 캘린더의 비어 있는 사람 띠를 흰 달력 카드에 올렸습니다. 앱의 핵심 화면과 바로 이어지고, 작게 보면 띠가 연보라에서 포도로 짙어지는 한 줄로 읽힙니다.', rec=True)}
{card('B', '말풍선 달력', icon_b, '“그날 어때?”라고 묻는 말풍선 안에 고른 날 한 칸. 캘린더와 채팅을 한 모양에 담지만, 채팅 앱으로 먼저 읽힐 수 있습니다.')}
{card('C', '날', icon_c, '이름의 한 글자. 한국어 사용자에게는 바로 읽히지만 글자 아이콘은 흔하고, 획이 많아 작은 크기에서 답답합니다.')}
</div>
</section>

<section style="display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr); gap: 24px">
<div style="border-radius: 24px; padding: 32px; background: linear-gradient(160deg, #5d6f9c 0%, #a78bb5 55%, #e7b9a1 100%); display: flex; flex-direction: column; gap: 24px">
<h2 style="margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em; color: #ffffff">A · iOS 26의 네 가지 모습</h2>
<div style="display: flex; justify-content: space-between; padding: 0 8px">{ios_items}</div>
<p style="margin: 0; font-size: 14px; line-height: 1.6; color: rgba(255,255,255,0.92)">색조는 시스템이 회색조로 바꾼 뒤 사용자가 고른 색을 입힙니다. 예시는 노란색을 고른 경우입니다.</p>
</div>
<div style="border-radius: 24px; padding: 32px; background: #f4f4f6; display: flex; flex-direction: column; gap: 24px">
<h2 style="margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">A · 안드로이드 적응형 아이콘</h2>
<div style="display: flex; align-items: flex-end; justify-content: space-between">{android_items}</div>
<p style="margin: 0; font-size: 14px; line-height: 1.6; color: #3a3a40">빨간 점선이 지름 66dp 안전 영역입니다. 기기가 어떤 모양으로 잘라도 카드와 띠는 남습니다. 테마 아이콘은 단색 층의 투명도로 띠의 다섯 단계를 남깁니다.</p>
</div>
</section>

<section style="display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr); gap: 48px; align-items: start">
<div style="border-radius: 32px; padding: 40px 36px; background: linear-gradient(170deg, #6c7fb0 0%, #b49ac0 60%, #efc4a8 100%); display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 28px 12px">{home}</div>
<div style="display: flex; flex-direction: column; gap: 4px">
<h2 style="margin: 0 0 12px; font-size: 28px; font-weight: 600; letter-spacing: -0.02em">만들 때</h2>
<ul style="margin: 0; padding: 0; list-style: none">{spec_items}</ul>
</div>
</section>

</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":1440,"height":__H__}}}}'>
class Component extends DCLogic {{
  renderVals() {{
    return {{}};
  }}
}}
</script>
</body>
</html>
'''

if __name__ == '__main__':
    OUT = sys.argv[1]
    H = sys.argv[2] if len(sys.argv) > 2 else '1360'
    open(OUT, 'w', encoding='utf-8').write(HTML.replace('__H__', H))
    print('wrote', OUT, len(HTML), 'bytes')
