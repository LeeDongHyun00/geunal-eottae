// Rasterise icon SVGs. Store icons must be opaque, so PNGs are flattened to RGB afterwards.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [dir, outDir, ...specs] = process.argv.slice(2);
  const browser = await chromium.launch();
  for (const spec of specs) {
    const [svgName, size, outName, bg] = spec.split(':');
    const svg = fs.readFileSync(`${dir}/${svgName}`, 'utf8').replace(/width="1024" height="1024"/, `width="${size}" height="${size}"`);
    const page = await browser.newPage({ viewport: { width: Number(size), height: Number(size) } });
    await page.setContent(`<html><body style="margin:0;background:${bg || 'transparent'}">${svg}</body></html>`);
    await page.screenshot({ path: `${outDir}/${outName}`, omitBackground: !bg });
    console.log(outName);
    await page.close();
  }
  await browser.close();
})();
