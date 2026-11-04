"""Generate M2 sheet boards 13 (CreateGroup), 15 (LeadSet), 19 (GroupProfile) in the canvas .dc.html format."""
import sys
from pathlib import Path

out = Path(sys.argv[1])

HEAD = '''<!doctype html>
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
@font-face{{font-family:"Pretendard";src:url(/_blob/6c25f752af04cd1acf08b3de90d860de) format("woff2");font-weight:45 920;font-style:normal;font-display:swap}}
body{{margin:0}}
a{{color:#1b1b1f;text-decoration:none}}a:hover{{color:#1b1b1f}}
button{{font:inherit;letter-spacing:inherit;color:inherit}}
input{{font:inherit;letter-spacing:inherit;color:inherit}}
</style>
</helmet>
<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: #ffffff; color: #1b1b1f; font-family: Pretendard, -apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; letter-spacing: -0.01em; word-break: keep-all; overflow-wrap: break-word">
'''

TAIL = '''
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"groupColor":{{"editor":"enum","options":["토마토","귤","겨자","올리브","숲","민트","하늘","바다","포도","자두","벚꽃","코코아"],"default":"포도"}},"$preview":{{"width":390,"height":844}}}}'>
class Component extends DCLogic {{
{body}
}}
</script>
</body>
</html>
'''

RAMPS = '''    const RAMPS = {
      '토마토': ['#f9cdc8', '#f5aaa1', '#ed887d', '#de685d', '#c54e45'],
      '귤': ['#f5d1bc', '#efb08a', '#e5905a', '#d57128', '#ba5a00'],
      '겨자': ['#ead7b4', '#dcba7b', '#cd9d3d', '#b58400', '#976d00'],
      '올리브': ['#d3dfbb', '#b5c887', '#98b152', '#7d9911', '#667e00'],
      '숲': ['#c3e3c8', '#94cf9f', '#64b979', '#32a256', '#00873d'],
      '민트': ['#b6e5db', '#77d1c2', '#19bdaa', '#00a090', '#008476'],
      '하늘': ['#b8e0f3', '#7dcaec', '#36b3e2', '#0099c6', '#007ea4'],
      '바다': ['#c7dafb', '#a0c0fa', '#7ba6f5', '#5a8be9', '#4172d1'],
      '포도': ['#d9d4f8', '#c1b6f6', '#aa98f0', '#937ce3', '#7d63cb'],
      '자두': ['#ecceea', '#e1acde', '#d38cd1', '#c16dbf', '#aa54a9'],
      '벚꽃': ['#f6ccd9', '#f1a9c0', '#e786a9', '#d86691', '#c04c7b'],
      '코코아': ['#e5d5cd', '#d5b9aa', '#c29d89', '#ae836c', '#966b54'],
    };'''

CLOSE_X = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round"><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"></path></svg>'
GRABBER = '<div aria-hidden="true" style="align-self: center; width: 36px; height: 5px; border-radius: 3px; background: #d1d1d6"></div>'
SHEET = 'background: #ffffff; border-radius: 20px 20px 0 0; box-shadow: 0 -8px 32px rgba(0,0,0,0.12); display: flex; flex-direction: column'
SCRIM = '<div style="position: absolute; inset: 0; background: rgba(10,10,14,0.32)"></div>'
GROUP_BACKDROP = '''<div aria-hidden="true" style="position: absolute; inset: 0">
<div style="height: 176px; box-sizing: border-box; padding: 104px 20px 0; background: {{g.s5}}; color: #ffffff; font-size: 28px; font-weight: 600; letter-spacing: -0.025em">주말모임</div>
<div style="padding: 16px 8px; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 8px 0">
<sc-for list="{{bg}}" as="b" hint-placeholder-count="14">
<span style="margin: 0 3px; height: 12px; border-radius: 3px; background: {{b}}"></span>
</sc-for>
</div>
''' + SCRIM + '\n</div>\n'
BG_VALS = "const bg = [2, 4, 1, 3, 4, 2, 0, 3, 1, 4, 2, 3, 1, 4].map((i) => ramp[i]);"


