/**
 * 运营后台的模块清单——**左栏、顶栏标题、路由表的唯一定义处**。
 *
 * 模块与顺序来自交付文档 3.1「平台运营后台」的模块树。术语按 CONTEXT.md 收口：文档里那一格
 * 叫「商家审核」（文档用词）、路由写作 `/admin/merchants`，这里一律改成「服务者审核」与 `/admin/providers`
 * ——禁用词不止在文案里不许出现，路由与代码标识符里同样不许（清单见 CONTEXT.md）。
 *
 * 运营后台与**服务者后台是两个独立登录域**（CONTEXT.md），所以两端的模块清单没有任何交集，
 * 共用的只有外壳与四态组件（packages/ui）。
 *
 * 每个模块「这一页要做什么」写在各自的视图里（`views/`）。
 */
import type { RouteComponent } from "vue-router";

export interface ConsoleModule {
  /** 路由名：导航高亮与跳转都靠它，不靠路径 */
  name: string;
  /** 子路由路径，拼在外壳的 `/admin` 之后 */
  path: string;
  /** 左栏与顶栏的模块名 */
  label: string;
  /** 左栏图标（emoji）：交付文档 4.7 要的 lucide 图标是【假设】项，本波不引依赖 */
  icon: string;
  /** 页面组件（懒加载） */
  view: () => Promise<RouteComponent>;
}

export const adminModules: ConsoleModule[] = [
  {
    name: "providers",
    path: "providers",
    label: "服务者审核",
    icon: "🛡️",
    view: () => import("./views/ProviderAuditView.vue"),
  },
  {
    name: "catalog",
    path: "catalog",
    label: "标准目录管理",
    icon: "📚",
    view: () => import("./views/CatalogView.vue"),
  },
  {
    name: "coupon-pool",
    path: "coupon-pool",
    label: "券池管理",
    icon: "🎟️",
    view: () => import("./views/CouponPoolView.vue"),
  },
  {
    name: "subsidy",
    path: "subsidy",
    label: "补贴券窗口",
    icon: "🧧",
    view: () => import("./views/SubsidyView.vue"),
  },
  {
    name: "users",
    path: "users",
    label: "用户管理",
    icon: "👥",
    view: () => import("./views/UsersView.vue"),
  },
  {
    name: "content",
    path: "content",
    label: "内容审核",
    icon: "📝",
    view: () => import("./views/ContentAuditView.vue"),
  },
  {
    name: "stats",
    path: "stats",
    label: "数据看板",
    icon: "📈",
    view: () => import("./views/StatsView.vue"),
  },
  {
    name: "ai-ops",
    path: "ai-ops",
    label: "AI 运营",
    icon: "🤖",
    view: () => import("./views/AiOpsView.vue"),
  },
  {
    name: "points-invite",
    path: "points-invite",
    label: "积分与邀请配置",
    icon: "🎁",
    view: () => import("./views/GrowthConfigView.vue"),
  },
  {
    name: "assess-rules",
    path: "assess-rules",
    label: "考核规则配置",
    icon: "🏅",
    view: () => import("./views/AssessRulesView.vue"),
  },
  {
    name: "settings",
    path: "settings",
    label: "系统配置",
    icon: "⚙️",
    view: () => import("./views/SettingsView.vue"),
  },
];
