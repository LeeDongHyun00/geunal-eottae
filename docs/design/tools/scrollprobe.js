const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.goto('http://127.0.0.1:8765/Me.dc.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(300);
  const info = await page.evaluate(() => {
    const el = document.querySelector("[data-scroll='settings']");
    if (!el) return 'no element';
    const cs = getComputedStyle(el);
    el.scrollTop = el.scrollHeight;
    return { sh: el.scrollHeight, ch: el.clientHeight, st: el.scrollTop, ov: cs.overflowY, pos: cs.position, bottom: cs.bottom, top: cs.top };
  });
  console.log(JSON.stringify(info));
  await browser.close();
})();