def header(title, sub, close_href, eyebrow=None):
    eb = f'<span style="font-size: 13px; font-weight: 600; color: {{{{g.s5}}}}">{eyebrow}</span>\n' if eyebrow else ''
    sb = f'<span style="font-size: 14px; line-height: 1.5; color: #5c5c66">{sub}</span>\n' if sub else ''
    return f'''<div style="display: flex; align-items: flex-start; justify-content: space-between; margin-top: -6px">
<div style="display: flex; flex-direction: column; gap: 4px">
{eb}<h2 style="margin: 0; font-size: 22px; font-weight: 600; letter-spacing: -0.02em">{title}</h2>
{sb}</div>
<a href="{close_href}" aria-label="닫기" style="flex: none; width: 44px; height: 44px; margin: -6px -12px 0 0; display: flex; align-items: center; justify-content: center; color: #5c5c66">{CLOSE_X}</a>
</div>
'''


PRIMARY = 'height: 52px; border: none; border-radius: 999px; background: {{g.s5}}; color: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600; cursor: pointer'
FIELD = 'height: 52px; box-sizing: border-box; padding: 0 16px; border: none; border-radius: 12px; background: #f4f4f6; font-size: 17px; outline: none'
LABEL = 'font-size: 13px; font-weight: 600; color: #5c5c66'

# ---------- 13. CreateGroup ----------
NAMES = ['토마토', '귤', '겨자', '올리브', '숲', '민트', '하늘', '바다', '포도', '자두', '벚꽃', '코코아']
create = HEAD.format(title='그룹 만들기') + '''
<div aria-hidden="true" style="position: absolute; inset: 0">
<div style="padding: 64px 20px 0; font-size: 32px; font-weight: 600; letter-spacing: -0.025em">그룹</div>
<div style="padding: 20px 20px 0; display: flex; flex-direction: column; gap: 14px">
<span style="height: 64px; border-radius: 16px; background: #f4f4f6"></span>
<span style="height: 64px; border-radius: 16px; background: #f4f4f6"></span>
</div>
''' + SCRIM + '''
</div>

<form aria-label="그룹 만들기" style="position: absolute; left: 0; top: 330px; width: 390px; height: 514px; box-sizing: border-box; margin: 0; padding: 8px 20px 34px; ''' + SHEET + '''; gap: 20px">
''' + GRABBER + '\n' + header('그룹 만들기', None, 'Groups.dc.html') + '''
<div style="display: flex; flex-direction: column; gap: 8px">
<label for="cg-name" style="''' + LABEL + '''">이름</label>
<input id="cg-name" type="text" maxlength="20" value="주말모임" style="''' + FIELD + '''">
</div>
<fieldset style="margin: 0; padding: 0; border: none; display: flex; flex-direction: column; gap: 10px">
<legend style="padding: 0 0 10px; ''' + LABEL + '''">그룹 색 · {{colorName}}</legend>
<div style="display: grid; grid-template-columns: repeat(6, 44px); justify-content: space-between; row-gap: 12px">
<sc-for list="{{swatches}}" as="s" hint-placeholder-count="12">
<button type="button" aria-label="{{s.name}}" aria-pressed="{{s.on}}" onClick="{{s.pick}}" style="width: 44px; height: 44px; padding: 0; border: none; border-radius: 999px; background: {{s.color}}; box-shadow: {{s.ring}}; cursor: pointer"></button>
</sc-for>
</div>
</fieldset>
<p style="margin: 0; font-size: 14px; line-height: 1.55; color: #5c5c66">커버와 날짜 잡기 기본값은 만든 뒤 그룹 설정에서 바꿔요.</p>
<a href="Invite.dc.html" style="margin-top: auto; ''' + PRIMARY + '''">만들기</a>
</form>
''' + TAIL.format(body='''  constructor(props) {
    super(props);
    this.state = { pick: null };
  }
  renderVals() {
''' + RAMPS + '''
    const name = this.state.pick ?? this.props.groupColor ?? '포도';
    const ramp = RAMPS[name] || RAMPS['포도'];
    const g = { s1: ramp[0], s2: ramp[1], s3: ramp[2], s4: ramp[3], s5: ramp[4] };
    // Selected swatch gets a white gap and an ink ring so it reads on every hue.
    const swatches = Object.keys(RAMPS).map((n) => ({
      name: n,
      color: RAMPS[n][4],
      on: n === name,
      ring: n === name ? '0 0 0 3px #ffffff, 0 0 0 5px #1b1b1f' : 'none',
      pick: () => this.setState({ pick: n }),
    }));
    return { g, swatches, colorName: name };
  }''')

