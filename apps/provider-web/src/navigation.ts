/**
 * 服务者后台的模块清单——**左栏、顶栏标题、路由表的唯一定义处**（避免同一批模块名在三处各写一遍，
 * 然后慢慢分叉）。
 *
 * 模块与顺序来自交付文档 3.1 的服务者后台模块树（文档用词「商家端（PC后台）」，本项目统一称服务者，见 CONTEXT.md），
 * 路径沿用 3.2 页面清单 P019–P028 的路由列
 * （`/b/*` 是那份文档给这个端定的前缀，保留它以便和页面清单逐条对上）。术语按 CONTEXT.md：一律说
 * 术语按 CONTEXT.md 收口：文档用词「店铺设置」在这里叫「设置」。
 *
 * 每个模块「这一页要做什么」不写在这里，写在各自的视图里（`views/`）：那里才是页面的正主，
 * 真接上数据时改的也是它。
 */
import type { RouteComponent } from "vue-router";

export interface ConsoleModule {
  /** 路由名：导航高亮与跳转都靠它，不靠路径（改路径不该影响导航） */
  name: string;
  /** 子路由路径，拼在外壳的 `/b` 之后 */
  path: string;
  /** 左栏与顶栏的模块名 */
  label: string;
  /** 左栏图标（emoji）：交付文档 4.7 要的 lucide 图标是【假设】项，本波不引依赖，先用 emoji 占位 */
  icon: string;
  /** 页面组件（懒加载） */
  view: () => Promise<RouteComponent>;
}

export const providerModules: ConsoleModule[] = [
  {
    name: "dashboard",
    path: "dashboard",
    label: "今日概览",
    icon: "🧭",
    view: () => import("./views/DashboardView.vue"),
  },
  {
    name: "orders",
    path: "orders",
    label: "订单管理",
    icon: "📋",
    view: () => import("./views/OrdersView.vue"),
  },
  {
    name: "redeem",
    path: "redeem",
    label: "核销管理",
    icon: "✅",
    view: () => import("./views/RedeemView.vue"),
  },
  {
    name: "coupons",
    path: "coupons",
    label: "券管理",
    icon: "🎟️",
    view: () => import("./views/CouponsView.vue"),
  },
  {
    name: "assessment",
    path: "assess",
    label: "考核中心",
    icon: "🏅",
    view: () => import("./views/AssessmentView.vue"),
  },
  {
    name: "customers",
    path: "customers",
    label: "客户管理",
    icon: "👥",
    view: () => import("./views/CustomersView.vue"),
  },
  {
    name: "stats",
    path: "stats",
    label: "数据看板",
    icon: "📈",
    view: () => import("./views/StatsView.vue"),
  },
  {
    name: "standard",
    path: "standard",
    label: "服务标准",
    icon: "📐",
    view: () => import("./views/StandardView.vue"),
  },
  {
    name: "marketing",
    path: "marketing",
    label: "营销中心",
    icon: "📣",
    view: () => import("./views/MarketingView.vue"),
  },
  {
    name: "settlement",
    path: "settle",
    label: "结算中心",
    icon: "💰",
    view: () => import("./views/SettlementView.vue"),
  },
  {
    name: "settings",
    path: "settings",
    label: "设置",
    icon: "⚙️",
    view: () => import("./views/SettingsView.vue"),
  },
];
