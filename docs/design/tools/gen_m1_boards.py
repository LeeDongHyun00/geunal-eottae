# Generate the remaining M1 settings boards from shared chrome so they match the existing boards.
import sys
from pathlib import Path

OUT = Path(sys.argv[1])

FONT = ('@font-face{font-family:"Pretendard";src:url(/_blob/6c25f752af04cd1acf08b3de90d860de) format("woff2");'
        'font-weight:45 920;font-style:normal;font-display:swap}')
ROOT = ("width: 390px; height: 844px; position: relative; overflow: hidden; background: %s; color: #1b1b1f; "
        "font-family: Pretendard, -apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; "
        "letter-spacing: -0.01em; word-break: keep-all; overflow-wrap: break-word")
CHEV_L = '<svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round"><path d="M14.5 6l-6 6 6 6"></path></svg>'
CHEV_R = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" style="flex: none; fill: none; stroke: #696973; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round"><path d="M9.5 6l6 6-6 6"></path></svg>'
PLUS = '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round"><path d="M12 5v14M5 12h14"></path></svg>'
CLOSE = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round"><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"></path></svg>'
CHECK = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round"><path d="M5.5 12.5l4 4 9-9"></path></svg>'
LOCK = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" style="flex: none; margin-top: 2px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round"><rect x="5" y="10.5" width="14" height="10" rx="2.5"></rect><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"></path></svg>'
WARN = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" style="flex: none; color: #c92f26; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round"><path d="M12 4l9 16H3z"></path><path d="M12 10v4M12 17.5v.5"></path></svg>'

SOFT = 'repeating-linear-gradient(135deg, #d3dfbb 0 4px, #e4ecd5 4px 8px)'


