// Drive a board through clicks and screenshot each state.
// Usage: node interact.js <baseUrl> <outDir> <file:w:h> step... where step is click:<selector> or shot:<name>
const { chromium } = require('playwright');
(async () => {
  const [base, out, spec, ...steps] = process.argv.slice(2);
  const [file, w, h] = spec.split(':');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: Number(w), height: Number(h) } });
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  for (const step of steps) {
    const i = step.indexOf(':');
    const kind = step.slice(0, i), arg = step.slice(i + 1);
    if (kind === 'click') { await page.locator(arg).first().click(); await page.waitForTimeout(200); }
    if (kind === 'shot') { await page.screenshot({ path: `${out}/${arg}.png` }); console.log('shot', arg); }
  }
  if (errors.length) console.log('errors:\n  ' + errors.join('\n  '));
  await browser.close();
})();