# ---------- 15. LeadSet ----------
lead = HEAD.format(title='시간과 활동 정하기') + '''
<div aria-hidden="true" style="position: absolute; inset: 0">
<div style="padding: 64px 20px 0; font-size: 26px; font-weight: 600; letter-spacing: -0.025em">11월 7일 약속</div>
''' + SCRIM + '''
</div>

<form aria-label="시간과 활동 정하기" style="position: absolute; left: 0; top: 64px; width: 390px; height: 780px; box-sizing: border-box; margin: 0; padding: 8px 20px 34px; ''' + SHEET + '''; gap: 18px">
''' + GRABBER + '\n' + header('11월 7일 · 시간과 활동', '참가자 4명의 일정으로 보여요. 정하면 모두의 일정이 함께 바뀌어요.', 'Appointment.dc.html', eyebrow='주도자') + '''
<div role="img" aria-label="참가자 날 테이블, 12시부터 24시까지. 19시부터 21시를 골랐어요" style="display: flex; flex-direction: column; gap: 4px">
<div style="display: grid; grid-template-columns: 44px repeat(12, minmax(0, 1fr)); font-size: 11px; color: #8a8a94; font-variant-numeric: tabular-nums">
<span></span>
<sc-for list="{{hours}}" as="h" hint-placeholder-count="12"><span>{{h}}</span></sc-for>
</div>
<div style="position: relative; display: flex; flex-direction: column; gap: 4px">
<div style="display: grid; grid-template-columns: 44px repeat(12, minmax(0, 1fr)); align-items: center; gap: 0 1px">
<span style="font-size: 12px; font-weight: 600">합침</span>
<sc-for list="{{merged}}" as="m" hint-placeholder-count="12"><span style="height: 28px; border-radius: 3px; background: {{m}}"></span></sc-for>
</div>
<sc-for list="{{people}}" as="p" hint-placeholder-count="4">
<div style="display: grid; grid-template-columns: 44px repeat(12, minmax(0, 1fr)); align-items: center; gap: 0 1px">
<span style="font-size: 12px; color: #5c5c66">{{p.name}}</span>
<sc-for list="{{p.cells}}" as="c" hint-placeholder-count="12"><span style="height: 20px; border-radius: 3px; background: {{c}}"></span></sc-for>
</div>
</sc-for>
<span aria-hidden="true" style="position: absolute; top: -3px; bottom: -3px; left: {{selLeft}}; width: {{selWidth}}; border-radius: 6px; box-shadow: inset 0 0 0 2px #1b1b1f"></span>
</div>
</div>

<div style="display: flex; flex-direction: column; gap: 8px">
<div style="display: flex; align-items: center; justify-content: space-between; height: 48px; padding: 0 16px; border-radius: 12px; background: #f4f4f6">
<span style="font-size: 15px; color: #5c5c66">시간</span>
<span style="font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums">19:00–21:00 · 2시간</span>
</div>
<span style="display: flex; align-items: center; gap: 6px; font-size: 14px; color: #5c5c66"><svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" style="fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round"><circle cx="12" cy="12" r="8.5"></circle><path d="M12 8v4.5M12 16h.01"></path></svg>지영은 이 시간에 일정이 있어요</span>
</div>

<div style="display: flex; flex-direction: column; gap: 10px">
<span style="''' + LABEL + '''">활동 · 토요일 저녁, 4명에게 맞는 추천</span>
<div style="display: flex; flex-wrap: wrap; gap: 8px">
<sc-for list="{{acts}}" as="a" hint-placeholder-count="3">
<button type="button" aria-pressed="{{a.on}}" onClick="{{a.pick}}" style="height: 40px; padding: 0 14px; border-radius: 999px; border: {{a.border}}; background: {{a.bg}}; color: {{a.fg}}; font-size: 15px; font-weight: 600; cursor: pointer">{{a.name}}</button>
</sc-for>
<button type="button" style="height: 40px; padding: 0 14px; border-radius: 999px; border: 1px dashed #b4b4bc; background: #ffffff; font-size: 15px; font-weight: 600; color: #5c5c66; cursor: pointer">직접 입력</button>
</div>
</div>

<div style="margin-top: auto; display: flex; flex-direction: column; gap: 6px">
<a href="Appointment.dc.html" style="''' + PRIMARY + '''">정하기</a>
<button type="button" style="height: 44px; border: none; background: none; font-size: 15px; font-weight: 600; color: #5c5c66; cursor: pointer">시간 지우기</button>
</div>
</form>
''' + TAIL.format(body='''  constructor(props) {
    super(props);
    this.state = { act: '식사' };
  }
  renderVals() {
''' + RAMPS + '''
    const ramp = RAMPS[this.props.groupColor ?? '포도'] || RAMPS['포도'];
    const g = { s1: ramp[0], s2: ramp[1], s3: ramp[2], s4: ramp[3], s5: ramp[4] };
    // Busy hours from 12:00 (index 0) to 24:00, mock data for the four participants.
    const busy = { '하린': [1, 2], '지영': [0, 1, 8, 9], '민수': [3, 4, 5], '서연': [0, 10, 11] };
    const names = Object.keys(busy);
    const hours = [12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23];
    // Free count 0..4 maps to empty, then the group ramp steps 1, 2, 4, 5 (same rule as the group calendar).
    const step = ['#f2f2f5', g.s1, g.s2, g.s4, g.s5];
    const merged = hours.map((_, i) => step[names.filter((n) => !busy[n].includes(i)).length]);
    const people = names.map((n) => ({ name: n, cells: hours.map((_, i) => (busy[n].includes(i) ? '#c7c7cf' : '#f4f4f6')) }));
    // Selection 19:00-21:00 = columns 7..8; label column is 44px, 12 columns share the rest.
    const col = '((100% - 44px) / 12)';
    const acts = ['식사', '술', '보드게임 카페'].map((n) => {
      const on = n === this.state.act;
      return { name: n, on, pick: () => this.setState({ act: n }), bg: on ? g.s5 : '#ffffff', fg: on ? '#ffffff' : '#1b1b1f', border: on ? 'none' : '1px solid #e1e1e6' };
    });
    return { g, hours, merged, people, acts, selLeft: 'calc(44px + 7 * ' + col + ' - 2px)', selWidth: 'calc(2 * ' + col + ' + 4px)' };
  }''')

