#!/usr/bin/env node
/**
 * 首屏体积预算检查（三端共用；交付文档 9.3 的「首页加载 < 2 秒」在代码侧的可测代理）。
 *
 * 为什么需要它：体积是唯一能**在提交时**拦住的性能指标——真机加载时间要设备与网络，体积不需要；
 * 体积涨了，加载时间一定跟着涨。此前没有任何检查，产物悄悄变大没人知道。
 * 「后台是内部用户才用，慢一点没关系」这个想法撑不过三个月（组件库、图表库、日期库都是这么进来的）。
 *
 * 判定用 **gzip 后**的大小（真实网络传的量），只算**打开默认页真正会下载的文件**。
 *
 * ## 两种首屏判定（三端的路由写法不同，所以留了两条路；不要让它们合并成一条）
 *
 * - `--navigation`（两个后台）：从 `src/navigation.ts` 读出「路由名 → 视图文件」，再从
 *   `src/router/index.ts` 读出默认路由指向哪个路由名。首屏 = 入口 + 外壳 + 共享块 + **默认页那一块**。
 *   加一个模块不用回来改这个脚本（改忘了的表现是预算悄悄被低估）。
 * - `--prefixes`（C 端）：C 端的 `/` 直接挂 `AppShell` 子路由、没有 `redirect`，读不出「默认页」，
 *   所以按**产物名前缀**判定（`index-`、`_plugin-vue_export-helper`、`AppShell-`、`HomeView-`）。
 *
 * ## 各端预算与取值理由（改之前先读这段，别把阈值调高来让构建变绿）
 *
 * | 端 | 预算 | 依据 |
 * | --- | --- | --- |
 * | c-web | 85K | 2026-09-28 实测 70.8K，取 +15% 余量 |
 * | provider-web | 95K | 两个后台产物同构（同一套外壳 + 请求层 + Vue），预算取同一个数：2026-09-29 实测值 + 约 25% |
 * | admin-web | 95K | 同上 |
 *
 * 想让预算随产品阶段变化时改**各端 package.json 里的 `--budget`** 并说明理由。
 *
 * 用法：`pnpm --filter <app> check:bundle`（挂在各端的 build 里，跑在 vite build 之后）。
 */
import { gzipSync } from "node:zlib";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";

const args = process.argv.slice(2);

/** 取 `--key value` 形式的参数值；缺参数时按反空转处理（宁可报错，不要静默用默认值）。 */
function option(name) {
  const index = args.indexOf(`--${name}`);
  if (index === -1) return undefined;
  const value = args[index + 1];
  if (value === undefined || value.startsWith("--")) {
    console.error(`✗ --${name} 缺参数值`);
    process.exit(1);
  }
  return value;
}

const budgetKb = Number(option("budget"));
const prefixesArg = option("prefixes");
const useNavigation = args.includes("--navigation");

if (!Number.isFinite(budgetKb) || budgetKb <= 0) {
  console.error("✗ 必须给 --budget <KB>（各端预算与理由见本文件头部那张表）");
  process.exit(1);
}
if (useNavigation === Boolean(prefixesArg)) {
  console.error("✗ 首屏判定方式二选一：`--navigation` 或 `--prefixes a,b,c`");
  process.exit(1);
}

const cwd = process.cwd();
const assetsDir = join(cwd, "dist", "assets");

/**
 * 判定某块产物是否属于首屏。两种策略的语义见文件头，**返回值必须与原实现逐字节一致**——
 * 换个口径等于把三端已经校准过的预算悄悄换成另一个数。
 */
let isInitialLoad;

if (prefixesArg) {
  // C 端：按产物名前缀。**只算 `index-*` 会低报约 10%**——用户打开站点必然也下
  // AppShell 与 HomeView 那几个块。
  const prefixes = prefixesArg.split(",").map((prefix) => prefix.trim()).filter(Boolean);
  if (prefixes.length === 0) {
    console.error("✗ --prefixes 里没有有效前缀");
    process.exit(1);
  }
  isInitialLoad = (name) => prefixes.some((prefix) => name.startsWith(prefix));
} else {
  // 两个后台：从模块清单与路由表推导，避免「加一个模块忘了改脚本」。
  const viewNames = readViewNames(join(cwd, "src", "navigation.ts"));
  const defaultView = readDefaultView(join(cwd, "src", "router", "index.ts"), viewNames);
  /** 默认页之外的路由块：进那个模块才会下载，因此不计入首屏 */
  const lazyViewPrefixes = [...viewNames.values()].filter((view) => view !== defaultView);
  isInitialLoad = (name) => !lazyViewPrefixes.some((prefix) => name.startsWith(`${prefix}-`));
}

if (!existsSync(assetsDir)) {
  console.error(`✗ 没找到 ${assetsDir}，先跑 vite build`);
  process.exit(1);
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
  console.log(`  ${name.padEnd(48)} ${kb.toFixed(1)}K`);
}
console.log(`  ${"合计".padEnd(46)} ${totalKb.toFixed(1)}K（预算 ${budgetKb}K）`);

if (totalKb > budgetKb) {
  console.error(
    `\n✗ 首屏体积 ${totalKb.toFixed(1)}K 超出预算 ${budgetKb}K。` +
      `\n  先看是哪一块涨的（上面按大小排序），能拆就拆、能懒加载就懒加载；` +
      `\n  确实是产品需要就改 package.json 里的 --budget 并说明理由——别调阈值了事。`,
  );
  process.exit(1);
}
console.log("✓ 首屏体积在预算内");

/** 从模块清单里读出「路由名 → 视图文件名」。清单是导航、标题、路由表的唯一定义处。 */
function readViewNames(navigationFile) {
  if (!existsSync(navigationFile)) {
    console.error(`✗ 没找到 ${navigationFile}，检查一下应用目录对不对`);
    process.exit(1);
  }
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
function readDefaultView(routerFile, viewNames) {
  if (!existsSync(routerFile)) {
    console.error(`✗ 没找到 ${routerFile}，检查一下应用目录对不对`);
    process.exit(1);
  }
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
