// Screenshot boards at a given device scale factor, optionally after clicks: file:w:h:out[:click1||click2]
const { chromium } = require('playwright');
(async () => {
  const [base, out, scale, ...specs] = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const spec of specs) {
    const [file, w, h, name, clicks] = spec.split(':');
    const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) }, deviceScaleFactor: Number(scale) });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    for (const c of clicks ? clicks.split('||') : []) { await page.locator(c).first().click(); await page.waitForTimeout(200); }
    await page.screenshot({ path: `${out}/${name}` });
    console.log(name, errors.length ? 'ERR ' + errors.join('; ') : '');
    await page.close();
  }
  await browser.close();
})();