# ---------- 19. GroupProfile ----------
CATS = ['식사', '술', '카페', '보드게임 카페', '영화', '노래방', '볼링', '전시·공연', '산책·공원', '등산', '쇼핑', '방탈출', '스포츠 관람', '집 모임', '드라이브', '당일 여행', 'PC방', '놀이공원', '공방·원데이 클래스', '운동(풋살·배드민턴 등)']
cats_js = '[' + ', '.join(f"'{c}'" for c in CATS) + ']'
profile = HEAD.format(title='그룹 프로필') + GROUP_BACKDROP + '''
<form aria-label="이 그룹에서 나" style="position: absolute; left: 0; top: 56px; width: 390px; height: 788px; box-sizing: border-box; margin: 0; padding: 8px 0 34px; ''' + SHEET + '''; gap: 0">
''' + GRABBER + '''
<div style="padding: 12px 20px 0">
''' + header('이 그룹에서 나', '주말모임 멤버에게만 보여요', 'GroupSettings.dc.html') + '''</div>
<div data-scroll style="flex: 1 1 0; min-height: 0; overflow-y: auto; padding: 12px 20px 16px; display: flex; flex-direction: column; gap: 20px">
<div style="flex: none; display: flex; align-items: flex-start; gap: 16px">
<div style="flex: none; display: flex; flex-direction: column; align-items: center; gap: 4px">
<span aria-hidden="true" style="width: 80px; height: 80px; border-radius: 999px; background: {{g.s2}}; color: #1b1b1f; display: flex; align-items: center; justify-content: center; font-size: 30px; font-weight: 600">하</span>
<button type="button" style="height: 32px; padding: 0 8px; border: none; background: none; font-size: 14px; font-weight: 600; color: {{g.s5}}; cursor: pointer">바꾸기</button>
</div>
<div style="flex: 1 1 0; min-width: 0; padding-top: 6px; display: flex; flex-direction: column; gap: 8px">
<label for="gp-name" style="''' + LABEL + '''">이 그룹에서 쓸 이름</label>
<input id="gp-name" type="text" value="하린" style="''' + FIELD + '''; width: 100%; height: 48px">
</div>
</div>

<section aria-label="취향" style="flex: none; display: flex; flex-direction: column; gap: 4px">
<h3 style="margin: 0; font-size: 17px; font-weight: 600">취향 · 활동 추천에 쓰여요</h3>
<p style="margin: 0 0 8px; font-size: 14px; line-height: 1.5; color: #5c5c66">고르지 않아도 돼요. 한 명이라도 싫어하는 활동은 추천에서 뒤로 가요. {{summary}}</p>
<sc-for list="{{cats}}" as="c" hint-placeholder-count="20">
<div role="group" aria-label="{{c.name}}" style="min-height: 52px; display: flex; align-items: center; gap: 8px; box-shadow: inset 0 -1px 0 #ececf0">
<span style="flex: 1 1 0; font-size: 16px">{{c.name}}</span>
<button type="button" aria-pressed="{{c.likeOn}}" onClick="{{c.like}}" style="height: 34px; padding: 0 12px; border: {{c.likeBorder}}; border-radius: 999px; background: {{c.likeBg}}; color: {{c.likeFg}}; font-size: 14px; font-weight: 600; cursor: pointer">좋아요</button>
<button type="button" aria-pressed="{{c.dislikeOn}}" onClick="{{c.dislike}}" style="height: 34px; padding: 0 12px; border: {{c.dislikeBorder}}; border-radius: 999px; background: {{c.dislikeBg}}; color: {{c.dislikeFg}}; font-size: 14px; font-weight: 600; cursor: pointer">싫어요</button>
</div>
</sc-for>
</section>
</div>
<div style="padding: 12px 20px 0; box-shadow: 0 -1px 0 #ececf0">
<a href="GroupSettings.dc.html" style="''' + PRIMARY + '''">저장</a>
</div>
</form>
''' + TAIL.format(body='''  constructor(props) {
    super(props);
    // Mock picks; untouched categories stay neutral and are left out of the group taste score.
    this.state = { tags: { '보드게임 카페': 'like', '방탈출': 'like', '등산': 'dislike', '노래방': 'like', 'PC방': 'dislike' } };
  }
  renderVals() {
''' + RAMPS + '''
    const ramp = RAMPS[this.props.groupColor ?? '포도'] || RAMPS['포도'];
    const g = { s1: ramp[0], s2: ramp[1], s3: ramp[2], s4: ramp[3], s5: ramp[4] };
    ''' + BG_VALS + '''
    const tags = this.state.tags;
    // Tapping the active choice again clears it back to neutral.
    const set = (n, v) => this.setState({ tags: { ...tags, [n]: tags[n] === v ? undefined : v } });
    const cats = ''' + cats_js + '''.map((n) => {
      const t = tags[n];
      return {
        name: n,
        likeOn: t === 'like',
        dislikeOn: t === 'dislike',
        like: () => set(n, 'like'),
        dislike: () => set(n, 'dislike'),
        likeBg: t === 'like' ? g.s5 : '#ffffff', likeFg: t === 'like' ? '#ffffff' : '#5c5c66', likeBorder: t === 'like' ? 'none' : '1px solid #e1e1e6',
        dislikeBg: t === 'dislike' ? '#1b1b1f' : '#ffffff', dislikeFg: t === 'dislike' ? '#ffffff' : '#5c5c66', dislikeBorder: t === 'dislike' ? 'none' : '1px solid #e1e1e6',
      };
    });
    const likes = Object.values(tags).filter((v) => v === 'like').length;
    const dislikes = Object.values(tags).filter((v) => v === 'dislike').length;
    return { g, bg, cats, summary: '좋아요 ' + likes + ' · 싫어요 ' + dislikes };
  }''')

