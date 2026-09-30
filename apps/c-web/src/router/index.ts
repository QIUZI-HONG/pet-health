/**
 * C 端路由。所有页面挂进同一套框架（AppShell），另有独立的登录页。
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
        // 门店详情：**价格在这里**（服务页只负责把人带过来）。
        // 不需要登录（ADR-0037 第一节），所以与别的详情页一样，它自己不套 SessionGate。
        path: "providers/:id",
        name: "provider-detail",
        component: () => import("../views/ProviderDetailView.vue"),
        meta: { title: "门店详情" },
      },
      {
        // 按项目找服务：服务页是「分类 → 门店」，这一条是反向的「项目 → 门店」。
        path: "catalog",
        name: "catalog",
        component: () => import("../views/CatalogView.vue"),
        meta: { title: "按项目找服务" },
      },
      {
        // 某个项目有哪些门店能做（按项目找店的落点）。
        path: "catalog/items/:code",
        name: "catalog-item",
        component: () => import("../views/CatalogItemView.vue"),
        meta: { title: "可预约门店" },
      },
      {
        // AI 找服务（F011 规则版）：描述症状 → 推荐项目。需要登录（与 AI 咨询一致）。
        path: "service-finder",
        name: "service-finder",
        component: () => import("../views/ServiceFinderView.vue"),
        meta: { title: "AI 找服务" },
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
      {
        path: "legal/:code",
        name: "legal",
        component: () => import("../views/LegalView.vue"),
        meta: { title: "条款与说明" },
      },
      {
        path: "messages",
        name: "messages",
        component: () => import("../views/MessagesView.vue"),
        meta: { title: "消息中心" },
      },
      // ---- 交易与增长（切片 #109–#113）：订单、券包、积分、权益、邀请 ----
      // 都是同一套框架内的页面（左栏 + 顶栏 + 内容区），不另起一套导航。
      {
        path: "orders",
        name: "orders",
        component: () => import("../views/MyOrdersView.vue"),
        meta: { title: "我的订单" },
      },
      {
        // 下单页：门店与服务项由查询参数带入（`provider_id` / `service_id`）。
        // `new` 是静态段，vue-router 按路径排序优先于 `orders/:id`，所以与详情的先后无关。
        path: "orders/new",
        name: "order-create",
        component: () => import("../views/OrderCreateView.vue"),
        meta: { title: "预约下单" },
      },
      {
        path: "orders/:id",
        name: "order-detail",
        component: () => import("../views/OrderDetailView.vue"),
        meta: { title: "订单详情" },
      },
      {
        path: "coupons",
        name: "coupons",
        component: () => import("../views/CouponsView.vue"),
        meta: { title: "我的券" },
      },
      {
        path: "points",
        name: "points",
        component: () => import("../views/PointsView.vue"),
        meta: { title: "积分中心" },
      },
      {
        path: "rights",
        name: "rights",
        component: () => import("../views/RightsView.vue"),
        meta: { title: "我的权益" },
      },
      {
        path: "invites",
        name: "invites",
        component: () => import("../views/InviteView.vue"),
        meta: { title: "邀请有礼" },
      },
      {
        // 社区：经验卡片（同款宠友圈）与问答互助（切片 #84 / F020）。
        // **不进左栏**：ADR-0041 第一节定的是「不做独立 Tab」（社区嵌在首页与档案页里），
        // 所以它是次级页——入口在首页的「快捷服务」里（见 HomeView 的 quickEntries）。
        path: "community",
        name: "community",
        component: () => import("../views/CommunityView.vue"),
        meta: { title: "社区" },
      },
      {
        // 知识库（F024 / F025，决策见 ADR-0033）：**需要登录**，与社区同属「内容面」。
        // 不进左栏：它不是主页面（ADR-0016 的信息架构），入口在首页的「快捷服务」里。
        path: "knowledge",
        name: "knowledge",
        component: () => import("../views/KnowledgeView.vue"),
        meta: { title: "知识库" },
      },
      {
        // 一条知识条目的正文。编号（形如 K-0011）就是路径参数，可分享可收藏。
        path: "knowledge/:code",
        name: "knowledge-entry",
        component: () => import("../views/KnowledgeEntryView.vue"),
        meta: { title: "知识条目" },
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
