const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.goto('http://127.0.0.1:8765/Me.dc.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(300);
  const info = await page.evaluate(() => {
    const el = document.querySelector("[data-scroll='settings']");
    const kids = [...el.children].map((k) => ({ tag: k.tagName, h: Math.round(k.getBoundingClientRect().height), shrink: getComputedStyle(k).flexShrink, oh: k.scrollHeight }));
    const rule = [...document.styleSheets].flatMap((s) => { try { return [...s.cssRules].map((r) => r.cssText); } catch (e) { return []; } }).filter((t) => t.includes('data-scroll'));
    return { kids, rule, display: getComputedStyle(el).display, html: el.outerHTML.slice(0, 160) };
  });
  console.log(JSON.stringify(info, null, 1));
  await browser.close();
})();