for name, html in [('CreateGroup', create), ('LeadSet', lead), ('GroupProfile', profile)]:
    (out / f'{name}.dc.html').write_text(html)
print('ok')

# ---------- 21. InviteWeb: /i/<code> in a mobile browser when the app is not installed ----------
web = HEAD.format(title='초대 웹 페이지') + '''
<div style="position: absolute; inset: 0; display: flex; flex-direction: column; background: #ffffff">
<div aria-hidden="true" style="height: 47px; flex: none; background: #f4f4f6; display: flex; align-items: flex-end; justify-content: center; padding-bottom: 6px; box-sizing: border-box; font-size: 12px; color: #5c5c66"><span style="width: 160px; height: 14px; border-radius: 7px; background: #e1e1e6"></span></div>
<header style="flex: none; padding: 28px 20px 24px; background: {{headBg}}; color: #ffffff; display: flex; flex-direction: column; gap: 14px">
<span style="font-size: 15px; font-weight: 600; opacity: 0.9">그날어때</span>
<span aria-hidden="true" style="height: 10px; border-radius: 3px; background: {{stripOn}}"></span>
<div style="display: flex; flex-direction: column; gap: 6px">
<span style="font-size: 15px; opacity: 0.9">{{byLine}}</span>
<h1 style="margin: 0; font-size: 32px; font-weight: 600; letter-spacing: -0.025em">주말모임</h1>
</div>
</header>

<main style="flex: 1 1 0; padding: 24px 20px 34px; display: flex; flex-direction: column; gap: 20px">
<p style="margin: 0; font-size: 17px; line-height: 1.55">일정을 합쳐 보고 같이 날짜를 잡아요. 앱을 설치하면 이 그룹에 들어갈 수 있어요.</p>
<ol style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 14px">
<li style="display: flex; gap: 12px; align-items: flex-start"><span style="flex: none; width: 24px; height: 24px; border-radius: 999px; background: #1b1b1f; color: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 600">1</span><span style="font-size: 15px; line-height: 1.5">{{step1}}</span></li>
<li style="display: flex; gap: 12px; align-items: flex-start"><span style="flex: none; width: 24px; height: 24px; border-radius: 999px; background: #1b1b1f; color: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 600">2</span><span style="font-size: 15px; line-height: 1.5">{{step2}}</span></li>
</ol>
<div style="display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 16px; background: #f4f4f6">
<span style="flex: 1 1 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 13px; font-weight: 600; color: #5c5c66">초대 코드</span><span style="font-size: 24px; font-weight: 600; letter-spacing: 0.08em; font-variant-numeric: tabular-nums">GN7KQ-4XZM2</span><span style="font-size: 13px; color: #5c5c66">앱에서 이 코드를 넣어도 돼요</span></span>
<button type="button" style="height: 40px; padding: 0 16px; border: none; border-radius: 999px; background: #ffffff; font-size: 14px; font-weight: 600; cursor: pointer">복사</button>
</div>
<a href="Join.dc.html" style="margin-top: auto; height: 54px; border-radius: 999px; background: #1b1b1f; color: #ffffff; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 600">{{storeLabel}}</a>
<span style="text-align: center; font-size: 13px; line-height: 1.5; color: #5c5c66">그룹 이름은 링크에만 있고 서버로 보내지 않아요.</span>
</main>
</div>
''' + TAIL.format(body='''  renderVals() {
''' + RAMPS + '''
    const ramp = RAMPS[this.props.groupColor ?? '포도'] || RAMPS['포도'];
    const g = { s1: ramp[0], s2: ramp[1], s3: ramp[2], s4: ramp[3], s5: ramp[4] };
    const android = this.props.platform === '안드로이드';
    const steps = '555555554333334345555444'.split('');
    const stripOn = 'linear-gradient(to right, ' + steps.map((ch, i) => 'rgba(255,255,255,' + (0.15 + Number(ch) * 0.17).toFixed(2) + ') ' + ((i * 100) / 24).toFixed(3) + '% ' + (((i + 1) * 100) / 24).toFixed(3) + '%').join(', ') + ')';
    // Android carries the code through Install Referrer; iOS needs the link tapped again or the code pasted.
    return {
      // The page never calls the server: group name, color and inviter come from the link fragment.
      g, stripOn, headBg: g.s5, byLine: '하린님이 초대했어요',
      step1: android ? 'Google Play에서 그날어때를 설치해요.' : 'App Store에서 그날어때를 설치해요.',
      step2: android ? '앱을 열면 이 초대가 그대로 이어져요.' : '설치한 뒤 이 링크를 다시 누르거나, 앱 첫 화면의 ‘초대 코드가 있어요’에 아래 코드를 붙여넣어요.',
      storeLabel: android ? 'Google Play에서 받기' : 'App Store에서 받기',
    };
  }''').replace('"$preview"', '"platform":{"editor":"enum","options":["iOS","안드로이드"],"default":"iOS"},"$preview"')
(out / 'InviteWeb.dc.html').write_text(web)
print('web ok')
