/**
 * 服务者后台路由。全部模块挂在同一个外壳（`layouts/AppShell.vue`）下，URL 前缀 `/b`
 * （交付文档 3.2 页面清单：P019–P028 都是 `/b/*`）。
 *
 * 服务者后台是**独立登录域**（CONTEXT.md），所以登录页**不套外壳**（`/login`，与 `/b/**` 平级）
 * ——套了就会出现「未登录时左栏里那 11 个模块都点得动」这种界面。
 *
 * **刻意不做全局路由守卫**：本端每一页都用 `ConsoleGate` 自己判会话（见 packages/ui 的说明），
 * 未登录时页面里显示的是「尚未登录 + 为什么」而不是被一把弹到登录页——直接打开一个深链接的
 * 体验更好，也和 C 端（ADR-0016）同一条口径。登录入口在外壳顶栏与闸门文案里都指得到。
 *
 * `routes` 导出是为了让后续的导航测试直接用真表——测试里复制一份路由，等于把「模块可导航」
 * 这条验收标准变成空话（照 c-web 的做法，见 apps/c-web/src/__tests__/shell.spec.ts）。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { providerModules } from "../navigation";

export const routes: RouteRecordRaw[] = [
  {
    path: "/b",
    component: () => import("../layouts/AppShell.vue"),
    children: providerModules.map((module) => ({
      path: module.path,
      name: module.name,
      component: module.view,
      meta: { title: module.label, icon: module.icon },
    })),
  },
  // 登录页：**不套外壳**（未登录时左栏的模块不该可用），也**不套闸门**
  { path: "/login", name: "login", component: () => import("../views/LoginView.vue"),
    meta: { title: "登录" } },
  // 兜底：不认识的地址回首屏，而不是白屏
  { path: "/", redirect: { name: "dashboard" } },
  { path: "/:pathMatch(.*)*", redirect: { name: "dashboard" } },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? "";
  document.title = title ? `${title} · 服务者后台` : "服务者后台";
});
