// Export boards and interactive states listed in a JSON job file: [{file, w, h, scale, out, clicks: []}]
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
(async () => {
  const [base, jobsPath, outRoot] = process.argv.slice(2);
  const jobs = JSON.parse(fs.readFileSync(jobsPath, 'utf8'));
  const browser = await chromium.launch();
  let failed = 0;
  for (const j of jobs) {
    const page = await browser.newPage({ viewport: { width: j.w, height: j.h }, deviceScaleFactor: j.scale });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(base + '/' + j.file, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    for (const c of j.clicks || []) { await page.locator(c).first().click(); await page.waitForTimeout(200); }
    if (j.scroll) { await page.locator(j.scroll).first().evaluate((el) => { el.scrollTop = el.scrollHeight; }); await page.waitForTimeout(200); }
    const out = path.join(outRoot, j.out);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    await page.screenshot({ path: out });
    if (errors.length) { failed++; console.log('ERR', j.out, errors.join('; ')); }
    await page.close();
  }
  await browser.close();
  console.log(jobs.length, 'exported', failed, 'with page errors');
})();
