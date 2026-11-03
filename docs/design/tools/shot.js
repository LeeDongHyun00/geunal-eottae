// Render canvas boards through the downloaded dc runtime and screenshot them.
// Usage: node shot.js <baseUrl> <outDir> <file:w:h> [...]
const { chromium } = require('playwright');
(async () => {
  const [base, out, ...specs] = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const spec of specs) {
    const [file, w, h] = spec.split(':');
    const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') errors.push(m.type() + ': ' + m.text()); });
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(400);
    const name = file.replace('.dc.html', '');
    await page.screenshot({ path: `${out}/${name}.png`, fullPage: false });
    const fontOk = await page.evaluate(() => document.fonts.check('16px Pretendard', '가'));
    console.log(name, 'fontOk=' + fontOk, errors.length ? '\n  ' + errors.slice(0, 6).join('\n  ') : '');
    await page.close();
  }
  await browser.close();
})();
