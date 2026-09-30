/**
 * 共享外壳组件的公共类型。放在单独的 .ts 里而不是 SFC 内部——SFC 的 `<script setup>` 不能导出类型，
 * 而两个后台都要用同一份（避免一端自定义、另一端跟着漂）。
 */

/** 左栏的一个模块入口。`name` 是**路由名**，高亮与跳转都靠它，不用路径——改路径不该影响导航。 */
export interface ConsoleNavItem {
  /** 路由名，与各端路由表里的 `name` 一致 */
  name: string;
  /** 左栏与顶栏显示的模块名 */
  label: string;
  /** 图标（emoji）。交付文档 4.7 要的 lucide 是【假设】项，本波不引依赖，先用 emoji 占位 */
  icon?: string;
}

/** 四态：空 / 加载 / 错误 / 无权限（交付文档 4.1「状态明确」、4.16.9 的状态对照表）。 */
export type ConsoleStateVariant = "empty" | "loading" | "error" | "forbidden";

/**
 * 后台的会话状态（由各端自己的 session 模块给出，见 `ConsoleGate.vue`）。
 *
 * - `unknown`：还没确认本地有没有令牌（应用启动的第一帧）
 * - `anonymous`：确认没有令牌（或令牌已被后端作废）——**不是错误**，就是没登录
 * - `authenticated`：有令牌，请求可以发
 */
export type ConsoleSessionStatus = "unknown" | "anonymous" | "authenticated";
