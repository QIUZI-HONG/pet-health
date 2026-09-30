/**
 * 首屏体积预算检查（两个后台共用同一份实现）。
 *
 * 为什么后台也要：体积是唯一能**在提交时**拦住的性能指标。c-web 早就有这道闸，
 * 后台没有时，产物悄悄变大没人知道——而「后台是内部用户才用，慢一点没关系」这个想法
 * 撑不过三个月（组件库、图表库、日期库都是这么进来的）。
 *
 * 口径：**打开后台默认页真正会下载的文件**——
 *   - 入口（`index-*`：vue、vue-router、请求层、公共样式）；
 *   - 外壳（`AppShell-*`）与共享块（如 `ConsoleState-*`、`money-*` 这些被多个路由引用的块）；
 *   - **默认路由那一页**（`router/index.ts` 里 `/` 的跳转目标对应的视图块）。
 *
 * 其余路由块不算：那是点进那个模块才下载的。判定用 gzip 后的大小（真实网络传的量）。
 *
 * 「哪些是路由视图」不写死在这里：从 `src/navigation.ts` 读出「路由名 → 视图文件」，
 * 再从 `src/router/index.ts` 读出默认路由指向哪个路由名。这样加一个模块不用回来改这个脚本
 * （改忘了的表现是预算悄悄被低估）。
 *
 * 预算取值：2026-09-29 实测值 + 约 25% 余量——正常开发碰不到，但拖进来一个大依赖会立刻红。
 * 想让预算随产品阶段变化时改 BUDGET 并说明理由，别把阈值调高来让构建变绿。
 */
import { gzipSync } from "node:zlib";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

/**
 * gzip 后的预算（KB）。两个后台的产物几乎同构（同一套外壳 + 请求层 + Vue），
 * 差别只在各自的视图大小，所以预算取同一个数；真要分开时按实测值各给各的。
 */
const BUDGET = {
  total: 95,
};

const navigationFile = join(process.cwd(), "src", "navigation.ts");
const routerFile = join(process.cwd(), "src", "router", "index.ts");
const assetsDir = join(process.cwd(), "dist", "assets");

/** 从模块清单里读出「路由名 → 视图文件名」。清单是导航、标题、路由表的唯一定义处。 */
function readViewNames() {
  const text = readFileSync(navigationFile, "utf8");
  const pattern = /name:\s*"([^"]+)"[\s\S]*?view:\s*\(\)\s*=>\s*import\("\.\/views\/([\w]+)\.vue"\)/g;
  const map = new Map();
  for (const match of text.matchAll(pattern)) {
    map.set(match[1], match[2]);
  }
  if (map.size === 0) {
    console.error(`✗ 没能从 ${navigationFile} 读出模块清单，检查一下它的写法是否变了`);
    process.exit(1);
  }
  return map;
}

/** 默认路由（`/` 的跳转目标）对应的视图——它属于首屏，其余视图块不是。 */
function readDefaultView(viewNames) {
  const text = readFileSync(routerFile, "utf8");
  const match = /path:\s*"\/"[\s\S]{0,120}?redirect:\s*\{\s*name:\s*"([^"]+)"/.exec(text);
  if (!match) {
    console.error(`✗ 没能从 ${routerFile} 读出默认路由，检查一下路由表是否变了`);
    process.exit(1);
  }
  const view = viewNames.get(match[1]);
  if (!view) {
    console.error(`✗ 默认路由指向的模块「${match[1]}」不在模块清单里`);
    process.exit(1);
  }
  return view;
}

const viewNames = readViewNames();
const defaultView = readDefaultView(viewNames);
/** 除默认页之外的路由块：进那个模块才会下载，因此不计入首屏 */
const lazyViewPrefixes = [...viewNames.values()].filter((view) => view !== defaultView);

let totalKb = 0;
const rows = [];
for (const name of readdirSync(assetsDir)) {
  if (!/\.(js|css)$/.test(name)) continue;
  if (lazyViewPrefixes.some((prefix) => name.startsWith(`${prefix}-`))) continue;
  const gzipped = gzipSync(readFileSync(join(assetsDir, name)), { level: 9 }).length;
  const kb = gzipped / 1024;
  totalKb += kb;
  rows.push([name, kb]);
}

if (rows.length === 0) {
  console.error("✗ 没找到入口产物，先跑 vite build");
  process.exit(1);
}

console.log(`首屏体积（gzip，默认页 = ${defaultView}）：`);
for (const [name, kb] of rows.sort((a, b) => b[1] - a[1])) {
  console.log(`  ${name.padEnd(48)} ${kb.toFixed(1)}K`);
}
console.log(`  ${"合计".padEnd(46)} ${totalKb.toFixed(1)}K（预算 ${BUDGET.total}K）`);

if (totalKb > BUDGET.total) {
  console.error(
    `\n✗ 首屏体积 ${totalKb.toFixed(1)}K 超出预算 ${BUDGET.total}K。` +
      `\n  先看是哪一块涨的（上面按大小排序），能拆就拆、能懒加载就懒加载；` +
      `\n  确实是产品需要就改脚本里的 BUDGET 并说明理由——别调阈值了事。`,
  );
  process.exit(1);
}
console.log("✓ 首屏体积在预算内");
