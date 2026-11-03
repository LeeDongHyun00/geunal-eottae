// Flag leaf elements that poke outside the phone frame horizontally; these get clipped on the canvas.
const { chromium } = require('playwright');
(async () => {
  const [base, ...files] = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const file of files) {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.goto(base + '/' + file, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(200);
    const hits = await page.evaluate(() => {
      const out = [];
      for (const el of document.body.querySelectorAll('button, a, input, span, p, h1, h2, h3, svg, label')) {
        const b = el.getBoundingClientRect();
        if (!b.width || !b.height || b.bottom < 0 || b.top > 844) continue;
        // Skip content inside intentionally clipped scrollers (overflow-x auto/hidden ancestors narrower than the frame).
        let p = el.parentElement, clipped = false;
        while (p && p !== document.body) {
          const cs = getComputedStyle(p);
          if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll' || cs.overflowX === 'hidden') && p.getBoundingClientRect().width < 389) { clipped = true; break; }
          p = p.parentElement;
        }
        if (clipped) continue;
        if (b.right > 391 || b.left < -1) out.push(`${el.tagName.toLowerCase()} [${Math.round(b.left)}..${Math.round(b.right)}] ${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 24)}`);
      }
      return out.slice(0, 6);
    });
    console.log(file.padEnd(24), hits.length ? '\n   ' + hits.join('\n   ') : 'ok');
    await page.close();
  }
  await browser.close();
})();
