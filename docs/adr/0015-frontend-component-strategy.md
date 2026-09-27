# 前端组件方案：共用 token 与请求层，组件库分家

三个端共用 `packages/ui` 的设计 token 与 `packages/shared` 的请求层、契约类型；**组件库分开**：C 端自研轻量组件，服务者后台与运营后台用 Element Plus。

对应地图 [#67](https://github.com/QIUZI-HONG/pet-health/issues/67)。仓库与构建形态在 [ADR-0007](0007-frontend-workspace.md) 已经定了（pnpm workspace 多应用），这里定的是**组件从哪来**。技术栈：Vue 3 + TypeScript + `vue-router` + `pinia`（装在当前主版本；2026-09-27 实测 `createRouter` / `createWebHistory` / `defineStore` 等本项目要用的 API 与既有用法一致）。

## Considered Options

- **三端统一 Element Plus**：最省事、一套用法三端通用。但 C 端会一眼看出是「后台管理系统」——交付文档 4.16 的视觉稿（卡片化健康驾驶舱、绿色主色铺满、圆角 16 的浮层）与 Element Plus 的默认观感相差很远，靠样式覆盖去救，最后会变成一屋子 `!important`。
- **三端统一自研组件**：视觉一致性最好、零覆盖。代价是运营后台的表格、筛选、批量操作、表单校验都要自己写——而这些恰好是 Element Plus 最成熟的部分，且后台的观感没有甲方视觉稿约束。
- **分家**（本决策）：C 端自研，后台用 Element Plus。

## Consequences

**一致性靠 token，不靠组件库。** 「三端配色完全一致」（交付文档 4.16）的落点是 `packages/ui/src/tokens.css`——C 端直接用它写样式；Element Plus 侧把它映射到 Element 的 CSS 变量（`--el-color-primary` 等），后台组件跟着 token 走。**token 是唯一的颜色来源，这一条对三个端都成立**。

**跨端复用的边界要清楚**：token、请求层、契约生成的类型三样共享；**业务组件不共享**（C 端的卡片与后台的表格是两种东西）。别为了「复用」把后台组件塞进 C 端。

**C 端要自己维护一份组件清单**：按钮、输入框、卡片、弹窗、标签、分段控件、骨架屏、空/加载/错误/无权限四态。每加一个都算公共资产，要么够通用，要么留在页面里——**不许长出半成品组件库**。清单与约定写在 `apps/c-web/src/components/README.md`。

**C 端的自动检查**：`apps/c-web/scripts/check-tokens.mjs` 在构建前扫描源码，发现硬编码色值（`#RGB` / `rgb()` / `hsl()`）即失败——「无硬编码色值」这条验收标准不靠自觉。它已经挂在 `c-web` 的 `build` 里，CI 会跑。

**没有移动端适配。** 不做 uni-app、不做小程序（[ADR-0001](0001-all-web-clients.md)），所以不引移动端组件库；窗口收窄时按断点收敛栏数即可，不追求手机上的可用性。
