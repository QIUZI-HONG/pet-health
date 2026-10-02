// 把 manual.md 里的 mermaid 代码块渲染成 PNG（供 docx 嵌入）
import fs from 'node:fs';
import { chromium } from 'playwright-core';

const md = fs.readFileSync('/home/QIU/Pet_Health/docs/delivery/manual.md', 'utf8');
const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m) => m[1].trim());
console.log('mermaid 块数:', blocks.length);

const browser = await chromium.launch({
  executablePath: process.env.HOME + '/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',
  args: ['--no-sandbox'],
});
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });

for (let i = 0; i < blocks.length; i++) {
  const html = `<!doctype html><html><head><meta charset="utf-8">
<style>body{margin:24px;background:#ffffff;font-family:"Microsoft YaHei","Noto Sans SC",sans-serif}</style>
</head><body><div id="d" class="mermaid">${blocks[i]}</div>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
<script>mermaid.initialize({startOnLoad:true,theme:'neutral',fontFamily:'Microsoft YaHei'});</script>
</body></html>`;
  await page.setContent(html, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const el = await page.waitForSelector('#d svg', { timeout: 20000 });
  await el.screenshot({ path: `/tmp/docxwork/mermaid-${i}.png` });
  const box = await el.boundingBox();
  console.log(`mermaid-${i}.png`, box.width + 'x' + box.height);
}
await browser.close();
