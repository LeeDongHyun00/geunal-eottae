# Add the unread-message badge to the chat tab, reusing each board's own group-tab badge style
# so light and dark boards stay consistent.
import re, sys
root, count, *names = sys.argv[1:]
pat_badge = re.compile(r'<span aria-hidden="true" style="(position: absolute; top: 4px; left: 50%; margin-left: 6px;[^"]*)">1</span>')
pat_chat = re.compile(r'<a href="ChatList\.dc\.html" style="border-radius: 28px;([^"]*)">(\s*<svg.*?</svg>\s*<span>채팅</span>\s*)</a>', re.S)
for n in names:
    p = f'{root}/{n}.dc.html'
    s = open(p, encoding='utf-8').read()
    b = pat_badge.search(s)
    m = pat_chat.search(s)
    if not b or not m or len(pat_chat.findall(s)) != 1:
        print(n, 'SKIP', bool(b), bool(m)); continue
    badge = f'<span aria-hidden="true" style="{b.group(1)}">{count}</span>\n'
    new = (f'<a href="ChatList.dc.html" aria-label="채팅, 읽지 않은 메시지 {count}개" '
           f'style="position: relative; border-radius: 28px;{m.group(1)}">{m.group(2)}{badge}</a>')
    s = s[:m.start()] + new + s[m.end():]
    open(p, 'w', encoding='utf-8').write(s)
    print(n, 'ok')
