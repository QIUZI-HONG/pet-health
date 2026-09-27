#!/usr/bin/env node
/**
 * 构建前的硬编码色值检查（ADR-0015）。
 *
 * 「设计 token 全部来自视觉稿取色，无硬编码色值」是切片 #96 的验收标准之一。
 * 验收标准靠自觉一定会烂——这个脚本把它变成构建期的红灯。
 *
 * 规则：颜色只能来自 `var(--ph-*)`（定义在 packages/ui/src/tokens.css，值取自视觉稿像素）。
 *
 * **只扫样式上下文**，不扫正文：`.css` 整个文件；`.vue` 的 `<style>` 块；`.vue` 模板与 `.ts`
 * 里带样式语义的行（含 color / background / border / fill / stroke）。否则正文里的 issue 引用
 * （「切片 #94」「#101 接真知识库」）会被当成三位十六进制色值误报。
 *
 * 用法：node scripts/check-tokens.mjs        （挂在 pnpm build 里，CI 会跑）
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(here, "../src");

const SCAN_EXTENSIONS = [".vue", ".ts", ".css"];
const COLOR_PATTERNS = [
  { name: "十六进制色值", regex: /#[0-9a-fA-F]{3,8}\b/g },
  { name: "rgb()/rgba()", regex: /\brgba?\([^)]*\)/g },
  { name: "hsl()/hsla()", regex: /\bhsla?\([^)]*\)/g },
];

/** 允许出现的例外：行内标注 `tokens-allow` 并说明原因。 */
const ALLOW_MARKER = "tokens-allow";

/** 样式语义关键词——模板与 TS 里只有这些行才可能写颜色。 */
const STYLE_HINTS = /\b(color|background|border|outline|shadow|fill|stroke|gradient)\b/i;

function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      yield* walk(full);
    } else if (SCAN_EXTENSIONS.some((ext) => entry.endsWith(ext))) {
      yield full;
    }
  }
}

/** 判断某一行是否属于「可能写颜色」的上下文。 */
function isStyleContext(file, line, inStyleBlock) {
  if (file.endsWith(".css")) return true;
  if (inStyleBlock) return true;
  return STYLE_HINTS.test(line);
}

const problems = [];

for (const file of walk(srcDir)) {
  const lines = readFileSync(file, "utf8").split("\n");
  let inStyleBlock = false;

  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (/^<style\b/.test(trimmed)) {
      inStyleBlock = true;
      return;
    }
    if (/^<\/style>/.test(trimmed)) {
      inStyleBlock = false;
      return;
    }
    if (trimmed.includes(ALLOW_MARKER)) return;
    if (!isStyleContext(file, line, inStyleBlock)) return;

    for (const { name, regex } of COLOR_PATTERNS) {
      if (regex.test(line)) {
        problems.push({
          file: file.slice(srcDir.length + 1),
          line: index + 1,
          name,
          snippet: trimmed.slice(0, 80),
        });
      }
    }
  });
}

if (problems.length > 0) {
  console.error("发现硬编码色值——颜色必须来自 packages/ui/src/tokens.css 的 var(--ph-*)：\n");
  for (const p of problems) {
    console.error(`  src/${p.file}:${p.line}  ${p.name}  → ${p.snippet}`);
  }
  console.error(
    `\n共 ${problems.length} 处。若某处确实必须写死（例如注释里举例），在该行加 ${ALLOW_MARKER} 标注。`,
  );
  process.exit(1);
}

console.log("  ✓ 没有硬编码色值，颜色全部来自设计 token");
