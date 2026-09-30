/**
 * 运营后台路由。全部模块挂在同一个外壳（`layouts/AppShell.vue`）下，URL 前缀 `/admin`
 * （交付文档 3.2 页面清单 P033–P036 都用这个前缀）。
 *
 * 运营后台是**独立登录域**，与服务者后台不互通（CONTEXT.md），所以登录页**不套外壳**
 * （`/login`，与 `/admin/**` 平级）——套了就会出现「未登录时左栏 9 个模块都点得动」。
 *
 * **刻意不做全局路由守卫**：每一页都用 `ConsoleGate` 自己判会话（见 packages/ui 的说明），
 * 未登录时页面里显示「尚未登录 + 为什么」而不是被一把弹到登录页——直接打开深链接的体验更好，
 * 与 C 端（ADR-0016）同一条口径。
 *
 * `routes` 导出是为了让后续的导航测试直接用真表（测试复制一份路由，验收标准就守不住了）。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { adminModules } from "../navigation";

export const routes: RouteRecordRaw[] = [
  {
    path: "/admin",
    component: () => import("../layouts/AppShell.vue"),
    children: adminModules.map((module) => ({
      path: module.path,
      name: module.name,
      component: module.view,
      meta: { title: module.label, icon: module.icon },
    })),
  },
  // 登录页：不套外壳（未登录时左栏的模块不该可用），也不套闸门
  { path: "/login", name: "login", component: () => import("../views/LoginView.vue"),
    meta: { title: "登录" } },
  // 兜底：不认识的地址回首屏，而不是白屏
  { path: "/", redirect: { name: "providers" } },
  { path: "/:pathMatch(.*)*", redirect: { name: "providers" } },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? "";
  document.title = title ? `${title} · 运营后台` : "运营后台";
});