def page(title, body, script, bg='#f4f4f6', extra_css=''):
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<style>
{FONT}
body{{margin:0}}
a{{color:#1b1b1f;text-decoration:none}}a:hover{{color:#1b1b1f}}
button{{font:inherit;letter-spacing:inherit;color:inherit}}
input{{font:inherit;letter-spacing:inherit;color:inherit}}
input::placeholder{{color:#696973}}
{extra_css}</style>
</helmet>
<div style="{ROOT % bg}">
{body}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":390,"height":844}}}}'>
class Component extends DCLogic {{
{script}
}}
</script>
</body>
</html>
'''


def back_header(back_label, back_href, right=''):
    return (f'<div style="position: absolute; top: 54px; left: 0; width: 390px; height: 44px; box-sizing: border-box; padding: 0 8px; display: flex; align-items: center; justify-content: space-between">'
            f'<a href="{back_href}" style="height: 44px; padding-right: 8px; display: flex; align-items: center; gap: 2px; font-size: 17px">{CHEV_L}{back_label}</a>{right or "<span></span>"}</div>')


def title_block(top, title, sub=None):
    s = f'<p style="margin: 0; font-size: 15px; line-height: 1.5; color: #5c5c66">{sub}</p>' if sub else ''
    return (f'<div style="position: absolute; top: {top}px; left: 20px; width: 350px; display: flex; flex-direction: column; gap: 8px">'
            f'<h1 style="margin: 0; font-size: 30px; font-weight: 600; letter-spacing: -0.03em">{title}</h1>{s}</div>')


def group(label, rows, note=None):
    lab = f'<h2 style="margin: 0 0 0 16px; font-size: 13px; font-weight: 600; color: #5c5c66">{label}</h2>' if label else ''
    n = f'<p style="margin: 0 16px; font-size: 13px; line-height: 1.5; color: #5c5c66">{note}</p>' if note else ''
    return (f'<div style="display: flex; flex-direction: column; gap: 8px">{lab}'
            f'<div style="border-radius: 16px; background: #ffffff; display: flex; flex-direction: column">{rows}</div>{n}</div>')


def row(inner, last=False, tag='div', attrs='', height=52):
    border = '' if last else ' border-bottom: 1px solid #ebebef;'
    return f'<{tag} {attrs} style="min-height: {height}px; padding: 0 16px; display: flex; align-items: center; gap: 10px;{border}">{inner}</{tag}>'


def label_cell(title, sub=None):
    s = f'<span style="font-size: 13px; line-height: 1.45; color: #5c5c66">{sub}</span>' if sub else ''
    return f'<span style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 2px; padding: 10px 0"><span style="font-size: 17px">{title}</span>{s}</span>'


def nav_row(title, value='', href='#', sub=None, last=False):
    v = f'<span style="font-size: 15px; color: #5c5c66">{value}</span>' if value else ''
    return row(label_cell(title, sub) + v + CHEV_R, last, 'a', f'href="{href}"')


def switch(label, on_hole, click, tint='#1b1b1f'):
    # on_hole is the state key prefix: {prefix}On / {prefix}Track / {prefix}Side are rendered by renderVals.
    return (f'<button type="button" role="switch" aria-checked="{{{{{on_hole}On}}}}" aria-label="{label}" onClick="{{{{{click}}}}}" '
            f'style="flex: none; width: 51px; height: 31px; padding: 2px; border: none; border-radius: 16px; background: {{{{{on_hole}Track}}}}; '
            f'display: flex; justify-content: {{{{{on_hole}Side}}}}; cursor: pointer"><span style="width: 27px; height: 27px; border-radius: 14px; '
            f'background: #ffffff; box-shadow: 0 2px 4px rgba(0,0,0,0.18)"></span></button>')


def sw_vals(prefix, on, tint='#1b1b1f'):
    return {f'{prefix}On': 'true' if on else 'false', f'{prefix}Track': tint if on else '#e1e1e6', f'{prefix}Side': 'flex-end' if on else 'flex-start'}


def primary(label, href=None, click=None, bg='#1b1b1f', fg='#ffffff'):
    if href:
        return f'<a href="{href}" style="height: 52px; border-radius: 999px; background: {bg}; color: {fg}; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600">{label}</a>'
    return f'<button type="button" onClick="{{{{{click}}}}}" style="height: 52px; border: none; border-radius: 999px; background: {bg}; color: {fg}; font-size: 17px; font-weight: 600; cursor: pointer">{label}</button>'


def sheet(top, inner, aria):
    return (f'<section role="dialog" aria-modal="true" aria-label="{aria}" style="position: absolute; left: 0; top: {top}px; width: 390px; height: {844 - top}px; '
            f'box-sizing: border-box; padding: 8px 20px 34px; background: #ffffff; border-radius: 20px 20px 0 0; box-shadow: 0 -8px 32px rgba(0,0,0,0.12); '
            f'display: flex; flex-direction: column; gap: 16px"><div aria-hidden="true" style="align-self: center; width: 36px; height: 5px; border-radius: 3px; background: #d1d1d6"></div>{inner}</section>')


def sheet_head(title, sub, close_href):
    s = f'<span style="font-size: 14px; line-height: 1.45; color: #5c5c66">{sub}</span>' if sub else ''
    return (f'<div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-top: -6px">'
            f'<div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">{title}</h2>{s}</div>'
            f'<a href="{close_href}" aria-label="닫기" style="flex: none; width: 44px; height: 44px; margin: -6px -12px 0 0; display: flex; align-items: center; justify-content: center; color: #5c5c66">{CLOSE}</a></div>')


def field(label, value, placeholder=''):
    return (f'<label style="height: 52px; box-sizing: border-box; padding: 0 16px; border-radius: 12px; background: #f4f4f6; display: flex; align-items: center; gap: 12px">'
            f'<span style="flex: none; font-size: 15px; color: #5c5c66">{label}</span>'
            f'<input type="text" value="{value}" placeholder="{placeholder}" style="flex: 1 1 0; width: 0; min-width: 0; border: none; background: transparent; font-size: 17px; outline: none"></label>')


def value_row(label, value, last=False):
    return row(f'<span style="flex: 1 1 0; font-size: 17px">{label}</span><span style="font-size: 17px; color: #5c5c66; font-variant-numeric: tabular-nums">{value}</span>' + CHEV_R, last)


def scrim_bg(inner):
    return f'<div aria-hidden="true" style="position: absolute; inset: 0">{inner}<div style="position: absolute; inset: 0; background: rgba(10,10,14,0.32)"></div></div>'


def danger_text(label, click=None, href=None):
    if href:
        return f'<a href="{href}" style="height: 48px; border-radius: 12px; background: #ffffff; color: #c92f26; display: flex; align-items: center; justify-content: center; font-size: 17px">{label}</a>'
    return f'<button type="button" onClick="{{{{{click}}}}}" style="height: 48px; border: none; border-radius: 12px; background: #ffffff; color: #c92f26; font-size: 17px; cursor: pointer">{label}</button>'


boards = {}

# ---------- 캘린더 관리 ----------
cal_rows = '''<sc-for list="{{cals}}" as="c" hint-placeholder-count="4">
<div style="min-height: 60px; padding: 0 16px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #ebebef">
<a href="CalendarEdit.dc.html" aria-label="{{c.label}}" style="flex: 1 1 0; min-width: 0; display: flex; align-items: center; gap: 12px">
<span aria-hidden="true" style="flex: none; width: 14px; height: 14px; border-radius: 4px; background: {{c.c}}; box-shadow: {{c.ring}}"></span>
<span style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 2px; padding: 10px 0"><span style="font-size: 17px">{{c.name}}</span><span style="font-size: 13px; color: #5c5c66">{{c.sub}}</span></span>
</a>
<button type="button" role="switch" aria-checked="{{c.on}}" aria-label="{{c.name}} 표시" onClick="{{c.toggle}}" style="flex: none; width: 51px; height: 31px; padding: 2px; border: none; border-radius: 16px; background: {{c.track}}; display: flex; justify-content: {{c.side}}; cursor: pointer"><span style="width: 27px; height: 27px; border-radius: 14px; background: #ffffff; box-shadow: 0 2px 4px rgba(0,0,0,0.18)"></span></button>
</div>
</sc-for>
<a href="CalendarEdit.dc.html" style="min-height: 48px; padding: 0 16px; display: flex; align-items: center; gap: 10px; font-size: 17px">''' + PLUS.replace('width="24" height="24"', 'width="20" height="20"') + '''새 캘린더</a>'''

dev_rows = row(label_cell('기기 캘린더 가져오기', '읽기만 해요. 제목은 이 기기 밖으로 나가지 않아요.') + switch('기기 캘린더 가져오기', 'dev', 'toggleDev')) + '''<sc-for list="{{devs}}" as="d" hint-placeholder-count="2">
<div style="min-height: 52px; padding: 0 16px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #ebebef; opacity: {{devOp}}">
<span aria-hidden="true" style="flex: none; width: 14px; height: 14px; border-radius: 4px; background: #d5b9aa"></span>
<span style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 2px; padding: 8px 0"><span style="font-size: 17px">{{d.name}}</span><span style="font-size: 13px; color: #5c5c66">{{d.sub}}</span></span>
<button type="button" role="checkbox" aria-checked="{{d.on}}" aria-label="{{d.name}} 가져오기" onClick="{{d.toggle}}" style="flex: none; width: 24px; height: 24px; box-sizing: border-box; padding: 0; border: none; border-radius: 6px; background: {{d.bg}}; box-shadow: {{d.ring}}; color: #ffffff; display: flex; align-items: center; justify-content: center; cursor: pointer">''' + CHECK + '''</button>
</div>
</sc-for>''' + row('<span style="font-size: 13px; line-height: 1.5; color: #5c5c66">가져온 일정은 내 캘린더에 ‘기기 캘린더’ 색으로 보이고, 그룹에는 비어 있는지만 보여요. 이 앱에서 고치거나 지울 수 없어요.</span>', last=True, height=44)

boards['Calendars'] = page('캘린더 관리',
    back_header('나', 'Me.dc.html', f'<a href="CalendarEdit.dc.html" aria-label="새 캘린더" style="width: 44px; height: 44px; display: flex; align-items: center; justify-content: center">{PLUS}</a>')
    + title_block(102, '캘린더 관리', '색은 내 화면의 일정 색이에요. 그룹에는 어느 캘린더든 비어 있는지만 보여요.')
    + '<div style="position: absolute; top: 210px; left: 16px; width: 358px; display: flex; flex-direction: column; gap: 24px">'
    + group('내 캘린더 · 4개', cal_rows)
    + group('기기 캘린더', dev_rows)
    + '</div>',
    '''  constructor(props) {
    super(props);
    this.state = { on: { school: true, cafe: true, personal: true, gym: true }, dev: true, picked: { icloud: true, work: false } };
  }
  renderVals() {
    const soft = \'''' + SOFT + '''\';
    const CALS = [
      { id: 'school', name: '학교', c: '#a0c0fa', sub: '바다 · 일정 42개' },
      { id: 'cafe', name: '알바', c: '#77d1c2', sub: '민트 · 교대 템플릿 3개' },
      { id: 'personal', name: '개인', c: '#efb08a', sub: '귤 · 일정 18개' },
      { id: 'gym', name: '운동', c: soft, sub: '올리브 · 소프트 캘린더, 그룹에는 비어 있음으로' },
    ];
    const { on, dev, picked } = this.state;
    const cals = CALS.map((c) => ({
      name: c.name, c: c.c, sub: c.sub, ring: c.id === 'gym' ? 'inset 0 0 0 1px #c5d1a6' : 'none',
      label: c.name + ' 캘린더 편집, ' + c.sub,
      on: on[c.id] ? 'true' : 'false', track: on[c.id] ? '#1b1b1f' : '#e1e1e6', side: on[c.id] ? 'flex-end' : 'flex-start',
      toggle: () => this.setState({ on: Object.assign({}, on, { [c.id]: !on[c.id] }) }),
    }));
    const DEVS = [
      { id: 'icloud', name: 'iCloud · 개인', sub: '일정 9개' },
      { id: 'work', name: 'Google · 회사', sub: '일정 31개' },
    ];
    const devs = DEVS.map((d) => ({
      name: d.name, sub: d.sub,
      on: picked[d.id] ? 'true' : 'false', bg: picked[d.id] ? '#1b1b1f' : '#ffffff', ring: picked[d.id] ? 'none' : 'inset 0 0 0 2px #c7c7cc',
      toggle: () => dev && this.setState({ picked: Object.assign({}, picked, { [d.id]: !picked[d.id] }) }),
    }));
    return Object.assign({ cals, devs, devOp: dev ? 1 : 0.4, toggleDev: () => this.setState({ dev: !dev }) },
      { devOn: dev ? 'true' : 'false', devTrack: dev ? '#1b1b1f' : '#e1e1e6', devSide: dev ? 'flex-end' : 'flex-start' });
  }''')

# ---------- 캘린더 편집 (시트) ----------
swatches = '''<div role="radiogroup" aria-label="캘린더 색" style="display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px">
<sc-for list="{{colors}}" as="k" hint-placeholder-count="12">
<button type="button" role="radio" aria-checked="{{k.on}}" aria-label="{{k.name}}" onClick="{{k.pick}}" style="height: 44px; padding: 0; border: none; border-radius: 12px; background: {{k.c}}; box-shadow: {{k.ring}}; color: #ffffff; display: flex; align-items: center; justify-content: center; cursor: pointer"><span style="display: {{k.tick}}">''' + CHECK + '''</span></button>
</sc-for>
</div>'''
bg_cal = (back_header('나', 'Me.dc.html') + title_block(102, '캘린더 관리')
          + '<div style="position: absolute; top: 170px; left: 16px; width: 358px">' + group('내 캘린더 · 4개', row(label_cell('학교', '바다 · 일정 42개')) + row(label_cell('알바', '민트')) + row(label_cell('개인', '귤'), last=True)) + '</div>')
boards['CalendarEdit'] = page('캘린더 편집',
    scrim_bg(bg_cal) + sheet(188,
        sheet_head('운동', '소프트 캘린더 · 올리브', 'Calendars.dc.html')
        + field('이름', '운동')
        + swatches
        + group('', row(label_cell('소프트 캘린더', '나에게만 보이고, 그룹에는 비어 있는 시간으로 보여요. 헬스장, 혼자 하는 공부처럼 약속이 생기면 미룰 수 있는 것에 써요.') + switch('소프트 캘린더', 'soft', 'toggleSoft'))
                   + value_row('기본 알림', '10분 전', last=True))
        + '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px">' + primary('저장', href='Calendars.dc.html') + danger_text('캘린더 삭제 · 일정 7개도 지워져요', click='noop') + '</div>',
        '캘린더 편집'),
    '''  constructor(props) {
    super(props);
    this.state = { color: 3, soft: true };
  }
  renderVals() {
    const C = [['토마토', '#de685d'], ['귤', '#d57128'], ['겨자', '#b58400'], ['올리브', '#7d9911'], ['숲', '#32a256'], ['민트', '#00a090'],
      ['하늘', '#0099c6'], ['바다', '#5a8be9'], ['포도', '#937ce3'], ['자두', '#c16dbf'], ['벚꽃', '#d86691'], ['코코아', '#ae836c']];
    const { color, soft } = this.state;
    const colors = C.map(([name, c], i) => ({ name, c, on: i === color ? 'true' : 'false', tick: i === color ? 'flex' : 'none',
      ring: i === color ? '0 0 0 2px #ffffff, 0 0 0 4px #1b1b1f' : 'none', pick: () => this.setState({ color: i }) }));
    return Object.assign({ colors, noop: () => {}, toggleSoft: () => this.setState({ soft: !soft }) },
      { softOn: soft ? 'true' : 'false', softTrack: soft ? '#1b1b1f' : '#e1e1e6', softSide: soft ? 'flex-end' : 'flex-start' });
  }''')

# ---------- 사람 추가 (시트) ----------
seg = '''<div role="radiogroup" aria-label="달력 종류" style="height: 40px; box-sizing: border-box; padding: 2px; border-radius: 999px; background: #ebebef; display: flex">
<button type="button" role="radio" aria-checked="{{solarOn}}" onClick="{{setSolar}}" style="flex: 1 1 0; border: none; border-radius: 999px; background: {{solarBg}}; box-shadow: {{solarShadow}}; color: {{solarFg}}; font-size: 14px; font-weight: 600; cursor: pointer">양력</button>
<button type="button" role="radio" aria-checked="{{lunarOn}}" onClick="{{setLunar}}" style="flex: 1 1 0; border: none; border-radius: 999px; background: {{lunarBg}}; box-shadow: {{lunarShadow}}; color: {{lunarFg}}; font-size: 14px; font-weight: 600; cursor: pointer">음력</button>
</div>'''
bg_people = (back_header('나', 'Me.dc.html') + title_block(102, '사람·생일')
             + '<div style="position: absolute; top: 170px; left: 16px; width: 358px">' + group('다가오는 생일', row(label_cell('민수', '10월 30일 금요일')) + row(label_cell('엄마', '음력 10월 3일'), last=True)) + '</div>')
boards['PersonEdit'] = page('사람 추가',
    scrim_bg(bg_people) + sheet(168,
        sheet_head('사람 추가', '이름과 생일은 이 기기와 암호화 백업에만 있어요.', 'People.dc.html')
        + field('이름', '', '이름')
        + '<div style="display: flex; flex-direction: column; gap: 10px">' + seg
        + group('', value_row('생일', '{{dateText}}') + row(label_cell('태어난 해', '몰라도 돼요. 나이는 보여 주지 않아요.') + '<span style="font-size: 17px; color: #5c5c66">선택</span>' + CHEV_R, last=True), note='{{lunarNote}}')
        + '</div>'
        + group('', value_row('알림', '당일 09:00') + row(label_cell('날짜 잡기 추천에 쓰기', '생일 7일 안이면 추천 칩에 ‘OO 생일 약속’이 떠요') + switch('날짜 잡기 추천에 쓰기', 'sug', 'toggleSug'), last=True))
        + '<a href="PersonEdit.dc.html" style="align-self: flex-start; height: 36px; padding: 0 12px; border-radius: 999px; background: #f4f4f6; display: flex; align-items: center; font-size: 14px; font-weight: 600">연락처에서 가져오기</a>'
        + '<div style="margin-top: auto">' + primary('저장', href='People.dc.html') + '</div>',
        '사람 추가'),
    '''  constructor(props) {
    super(props);
    this.state = { lunar: false, sug: true };
  }
  renderVals() {
    const { lunar, sug } = this.state;
    const seg = (on) => ({ bg: on ? '#ffffff' : 'transparent', fg: on ? '#1b1b1f' : '#5c5c66', shadow: on ? '0 1px 3px rgba(0,0,0,0.12)' : 'none' });
    const s = seg(!lunar), l = seg(lunar);
    return Object.assign({
      solarOn: lunar ? 'false' : 'true', solarBg: s.bg, solarFg: s.fg, solarShadow: s.shadow,
      lunarOn: lunar ? 'true' : 'false', lunarBg: l.bg, lunarFg: l.fg, lunarShadow: l.shadow,
      setSolar: () => this.setState({ lunar: false }), setLunar: () => this.setState({ lunar: true }),
      dateText: lunar ? '음력 10월 3일' : '10월 30일',
      lunarNote: lunar ? '매년 양력으로 바꿔서 넣어요. 올해는 11월 11일 수요일이에요.' : '음력 생일이면 음력을 고르세요. 매년 양력 날짜로 바꿔 드려요.',
      toggleSug: () => this.setState({ sug: !sug }),
    }, { sugOn: sug ? 'true' : 'false', sugTrack: sug ? '#1b1b1f' : '#e1e1e6', sugSide: sug ? 'flex-end' : 'flex-start' });
  }''')

# ---------- 교대 템플릿 편집 (시트) ----------
bg_shift = ('<div style="position: absolute; top: 54px; left: 0; width: 390px; height: 44px; box-sizing: border-box; padding: 0 20px; display: flex; align-items: center; justify-content: space-between"><span style="font-size: 17px">취소</span><span style="font-size: 17px; font-weight: 600">교대 찍기</span><span style="height: 36px; padding: 0 14px; border-radius: 999px; background: #1b1b1f; color: #ffffff; display: flex; align-items: center; font-size: 15px; font-weight: 600">완료</span></div>'
            '<div style="position: absolute; top: 110px; left: 20px; width: 350px; height: 300px; border-radius: 16px; background: #f4f4f6"></div>')
preview = ('<div style="display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 16px; background: #f4f4f6">'
           '<span aria-hidden="true" style="flex: none; width: 6px; height: 40px; border-radius: 3px; background: {{tint}}"></span>'
           '<span style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 17px; font-weight: 600">{{name}}</span><span style="font-size: 15px; color: #5c5c66; font-variant-numeric: tabular-nums">{{range}} · {{hours}}</span></span>'
           '<span style="margin-left: auto; font-size: 13px; color: #5c5c66">달력에 이렇게 찍혀요</span></div>')
boards['TemplateEdit'] = page('교대 템플릿 편집',
    scrim_bg(bg_shift) + sheet(168,
        sheet_head('템플릿 편집', '찍은 근무는 고른 캘린더의 일반 일정이 돼요.', 'Shift.dc.html')
        + preview
        + field('이름', '오픈')
        + group('', value_row('시작', '08:00') + value_row('끝', '13:00') + nav_row('캘린더', '알바 · 민트', 'Calendars.dc.html') + value_row('알림', '30분 전', last=True),
                note='자정을 넘기는 근무(22:00–02:00)는 다음 날까지 한 일정으로 들어가요.')
        + group('', row(label_cell('근무 시간 집계', '이 템플릿으로 찍은 시간의 합계와 예상 급여. 그날어때 플러스') + '<span style="height: 26px; padding: 0 10px; border-radius: 999px; background: #d9d4f8; display: flex; align-items: center; font-size: 12px; font-weight: 600">플러스</span>', last=True))
        + '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px">' + primary('저장', href='Shift.dc.html') + danger_text('템플릿 삭제 · 이미 찍은 근무는 남아요', click='noop') + '</div>',
        '템플릿 편집'),
    '''  renderVals() {
    return { name: '오픈', range: '08:00–13:00', hours: '5시간', tint: '#19bdaa', noop: () => {} };
  }''')

# ---------- 알림 설정 ----------
boards['NotifySettings'] = page('알림 설정',
    back_header('나', 'Me.dc.html')
    + title_block(102, '알림', '모두 이 기기에서 울리는 알림이에요. 내용은 서버를 거치지 않아요.')
    + '<div style="position: absolute; top: 214px; left: 16px; width: 358px; display: flex; flex-direction: column; gap: 20px">'
    + group('일정', value_row('기본 알림', '10분 전') + value_row('종일 일정', '전날 18:00') + row(label_cell('다음 할 일 막대', '시작 전까지 남은 시간을 함께 보여 줘요') + switch('다음 할 일 막대', 'bar', 'toggleBar'), last=True),
            note='일정마다 편집 화면에서 따로 바꿀 수 있어요. 바꾼 일정은 기본값을 따르지 않아요.')
    + group('사람·생일', row(label_cell('생일 알림', '당일 09:00') + switch('생일 알림', 'bday', 'toggleBday')) + row(label_cell('연휴·연차 제안', '공휴일 사이 평일 하루를 쉬면 4일 연휴가 될 때 알려 줘요') + switch('연휴·연차 제안', 'leave', 'toggleLeave'), last=True))
    + group('그룹', row(label_cell('채팅과 약속', '그룹에 들어가면 그룹마다 채팅·약속 알림을 따로 끌 수 있어요. 전체 끄기는 1시간·오늘·계속 중에서 골라요.') + '<span style="height: 26px; padding: 0 10px; border-radius: 999px; background: #ebebef; display: flex; align-items: center; font-size: 12px; font-weight: 600; color: #5c5c66">M2</span>', last=True))
    + group('', nav_row('시스템 알림 설정', '켜짐', 'NotifySettings.dc.html', '소리, 잠금 화면 표시, 요약은 설정 앱에서', last=True))
    + '</div>',
    '''  constructor(props) {
    super(props);
    this.state = { bar: true, bday: true, leave: true };
  }
  renderVals() {
    const sw = (k) => ({ [k + 'On']: this.state[k] ? 'true' : 'false', [k + 'Track']: this.state[k] ? '#1b1b1f' : '#e1e1e6', [k + 'Side']: this.state[k] ? 'flex-end' : 'flex-start' });
    const t = (k) => () => this.setState({ [k]: !this.state[k] });
    return Object.assign({ toggleBar: t('bar'), toggleBday: t('bday'), toggleLeave: t('leave') }, sw('bar'), sw('bday'), sw('leave'));
  }''')

# ---------- 암호화 백업 ----------
backup_list = '''<sc-for list="{{backups}}" as="b" hint-placeholder-count="3">
<div style="min-height: 52px; padding: 0 16px; display: flex; align-items: center; gap: 10px; border-bottom: 1px solid #ebebef">
<span style="flex: 1 1 0; display: flex; flex-direction: column; gap: 2px; padding: 8px 0"><span style="font-size: 17px; font-variant-numeric: tabular-nums">{{b.when}}</span><span style="font-size: 13px; color: #5c5c66">{{b.sub}}</span></span>
<span style="font-size: 15px; color: #5c5c66; font-variant-numeric: tabular-nums">{{b.size}}</span>
</div>
</sc-for>'''
incl = ('<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px">'
        '<div style="padding: 14px 16px; border-radius: 16px; background: #ffffff; display: flex; flex-direction: column; gap: 8px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">들어가는 것</span><span style="font-size: 15px; line-height: 1.5">내 일정과 캘린더<br>사람·생일<br>교대 템플릿<br>설정<br>채팅 글(M3)</span></div>'
        '<div style="padding: 14px 16px; border-radius: 16px; background: #ffffff; display: flex; flex-direction: column; gap: 8px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">안 들어가는 것</span><span style="font-size: 15px; line-height: 1.5; color: #3a3a40">사진·파일<br>기기 캘린더 일정<br>그룹의 상태<br><span style="font-size: 13px; color: #5c5c66">보관 기간 안이면 다시 받아요</span></span></div></div>')
boards['Backup'] = page('암호화 백업',
    back_header('나', 'Me.dc.html')
    + title_block(102, '암호화 백업', '기기에서 잠근 뒤 올려요. 복구 코드가 있어야 풀 수 있고, 그날어때는 열어 볼 수 없어요.')
    + '<div style="position: absolute; top: 222px; left: 16px; width: 358px; display: flex; flex-direction: column; gap: 24px">'
    + '<div style="padding: 16px; border-radius: 16px; background: #ffffff; display: flex; align-items: center; gap: 12px">'
    + '<span style="flex: 1 1 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">{{statusLabel}}</span><span style="font-size: 20px; font-weight: 600; letter-spacing: -0.015em; font-variant-numeric: tabular-nums">{{statusText}}</span><span style="font-size: 13px; color: #5c5c66">{{statusSub}}</span></span>'
    + '<button type="button" onClick="{{backupNow}}" disabled="{{busy}}" style="flex: none; height: 40px; padding: 0 14px; border: none; border-radius: 999px; background: {{btnBg}}; color: {{btnFg}}; font-size: 14px; font-weight: 600; cursor: pointer">{{btnLabel}}</button></div>'
    + group('', row(label_cell('자동 백업', '와이파이에서 하루 한 번. 바뀐 게 없으면 건너뛰어요') + switch('자동 백업', 'auto', 'toggleAuto'), last=True))
    + group('최근 백업 · 3개까지 보관', backup_list)
    + incl
    + '</div>',
    '''  constructor(props) {
    super(props);
    this.state = { auto: true, phase: 'idle' };
  }
  renderVals() {
    const { auto, phase } = this.state;
    const backups = phase === 'done'
      ? [{ when: '방금', sub: '일정 128개 · 사람 12명', size: '2.4MB' }, { when: '오늘 03:12', sub: '자동', size: '2.4MB' }, { when: '어제 03:10', sub: '자동', size: '2.3MB' }]
      : [{ when: '오늘 03:12', sub: '자동 · 일정 128개 · 사람 12명', size: '2.4MB' }, { when: '어제 03:10', sub: '자동', size: '2.3MB' }, { when: '10월 23일 03:11', sub: '자동', size: '2.3MB' }];
    const busy = phase === 'running';
    const vals = {
      backups, busy,
      statusLabel: phase === 'running' ? '백업하는 중' : '마지막 백업',
      statusText: phase === 'running' ? '잠그고 올리는 중…' : phase === 'done' ? '방금' : '오늘 03:12',
      statusSub: phase === 'running' ? '2.4MB · 와이파이' : '일정 128개 · 사람 12명 · 2.4MB',
      btnLabel: phase === 'running' ? '올리는 중' : '지금 백업',
      btnBg: busy ? '#ebebef' : '#1b1b1f', btnFg: busy ? '#696973' : '#ffffff',
      backupNow: () => { this.setState({ phase: 'running' }); setTimeout(() => this.setState({ phase: 'done' }), 1200); },
      toggleAuto: () => this.setState({ auto: !auto }),
    };
    return Object.assign(vals, { autoOn: auto ? 'true' : 'false', autoTrack: auto ? '#1b1b1f' : '#e1e1e6', autoSide: auto ? 'flex-end' : 'flex-start' });
  }''')

# ---------- 기기 바꾸기 (복구 코드 입력) ----------
# Props: mode picks the device-replace or guest-return variant; error shows one of the contract's failure states.
word_grid = """<ol aria-label="복구 코드 12단어 입력" style="margin: 0; padding: 0; list-style: none; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px">
<sc-for list="{{words}}" as="w" hint-placeholder-count="12">
<li style="height: 48px; box-sizing: border-box; padding: 0 10px; border-radius: 12px; background: {{w.bg}}; box-shadow: {{w.ring}}; display: flex; align-items: center; gap: 8px"><span style="font-size: 12px; color: #696973; font-variant-numeric: tabular-nums">{{w.n}}</span><span style="font-size: 16px; font-weight: 600; color: {{w.fg}}">{{w.t}}</span></li>
</sc-for>
</ol>"""
done_card = """<sc-if value="{{done}}" hint-placeholder-val="{{false}}">
<div style="position: absolute; inset: 0; background: #ffffff">
<div style="position: absolute; top: 250px; left: 32px; width: 326px; display: flex; flex-direction: column; align-items: center; gap: 14px; text-align: center">
<span aria-hidden="true" style="width: 64px; height: 64px; border-radius: 32px; background: #1b1b1f; color: #ffffff; display: flex; align-items: center; justify-content: center"><svg viewBox="0 0 24 24" width="30" height="30" style="fill: none; stroke: currentColor; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round"><path d="M5.5 12.5l4 4 9-9"></path></svg></span>
<h2 style="margin: 4px 0 0; font-size: 26px; font-weight: 600; letter-spacing: -0.025em">돌아왔어요</h2>
<p style="margin: 0; font-size: 16px; line-height: 1.55; color: #3a3a40">{{doneText}}</p>
<p style="margin: 0; padding: 10px 14px; border-radius: 12px; background: #f4f4f6; font-size: 14px; line-height: 1.5; color: #3a3a40">{{doneNote}}</p>
</div>
<div style="position: absolute; left: 20px; bottom: 34px; width: 350px">""" + primary('캘린더 열기', href='Month.dc.html') + """</div>
</div>
</sc-if>"""
# The no-code sheet asks first; "백업 없이 새로 시작" then needs a second, destructive confirmation because it deletes
# the account's backups for good (protocol §10 "새 키로 시작"). The offer variant (a provider sign-in that found recovery
# material, no other device) also lets the person put the code in later and use the calendar meanwhile.
RED_TEXT = 'height: 48px; border: none; border-radius: 12px; background: #f4f4f6; color: #c92f26; display: flex; align-items: center; justify-content: center; font-size: 17px; cursor: pointer; width: 100%'
PLAIN = 'height: 52px; border: none; border-radius: 999px; background: #f4f4f6; color: #1b1b1f; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600; cursor: pointer; width: 100%'
GHOST = 'height: 52px; border: none; border-radius: 999px; background: none; font-size: 17px; font-weight: 600; cursor: pointer'
def ask_sheet(top, later):
    buttons = ''
    if later:
        buttons += '<a href="Permissions.dc.html" style="' + PLAIN + '; text-decoration: none">나중에 넣을게요 · 캘린더부터 쓰기</a>'
    buttons += ('<sc-if value="{{lostIsGuest}}" hint-placeholder-val="{{false}}"><a href="Permissions.dc.html" style="' + RED_TEXT + '; text-decoration: none">새 게스트 계정으로 시작</a></sc-if>'
                '<sc-if value="{{lostNotGuest}}" hint-placeholder-val="{{true}}"><button type="button" onClick="{{startFresh}}" style="' + RED_TEXT + '">백업 없이 새로 시작</button></sc-if>'
                '<button type="button" onClick="{{closeLost}}" style="' + GHOST + '">이전 기기에서 찾아볼게요</button>')
    return sheet(top, '<h2 style="margin: -6px 0 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">{{lostTitle}}</h2>'
                 '<p style="margin: 0; font-size: 15px; line-height: 1.55; color: #3a3a40">{{lostA}}</p>'
                 '<p style="margin: 0; font-size: 15px; line-height: 1.55; color: #3a3a40">{{lostB}}</p>'
                 '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px">' + buttons + '</div>', '복구 코드가 없을 때')
confirm_sheet = sheet(372, '<h2 style="margin: -6px 0 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">백업을 지우고 새로 시작할까요?</h2>'
    '<p style="margin: 0; font-size: 15px; line-height: 1.55; color: #3a3a40">{{confirmA}}</p>'
    '<p role="alert" style="margin: 0; display: flex; gap: 10px; padding: 12px 14px; border-radius: 12px; background: #fbeceb; font-size: 14px; line-height: 1.55; color: #3a3a40">' + WARN + '<span>되돌릴 수 없어요. 지운 백업은 복구 코드를 나중에 찾아도 열 수 없어요.</span></p>'
    '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px"><a href="Permissions.dc.html" style="height: 52px; border-radius: 999px; background: #c92f26; color: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600; text-decoration: none">백업 3개 지우고 새로 시작</a><button type="button" onClick="{{backToAsk}}" style="' + GHOST + '">지우지 않을게요</button></div>', '백업을 지우고 새로 시작')
lost_sheets = ('<sc-if value="{{lost}}" hint-placeholder-val="{{false}}"><div style="position: absolute; inset: 0">'
    '<button type="button" aria-label="닫기" onClick="{{closeLost}}" style="position: absolute; inset: 0; width: 100%; height: 100%; padding: 0; border: none; background: rgba(10,10,14,0.32); cursor: pointer"></button>'
    '<sc-if value="{{lostAsk}}" hint-placeholder-val="{{true}}">' + ask_sheet(432, False) + '</sc-if>'
    '<sc-if value="{{lostAskLater}}" hint-placeholder-val="{{false}}">' + ask_sheet(372, True) + '</sc-if>'
    '<sc-if value="{{lostConfirm}}" hint-placeholder-val="{{false}}">' + confirm_sheet + '</sc-if>'
    '</div></sc-if>')
boards['Restore'] = page('기기 바꾸기',
    back_header('로그인', 'SignIn.dc.html')
    + '<div style="position: absolute; top: 102px; left: 20px; width: 350px; display: flex; flex-direction: column; gap: 8px"><h1 style="margin: 0; font-size: 30px; font-weight: 600; letter-spacing: -0.03em">{{title}}</h1><p style="margin: 0; font-size: 15px; line-height: 1.5; color: #5c5c66">{{intro}}</p></div>'
    + '<div style="position: absolute; top: 244px; left: 20px; width: 350px; display: flex; flex-direction: column; gap: 14px">'
    + '<div style="display: flex; align-items: center; justify-content: space-between"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">{{progress}}</span><button type="button" onClick="{{paste}}" style="height: 36px; padding: 0 12px; border: none; border-radius: 999px; background: #f4f4f6; font-size: 14px; font-weight: 600; cursor: pointer">붙여넣기</button></div>'
    + word_grid
    + '<sc-if value="{{hasError}}" hint-placeholder-val="{{false}}"><p role="alert" style="margin: 0; display: flex; gap: 10px; padding: 12px 14px; border-radius: 12px; background: #fbeceb; font-size: 14px; line-height: 1.55; color: #3a3a40">' + WARN + '<span>{{errorText}}</span></p></sc-if>'
    + '<sc-if value="{{noError}}" hint-placeholder-val="{{true}}"><p style="margin: 0; display: flex; gap: 10px; padding: 12px 14px; border-radius: 12px; background: #f4f4f6; font-size: 13px; line-height: 1.55; color: #3a3a40">' + LOCK + '<span>단어는 이 기기에서만 쓰여요. 두 글자를 넣으면 단어가 정해지고, 목록에 없는 단어는 바로 빨갛게 표시돼요. 횟수 제한은 없어요.</span></p></sc-if>'
    + '</div>'
    + '<div style="position: absolute; left: 20px; bottom: 34px; width: 350px; display: flex; flex-direction: column; gap: 8px">'
    + '<sc-if value="{{ready}}" hint-placeholder-val="{{false}}">' + primary('{{cta}}', click='restore') + '</sc-if>'
    + '<sc-if value="{{notReady}}" hint-placeholder-val="{{true}}"><button type="button" disabled="{{true}}" style="height: 52px; border: none; border-radius: 999px; background: #ebebef; color: #696973; font-size: 17px; font-weight: 600">{{notReadyLabel}}</button></sc-if>'
    + '<button type="button" onClick="{{noCode}}" style="height: 44px; border: none; background: none; font-size: 15px; color: #5c5c66; cursor: pointer">복구 코드가 없어요</button>'
    + '</div>'
    + lost_sheets + done_card,
    """  constructor(props) {
    super(props);
    this.state = { filled: -1, done: false, lost: false, confirm: false };
  }
  renderVals() {
    // Example words from the BIP39 Korean list, shown in NFC.
    const WORDS = ['바람', '연필', '구름', '달력', '열쇠', '우산', '단추', '마당', '편지', '소풍', '거울', '하늘'];
    const mode = this.props.mode ?? '기기 교체';
    const guest = mode === '게스트 복귀';
    const offer = mode === '백업 제안';
    const error = this.props.error ?? '없음';
    const { done, lost, confirm } = this.state;
    const filled = this.state.filled >= 0 ? this.state.filled : (error === '없음' ? 7 : error === '단어 없음' ? 9 : 12);
    const badIndex = error === '단어 없음' ? 7 : -1;
    const words = WORDS.map((t, i) => ({
      n: String(i + 1), t: i < filled ? (i === badIndex ? '바다' : t) : '',
      bg: i === badIndex ? '#fbeceb' : i < filled ? '#f4f4f6' : '#ffffff',
      fg: i === badIndex ? '#c92f26' : '#1b1b1f',
      ring: i === badIndex ? 'inset 0 0 0 2px #c92f26' : i === filled ? 'inset 0 0 0 2px #1b1b1f' : i < filled ? 'none' : 'inset 0 0 0 1px #e1e1e6',
    }));
    const ERR = {
      '단어 없음': '8번째 단어가 목록에 없어요. 종이에 적은 글자를 다시 봐 주세요.',
      '체크섬': '12단어의 조합이 맞지 않아요. 단어 하나가 다르거나 순서가 바뀌었을 수 있어요. 종이에 적은 순서대로 다시 확인해 주세요.',
      '풀리지 않음': guest ? '이 코드로 계정을 찾지 못했어요. 게스트 계정의 코드인지, 카카오·Apple에 연결한 계정은 아닌지 확인해 주세요.' : '이 코드로는 백업이 열리지 않아요. 다른 계정의 코드이거나 단어 하나가 다를 수 있어요.',
    };
    const complete = filled >= 12 && badIndex < 0;
    const ready = complete && error !== '체크섬';
    const noBackup = error === '백업 없음';
    return {
      words, lost, ready, notReady: !ready,
      lostAsk: !confirm && !offer, lostAskLater: !confirm && offer, lostConfirm: confirm,
      lostIsGuest: guest, lostNotGuest: !guest,
      startFresh: () => this.setState({ confirm: true }), backToAsk: () => this.setState({ confirm: false }),
      confirmA: offer ? '이 계정의 백업 3개(마지막 10월 20일 18:20)를 지우고, 새 복구 코드와 새 열쇠를 만들어요. 그룹에는 새 기기로 다시 들어가고, 그룹 캘린더와 대화는 지금부터 다시 쌓여요.'
        : '이 계정의 백업 3개(마지막 10월 20일 18:20)를 지우고, 새 복구 코드와 새 열쇠를 만들어요. 이전 기기는 로그아웃되고, 그룹에는 새 기기로 다시 들어가요.',
      done: done || noBackup,
      title: guest ? '복구 코드로 돌아오기' : offer ? '백업 가져오기' : '기기 바꾸기',
      intro: guest ? '게스트 계정은 복구 코드가 곧 열쇠예요. 12단어를 넣으면 계정을 찾고 암호화 백업을 이 기기로 가져와요. 이전 기기는 로그아웃돼요.'
        : offer ? '이 계정의 암호화 백업이 서버에 있어요. 복구 코드 12단어를 넣으면 이 기기로 가져와요. 지금 넣지 않아도 캘린더는 쓸 수 있어요.'
        : '이 계정은 다른 기기에서 쓰고 있어요. 복구 코드 12단어를 넣으면 암호화 백업을 이 기기로 가져오고, 이전 기기는 로그아웃돼요.',
      progress: filled >= 12 ? '12단어 모두 넣었어요' : filled + ' / 12',
      hasError: !!ERR[error], noError: !ERR[error], errorText: ERR[error] || '',
      cta: error === '풀리지 않음' ? '다시 시도' : guest ? '계정 찾기' : '백업 가져오기',
      notReadyLabel: error === '체크섬' ? '코드를 다시 확인해 주세요' : badIndex >= 0 ? '8번째 단어를 고쳐 주세요' : '단어 ' + (12 - filled) + '개 더',
      paste: () => this.setState({ filled: 12 }),
      restore: () => this.setState({ done: true }),
      doneText: noBackup ? (guest ? '계정을 찾았어요. 가져올 백업은 없어서 일정은 이 기기에서 새로 시작해요.' : offer ? '가져올 백업이 없었어요. 일정은 이 기기에서 새로 시작해요.' : '가져올 백업이 없었어요. 일정은 이 기기에서 새로 시작하고, 이전 기기는 로그아웃됐어요.')
        : offer ? '일정 128개, 사람 12명, 템플릿 3개를 가져왔어요.' : '일정 128개, 사람 12명, 템플릿 3개를 가져왔어요. 이전 기기는 로그아웃됐어요.',
      doneNote: noBackup ? '이 기기에서 백업하면 다음부터는 이 복구 코드로 되찾을 수 있어요.' : '그룹에는 다시 들어가는 중이에요. 그룹 캘린더는 잠시 뒤에 채워져요.',
      noCode: () => this.setState({ lost: true }), closeLost: () => this.setState({ lost: false }),
      lostTitle: guest ? '복구 코드 없이는 게스트 계정을 찾을 수 없어요' : offer ? '복구 코드 없이는 백업을 열 수 없어요' : '복구 코드 없이는 되찾을 수 없어요',
      lostA: guest ? '게스트 계정은 복구 코드가 계정을 찾는 유일한 방법이에요. 그날어때도 코드를 모르고, 운영자도 찾아 드릴 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.'
        : offer ? '백업은 이 코드로만 열리고, 그날어때도 열 수 없어요. 코드를 적은 종이가 집에 있다면 나중에 넣어도 돼요. 그동안 캘린더는 이 기기에서 쓰고, 백업은 코드를 넣을 때까지 쉬어요.'
        : '백업은 이 코드로만 열리고, 그날어때도 열 수 없어요. 이전 기기가 아직 있다면 그 기기의 나 › 복구 코드에서 볼 수 있어요.',
      lostB: guest ? '새 게스트 계정으로 시작하면 이전 계정의 일정, 그룹, 백업은 돌아오지 않아요.'
        : '새로 시작하면 이 계정의 백업은 지워지고, 그룹에는 새 기기로 다시 들어가요. 그룹 캘린더와 대화는 지금부터 다시 쌓여요.',
    };
  }""")
boards['Restore'] = boards['Restore'].replace("data-props='{\"$preview\"", "data-props='{\"mode\":{\"editor\":\"enum\",\"options\":[\"기기 교체\",\"게스트 복귀\",\"백업 제안\"],\"default\":\"기기 교체\"},\"error\":{\"editor\":\"enum\",\"options\":[\"없음\",\"단어 없음\",\"체크섬\",\"풀리지 않음\",\"백업 없음\"],\"default\":\"없음\"},\"$preview\"")

# The guest-return entry on the sign-in screen opens this copy with the guest variant preselected.
boards['RestoreGuest'] = boards['Restore'].replace('<title>기기 바꾸기</title>', '<title>복구 코드로 돌아오기</title>').replace('"default":"기기 교체"', '"default":"게스트 복귀"')

# ---------- 기기 로그아웃됨 (403 device_revoked) ----------
month_bg = ('<div style="position: absolute; top: 54px; left: 20px; display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">2026년</span><span style="font-size: 32px; font-weight: 600; line-height: 1.1; letter-spacing: -0.025em">10월</span></div>'
            '<div style="position: absolute; top: 148px; left: 8px; width: 374px; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); grid-auto-rows: 88px">'
            + ''.join('<div style="border-top: 1px solid #e1e1e6; display: flex; flex-direction: column; align-items: center; padding-top: 6px; gap: 4px"><span style="font-size: 13px; font-weight: 600; color: #696973">%d</span><span style="width: 44px; height: 16px; border-radius: 4px; background: %s"></span></div>' % (d, c) for d, c in [(5, '#a0c0fa'), (6, '#f2f2f5'), (7, '#a0c0fa'), (8, '#77d1c2'), (9, '#f2f2f5'), (10, '#efb08a'), (11, '#f2f2f5'), (12, '#a0c0fa'), (13, '#77d1c2'), (14, '#f2f2f5'), (15, '#a0c0fa'), (16, '#f2f2f5'), (17, '#efb08a'), (18, '#f2f2f5')]) + '</div>')
boards['Revoked'] = page('기기 로그아웃됨',
    scrim_bg(month_bg) + sheet(300,
        '<h2 style="margin: -6px 0 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">다른 기기에서 로그인했어요</h2>'
        '<p style="margin: 0; font-size: 15px; line-height: 1.55; color: #3a3a40">{{how}}</p>'
        '<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px">'
        '<div style="padding: 14px 16px; border-radius: 16px; background: #f4f4f6; display: flex; flex-direction: column; gap: 8px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">이 기기에 남는 것</span><span style="font-size: 15px; line-height: 1.5">내 일정과 캘린더<br>사람·생일<br>교대 템플릿<br>일정 알림</span></div>'
        '<div style="padding: 14px 16px; border-radius: 16px; background: #f4f4f6; display: flex; flex-direction: column; gap: 8px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">이 기기에서 지워지는 것</span><span style="font-size: 15px; line-height: 1.5; color: #3a3a40">그룹과 채팅<br>백업 열쇠와 복구 코드<br>날짜 잡기 알림</span></div></div>'
        '<p style="margin: 0; font-size: 13px; line-height: 1.5; color: #5c5c66">{{advice}}</p>'
        '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px">' + primary('이 기기에서 다시 로그인', href='SignIn.dc.html') + '<a href="Month.dc.html" style="height: 52px; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600">캘린더만 쓸게요</a></div>',
        '다른 기기에서 로그인했어요'),
    """  renderVals() {
    // replaced_via from the 403 body: provider sign-in or a guest return with the recovery code.
    const recovery = (this.props.via ?? '카카오·Apple 로그인') === '복구 코드';
    return {
      how: recovery ? '10월 25일 18:20에 iPhone에서 복구 코드로 이 계정에 들어와서 이 기기는 로그아웃됐어요. 계정은 한 기기에서만 쓸 수 있어요.'
        : '10월 25일 18:20에 iPhone에서 카카오·Apple 계정으로 로그인해서 이 기기는 로그아웃됐어요. 계정은 한 기기에서만 쓸 수 있어요.',
      advice: recovery ? '캘린더는 그대로 쓸 수 있어요. 내가 한 게 아니라면 복구 코드를 적은 종이를 누가 봤을 수 있어요. 코드를 아는 사람은 누구나 이 계정으로 들어올 수 있으니, 다시 로그인한 뒤 카카오·Apple 계정을 연결해 두세요.'
        : '캘린더는 그대로 쓸 수 있어요. 내가 한 로그인이 아니라면 카카오·Apple 계정이 다른 사람에게 알려졌을 수 있어요. 그 계정의 비밀번호를 바꿔 주세요.',
    };
  }""", bg='#ffffff')
boards['Revoked'] = boards['Revoked'].replace("data-props='{\"$preview\"", "data-props='{\"via\":{\"editor\":\"enum\",\"options\":[\"카카오·Apple 로그인\",\"복구 코드\"],\"default\":\"카카오·Apple 로그인\"},\"$preview\"")

# ---------- 계정 삭제 ----------
item = lambda t, s: f'<li style="display: flex; gap: 10px; font-size: 15px; line-height: 1.5"><span aria-hidden="true" style="flex: none; width: 6px; height: 6px; margin-top: 8px; border-radius: 3px; background: #1b1b1f"></span><span><b style="font-weight: 600">{t}</b> {s}</span></li>'
boards['DeleteAccount'] = page('계정 삭제',
    back_header('나', 'Me.dc.html')
    + title_block(102, '계정 삭제', '삭제하면 되돌릴 수 없어요. 먼저 무엇이 지워지고 무엇이 남는지 확인하세요.')
    + '<div style="position: absolute; top: 222px; left: 16px; width: 358px; display: flex; flex-direction: column; gap: 16px">'
    + '<div style="padding: 16px; border-radius: 16px; background: #ffffff; display: flex; flex-direction: column; gap: 10px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">서버에서 지워지는 것</span><ul style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 8px">'
    + item('계정과 기기 등록.', '카카오·Apple 연결도 풀려요. 카카오·Apple 계정은 삭제 직전에 그 계정으로 로그인을 한 번 더 받아요. 게스트는 기기의 복구 코드 자료로 확인해서 더 할 일이 없어요.') + item('암호화 백업 3개.', '복구 코드로도 다시 받을 수 없어요.') + item('그룹 멤버십.', '그룹 4개에서 나가요. 이미 정해진 약속은 다른 멤버에게 남고, 내 이름은 ‘나간 멤버’로 보여요.') + item('그룹에 보낸 사진·파일.', '보관 기간이 남았어도 지워져요.')
    + '</ul></div>'
    + '<div style="padding: 16px; border-radius: 16px; background: #ffffff; display: flex; flex-direction: column; gap: 10px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">이 기기에 남는 것</span><ul style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 8px">'
    + item('내 일정과 사람·생일.', '앱을 지우기 전까지 그대로 보여요. 그룹 기능 없이 캘린더로 계속 쓸 수 있어요.') + item('캘린더 내보내기(ICS).', '다른 캘린더 앱으로 옮길 수 있어요.')
    + '</ul></div>'
    + '<p style="margin: 0 4px; font-size: 13px; line-height: 1.5; color: #5c5c66">접속 기록은 법이 정한 3개월 동안 보관된 뒤 지워져요. 그날어때 플러스를 쓰고 있다면 App Store·Google Play에서 구독을 따로 해지해야 해요.</p>'
    + '</div>'
    + '<div style="position: absolute; left: 20px; bottom: 34px; width: 350px; display: flex; flex-direction: column; gap: 8px">'
    + '<button type="button" onClick="{{openConfirm}}" style="height: 52px; border: none; border-radius: 999px; background: #ffffff; color: #c92f26; font-size: 17px; font-weight: 600; cursor: pointer">계정 삭제</button>'
    + '<a href="Me.dc.html" style="height: 44px; display: flex; align-items: center; justify-content: center; font-size: 15px; color: #5c5c66">그냥 둘게요</a></div>'
    + '''<sc-if value="{{confirm}}" hint-placeholder-val="{{false}}">
<div style="position: absolute; inset: 0">
<button type="button" aria-label="닫기" onClick="{{closeConfirm}}" style="position: absolute; inset: 0; width: 100%; height: 100%; padding: 0; border: none; background: rgba(10,10,14,0.32); cursor: pointer"></button>
''' + sheet(492, '<h2 style="margin: -6px 0 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">하린님의 계정을 삭제할까요?</h2>'
                 '<p style="margin: 0; display: flex; gap: 10px; font-size: 15px; line-height: 1.55; color: #3a3a40">' + WARN + '<span>백업과 그룹 멤버십이 바로 지워지고 되돌릴 수 없어요. 이 기기의 일정은 남아요.</span></p>'
                 '<sc-if value="{{provider}}" hint-placeholder-val="{{true}}"><p style="margin: 0; padding: 10px 14px; border-radius: 12px; background: {{noteBg}}; font-size: 13px; line-height: 1.5; color: {{noteFg}}">{{note}}</p></sc-if>'
                 '<label style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">확인을 위해 ‘삭제’라고 적어 주세요</span><input type="text" placeholder="삭제" style="height: 48px; box-sizing: border-box; padding: 0 16px; border: none; border-radius: 12px; background: #f4f4f6; font-size: 17px; outline: none"></label>'
                 '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px"><button type="button" disabled="{{true}}" style="height: 52px; border: none; border-radius: 999px; background: #ebebef; color: #696973; font-size: 17px; font-weight: 600">{{deleteLabel}}</button><button type="button" onClick="{{closeConfirm}}" style="height: 52px; border: none; border-radius: 999px; background: none; font-size: 17px; font-weight: 600; cursor: pointer">취소</button></div>', '계정 삭제 확인')
    + '</div></sc-if>',
    '''  constructor(props) {
    super(props);
    this.state = { confirm: false };
  }
  renderVals() {
    // Kakao and Apple accounts prove themselves again right before deletion; guests sign with the recovery key silently.
    const account = this.props.account ?? '카카오';
    const provider = account !== '게스트';
    const failed = (this.props.error ?? '없음') === '다른 계정';
    const note = failed ? '이 계정에 연결된 ' + account + ' 계정으로 다시 확인해 주세요. 다른 계정으로 로그인하면 지울 수 없어요.'
      : account + ' 계정이라 ‘삭제’를 적은 뒤 ' + account + ' 로그인을 한 번 더 받아요. 세션만 훔친 사람이 계정을 지우지 못하게 하려는 거예요.';
    return { confirm: this.state.confirm || failed, provider, note,
      noteBg: failed ? '#fbeceb' : '#f4f4f6', noteFg: failed ? '#3a3a40' : '#3a3a40',
      deleteLabel: failed ? account + '로 다시 확인' : provider ? '‘삭제’를 적으면 ' + account + '로 확인해요' : '‘삭제’를 적으면 지울 수 있어요',
      openConfirm: () => this.setState({ confirm: true }), closeConfirm: () => this.setState({ confirm: false }) };
  }''')

OUT.mkdir(parents=True, exist_ok=True)
for name, html in boards.items():
    (OUT / f'{name}.dc.html').write_text(html, encoding='utf-8')
    print(name, len(html))

boards['DeleteAccount'] = boards['DeleteAccount'].replace("data-props='{\"$preview\"", "data-props='{\"account\":{\"editor\":\"enum\",\"options\":[\"카카오\",\"Apple\",\"게스트\"],\"default\":\"카카오\"},\"error\":{\"editor\":\"enum\",\"options\":[\"없음\",\"다른 계정\"],\"default\":\"없음\"},\"$preview\"")

# ---------- 다른 계정의 일정이 남아 있을 때 ----------
boards['KeepLocal'] = page('남은 일정 처리',
    scrim_bg(month_bg) + sheet(372,
        '<h2 style="margin: -6px 0 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">이 기기에 다른 계정의 일정이 남아 있어요</h2>'
        '<p style="margin: 0; font-size: 15px; line-height: 1.55; color: #3a3a40">전에 이 기기를 쓰던 계정의 일정 86개, 사람 5명이에요. 하린님 계정으로 가져오거나 지울 수 있어요. 두 계정의 일정을 섞어 두지는 않아요.</p>'
        '<p style="margin: 0; padding: 10px 14px; border-radius: 12px; background: #f4f4f6; font-size: 13px; line-height: 1.5; color: #3a3a40">가져오면 내 일정이 되어 백업에 들어가고, 그룹에는 비어 있는지만 보여요. 지우면 이 기기에서만 사라지고, 그 계정의 백업은 그대로예요.</p>'
        '<div style="margin-top: auto; display: flex; flex-direction: column; gap: 8px">' + primary('내 계정으로 가져오기', href='Month.dc.html') + '<a href="Month.dc.html" style="height: 52px; border-radius: 999px; background: #f4f4f6; color: #c92f26; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600">지우고 시작하기</a></div>',
        '이 기기에 남은 일정'),
    """  renderVals() {
    return {};
  }""", bg='#ffffff')

OUT.mkdir(parents=True, exist_ok=True)
for name in ('Revoked', 'DeleteAccount', 'KeepLocal'):
    (OUT / f'{name}.dc.html').write_text(boards[name], encoding='utf-8')
    print(name, 'rewritten')

# ---------- 열 수 없던 캘린더 (core store_reset, screen 25) ----------
# The store's key was lost, so the core set the old file aside and started empty; account and backups are still on
# the server, so the screen sends people to sign in and restore, and warns guests off a new guest account.
KEY_ICON = '<svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round"><circle cx="8" cy="15.5" r="4"></circle><path d="M11 12.5l8.5-8.5M16.5 7l2.5 2.5M14 9.5l2 2"></path></svg>'
boards['StoreReset'] = page('열 수 없던 캘린더',
    '<div style="position: absolute; top: 104px; left: 20px; width: 350px; display: flex; flex-direction: column; gap: 16px">'
    '<span aria-hidden="true" style="width: 64px; height: 64px; border-radius: 32px; background: #f4f4f6; color: #1b1b1f; display: flex; align-items: center; justify-content: center">' + KEY_ICON + '</span>'
    '<h1 style="margin: 0; font-size: 28px; font-weight: 600; line-height: 1.3; letter-spacing: -0.03em">이 기기의 캘린더를<br>열 수 없었어요</h1>'
    '<p style="margin: 0; font-size: 16px; line-height: 1.55; color: #3a3a40">이 기기에서 캘린더를 잠가 둔 열쇠를 더 이상 읽을 수 없어, 빈 캘린더로 다시 시작했어요. 계정과 암호화 백업은 서버에 그대로 있어요.</p>'
    '<section aria-label="되찾는 길" style="box-sizing: border-box; padding: 16px 18px; border-radius: 16px; background: #f4f4f6; display: flex; flex-direction: column; gap: 12px">'
    '<span style="font-size: 13px; font-weight: 600; color: #5c5c66">되찾는 길</span>'
    '<p style="margin: 0; font-size: 15px; line-height: 1.5"><b style="font-weight: 600">카카오·Apple로 썼다면</b> 다시 로그인하고 복구 코드 12단어를 넣어 주세요. 마지막 백업까지 돌아와요.</p>'
    '<p style="margin: 0; font-size: 15px; line-height: 1.5"><b style="font-weight: 600">게스트였다면</b> ‘복구 코드로 돌아오기’로 들어와 주세요. 새 게스트로 시작하면 예전 계정으로 돌아갈 수 없어요.</p>'
    '</section>'
    '<p style="margin: 0; font-size: 13px; line-height: 1.5; color: #5c5c66">마지막 백업 뒤에 바뀐 것은 돌아오지 않아요.</p>'
    '</div>'
    '<div style="position: absolute; left: 20px; bottom: 34px; width: 350px; display: flex; flex-direction: column; gap: 8px">' + primary('다시 로그인하기', href='SignIn.dc.html')
    + '<a href="RestoreGuest.dc.html" style="height: 52px; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600">게스트였어요 · 복구 코드로 돌아오기</a></div>',
    """  renderVals() {
    return {};
  }""", bg='#ffffff')
(OUT / 'StoreReset.dc.html').write_text(boards['StoreReset'], encoding='utf-8')
print('StoreReset written')
