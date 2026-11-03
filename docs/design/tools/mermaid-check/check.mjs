// Parse every mermaid block in a markdown file with the real mermaid parser (no rendering).
import fs from 'node:fs';
import { JSDOM } from 'jsdom';
const dom = new JSDOM('<!doctype html><html><body></body></html>');
globalThis.window = dom.window; globalThis.Element = dom.window.Element; globalThis.HTMLElement = dom.window.HTMLElement; globalThis.document = dom.window.document; globalThis.DOMPurify = { sanitize: (s) => s, addHook() {} };
const mermaid = (await import('mermaid')).default;
mermaid.initialize({ startOnLoad: false });
const md = fs.readFileSync(process.argv[2], 'utf8');
const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m) => m[1]);
let bad = 0;
for (const [i, b] of blocks.entries()) {
  try { await mermaid.parse(b); console.log('block', i + 1, 'ok'); }
  catch (e) { bad++; console.log('block', i + 1, 'FAIL', String(e.message || e).split('\n').slice(0, 3).join(' | ')); }
}
process.exit(bad ? 1 : 0);
