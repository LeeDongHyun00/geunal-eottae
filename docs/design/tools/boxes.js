// Print bounding boxes for selectors after optional clicks, to debug layout.
const { chromium } = require('playwright');
(async () => {
  const [base, spec, clicks, ...sels] = process.argv.slice(2);
  const [file, w, h] = spec.split(':');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) } });
  await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  for (const c of clicks ? clicks.split('||') : []) { await page.locator(c).first().click(); await page.waitForTimeout(150); }
  for (const s of sels) {
    const boxes = await page.locator(s).evaluateAll((els) => els.map((e) => { const b = e.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)].join(','); }));
    console.log(s, '=>', boxes.join(' | '));
  }
  await browser.close();
})();
