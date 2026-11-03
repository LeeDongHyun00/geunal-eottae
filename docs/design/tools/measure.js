// Report how far each board's content reaches, to size artboards with an 80px bottom margin.
const { chromium } = require('playwright');
(async () => {
  const [base, ...specs] = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const spec of specs) {
    const [file, w, h] = spec.split(':');
    const page = await browser.newPage({ viewport: { width: Number(w), height: 4000 } });
    await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    const r = await page.evaluate(() => {
      let max = 0, maxEl = '';
      for (const el of document.body.querySelectorAll('*')) {
        const b = el.getBoundingClientRect();
        if (b.width === 0 || b.height === 0) continue;
        const cs = getComputedStyle(el);
        if (cs.position === 'absolute' && el.parentElement === document.body) continue;
        if (b.bottom > max && el.children.length === 0) { max = b.bottom; maxEl = el.tagName + ' ' + (el.textContent || '').trim().slice(0, 30); }
      }
      return { max: Math.round(max), maxEl };
    });
    console.log(file, 'frame=' + h, 'contentBottom=' + r.max, 'needs=' + (r.max + 80), r.maxEl);
    await page.close();
  }
  await browser.close();
})();
