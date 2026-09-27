/**
 * C 端路由。五个主页面挂进同一套框架（AppShell），另有独立的登录页。
 *
 * `meta.title` 同时用于浏览器标题与顶栏，避免每个页面各写一遍。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";

/**
 * 路由表。**导出是为了让测试直接用真表**——测试里复制一份路由，等于把「五个主页面可导航」
 * 这条验收标准变成一句空话（从真表里删掉一个页面，复制的那份照样绿）。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: "/",
    component: () => import("../layouts/AppShell.vue"),
    children: [
      {
        path: "",
        name: "home",
        component: () => import("../views/HomeView.vue"),
        meta: { title: "首页" },
      },
      {
        path: "services",
        name: "services",
        component: () => import("../views/ServicesView.vue"),
        meta: { title: "服务" },
      },
      {
        path: "ai",
        name: "ai",
        component: () => import("../views/AiConsultView.vue"),
        meta: { title: "AI 管家" },
      },
      {
        path: "records",
        name: "records",
        component: () => import("../views/RecordsView.vue"),
        meta: { title: "健康档案" },
      },
      {
        path: "profile",
        name: "profile",
        component: () => import("../views/ProfileView.vue"),
        meta: { title: "我的" },
      },
    ],
  },
  {
    path: "/login",
    name: "login",
    component: () => import("../views/LoginView.vue"),
    meta: { title: "登录" },
  },
  // 兜底：不认识的地址回首页，而不是白屏
  { path: "/:pathMatch(.*)*", redirect: "/" },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? "";
  document.title = title ? `${title} · 宠物健康管家` : "宠物健康管家";
});
