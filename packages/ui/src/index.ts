/**
 * 设计 token 与共享组件。三端共用一套颜色、字号、圆角、间距，
 * 以便满足交付文档 4.16 节「三端配色完全一致」的要求（ADR-0007）。
 *
 * 共享的边界（ADR-0015）：**token 共用，业务组件不共用**。这里放的是「三端交互同构」的东西——
 * 外壳（纯布局，两端只有端名与模块清单不同）、四态组件（两端是同一套状态），
 * 以及实现 docs/conventions.md 那几条交互约定的 composable（分页四态 + 秒级节流 + 错误分类）。
 * **C 端的业务组件（卡片、驾驶舱、档案）仍在 apps/c-web/src/components/，别往这里搬**：
 * 分家的理由是视觉与业务形态（C 端的卡片 vs 后台的表格），不是「凡是公共代码都不能共享」。
 *
 * 用法：两个后台在 `app.use(router)` 之后直接引 `ConsoleShell` / `ConsoleState`；
 * 样式引 `@pet-health/ui/tokens.css`（三端都要）与 `@pet-health/ui/console.css`（只有后台要）。
 */
export { default as ConsoleShell } from "./components/ConsoleShell.vue";
export { default as ConsoleState } from "./components/ConsoleState.vue";
export { default as ConsoleGate } from "./components/ConsoleGate.vue";
export { default as ConsoleListState } from "./components/ConsoleListState.vue";
export type { ConsoleNavItem, ConsoleStateVariant, ConsoleSessionStatus } from "./components/types";

// 交互状态机：分页加载与四态、写操作闸门。原先在三个应用里各有拷贝（两个后台逐字相同），
// 同一个「点完禁用 2 秒」有三个实现——改一处必漏两处。
export { usePagedList } from "./composables/usePagedList";
export type { PagedListOptions, PagedListResult } from "./composables/usePagedList";
export { useSubmitAction } from "./composables/useSubmitAction";
export type { SubmitAction, SubmitOutcome } from "./composables/useSubmitAction";
