/**
 * 首屏体积预算检查（交付文档 9.3 的「首页加载 < 2 秒」在代码侧的可测代理）。
 *
 * 为什么需要它：体积是唯一能**在提交时**拦住的性能指标——真机加载时间要设备与网络，
 * 而体积不需要；体积涨了，加载时间一定跟着涨。此前没有任何检查，产物悄悄变大没人知道。
 *
 * 口径：**首次加载真正会下载的那些文件**（入口 JS + 入口 CSS + 首屏要用的共享块），
 * 不含路由懒加载的页面块——那些是进页面才下载的。判定用 gzip 后的大小（真实网络传的量）。
 *
 * 预算写在下面 BUDGET 里，取值是 2026-09-28 实测值 + 15% 余量：正常开发不会碰到，
 * 但引入一个大依赖（比如把 Element Plus 拖进来）会立刻红。
 *
 * 用法：`pnpm --filter c-web build`（build 脚本里已接入，跑在 vite build 之后）
 * 想让预算随产品阶段变化时改 BUDGET，别把阈值调高来让构建变绿。
 */
import { gzipSync } from "node:zlib";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

/** gzip 后的预算（KB）。实测 2026-09-28：入口 73.7K，取 85K 作上限。 */
const BUDGET = {
  total: 85,
};

const assetsDir = join(process.cwd(), "dist", "assets");

/** 首次加载会下载的文件：入口 JS/CSS 与首屏即用的共享块（路由懒加载的页面块不计）。 */
function isInitialLoad(name) {
  if (name.startsWith("index-")) return true;                    // 入口 JS 与入口 CSS
  if (name.startsWith("_plugin-vue_export-helper")) return true; // Vite 的运行时辅助块
  return false;
}

let totalKb = 0;
const rows = [];
for (const name of readdirSync(assetsDir)) {
  if (!/\.(js|css)$/.test(name) || !isInitialLoad(name)) continue;
  const gzipped = gzipSync(readFileSync(join(assetsDir, name)), { level: 9 }).length;
  const kb = gzipped / 1024;
  totalKb += kb;
  rows.push([name, kb]);
}

if (rows.length === 0) {
  console.error("✗ 没找到入口产物，先跑 vite build");
  process.exit(1);
}

console.log("首屏体积（gzip）：");
for (const [name, kb] of rows.sort((a, b) => b[1] - a[1])) {
  console.log(`  ${name.padEnd(44)} ${kb.toFixed(1)}K`);
}
console.log(`  ${"合计".padEnd(42)} ${totalKb.toFixed(1)}K（预算 ${BUDGET.total}K）`);

if (totalKb > BUDGET.total) {
  console.error(
    `\n✗ 首屏体积 ${totalKb.toFixed(1)}K 超出预算 ${BUDGET.total}K。` +
      `\n  先看是哪一块涨的（上面按大小排序），能拆就拆、能懒加载就懒加载；` +
      `\n  确实是产品需要就改脚本里的 BUDGET 并说明理由——别调阈值了事。`,
  );
  process.exit(1);
}
console.log("✓ 首屏体积在预算内");
