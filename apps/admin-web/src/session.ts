/**
 * 运营后台的会话状态。
 *
 * 只回答一件事：**本地有没有本域（admin）的令牌**——有就发请求，没有就显示未登录态。
 * 令牌由请求层在 40101 且换不回来时清掉并广播「会话失效」（`onSessionExpired`），
 * 这里接住那次广播，把界面一起收干净（停在「看起来已登录、每个请求都 401」是最难查的一种状态）。
 *
 * **登录入口在 2026-09-30 接上了**（F021）：`contract/admin.yaml` 的 `/auth/login` 与后端
 * `ConsoleAuthController` 一起落地，`views/LoginView.vue` 拿到 **admin 域**令牌后调
 * {@link signIn} 落盘。与 C 端不同，运营后台是**名单制**（后端 `app.console.admin-user-ids`，
 * fail-closed）——不在名单里的账号登录会拿到 40300。
 */
import { computed, ref } from "vue";
import { onSessionExpired, type SessionTokens } from "@pet-health/shared";
import type { ConsoleSessionStatus } from "@pet-health/ui";
import { adminTokenStore } from "./api/client";
import { adminAuth } from "./api/auth";

const status = ref<ConsoleSessionStatus>("unknown");

/** 登录成功后的收尾：先落盘令牌，再重算状态（顺序不能反，见 provider-web 同名函数的说明）。 */
export function signIn(tokens: SessionTokens): void {
  adminTokenStore.save(tokens);
  markSessionExpired();
}

/** 退出：先通知服务端吊销 Refresh，再清本地；服务端那一步失败不阻塞本地清理。 */
export async function signOut(): Promise<void> {
  const refreshToken = adminTokenStore.get()?.refreshToken;
  try {
    if (refreshToken) {
      await adminAuth.logout({ refresh_token: refreshToken });
    }
  } finally {
    adminTokenStore.clear();
    markSessionExpired();
  }
}

/**
 * 当前身份是不是超级管理员——**现在恒为 false，且这是有意的**。
 *
 * ADR-0037 的权限矩阵把「清退服务者」与「删除目录项」划给超级管理员（运营不能做），但令牌里
 * **只带登录域**（`domain`，见后端 `JwtService` 的 `CLAIM_DOMAIN`）、不带角色，后台账号与角色
 * 体系也还没落地（ADR-0035 的「需要协调」第 3 条：服务端对自己发的 admin 令牌一视同仁）。
 *
 * 所以「谁是超管」在前端**没有任何可信来源**。这里刻意不写一个「读令牌里的 roles 声明」的实现：
 * 那个字段现在不存在，猜一个名字等于凭猜测放行，而猜错的代价是一次不可逆的清退。
 * 结论是**不渲染**清退入口（而不是渲染出来靠二次确认提醒「请确认你有权」——
 * 界面假装拦住不算拦住，见 `ProviderListPanel`）。
 *
 * 角色声明进令牌后（或有了 `GET /admin/me` 之类），把这一位接上即可，页面读的就是它，不用改。
 */
const isSuperAdmin = ref(false);

/** 应用启动时调一次：接住请求层的「会话失效」广播，并按本地令牌给一个初始状态。 */
export function initAdminSession(): void {
  onSessionExpired(markSessionExpired);
  markSessionExpired();
}

/** 会话失效：令牌已被请求层清掉，这里把界面状态一起收掉。 */
function markSessionExpired(): void {
  status.value = adminTokenStore.get() ? "authenticated" : "anonymous";
}

/**
 * 单例（一个页面只有一个会话），所以直接返回同一份状态，不需要 pinia。
 *
 * ⚠️ **用法：先解构再进模板**（`const { status, hasToken } = useAdminSession()`）。
 * Vue 只对**顶层** ref 做模板解包，对象属性里的 ref 不会——2026-09-30 写登录入口时在
 * provider-web 那边踩过：`!session.hasToken` 恒为 false，两个分支永远走后者。
 * 模板里请先解构（`const { status } = useAdminSession()`）：嵌套在对象里的 ref 在模板中不会自动解包。
 */
export function useAdminSession() {
  return {
    status,
    hasToken: computed(() => status.value === "authenticated"),
    /** 能不能做「只能超级管理员做」的动作（清退）。**当前恒为 false**，理由见上面那段注释。 */
    isSuperAdmin,
  };
}
