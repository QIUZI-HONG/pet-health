/**
 * 服务者后台的会话状态。
 *
 * 只回答一件事：**本地有没有本域（provider）的令牌**——有就发请求，没有就显示未登录态。
 * 令牌由请求层在 40101 且换不回来时清掉并广播「会话失效」（`onSessionExpired`），
 * 这里接住那次广播，把界面一起收干净（停在「看起来已登录、每个请求都 401」是最难查的一种状态）。
 *
 * **登录入口在 2026-09-30 接上了**（F021）：`contract/provider.yaml` 的 `/auth/login` 与
 * 后端 `ConsoleAuthController` 一起落地，`views/LoginView.vue` 走 `providerAuth.login` 拿到
 * **provider 域**令牌后调这里的 {@link signIn} 落盘。在此之前这里刻意没有登录动作
 * ——不能摆一个「能点但登不进去」的表单，那会让人以为鉴权已经通了。
 */
import { computed, ref } from "vue";
import { onSessionExpired, type SessionTokens } from "@pet-health/shared";
import type { ConsoleSessionStatus } from "@pet-health/ui";
import { providerTokenStore } from "./api/client";
import { providerAuth } from "./api/auth";

const status = ref<ConsoleSessionStatus>("unknown");

/** 应用启动时调一次：接住请求层的「会话失效」广播，并按本地令牌给一个初始状态。 */
export function initProviderSession(): void {
  onSessionExpired(markSessionExpired);
  markSessionExpired();
}

/**
 * 登录成功后的收尾：把令牌落盘，再重算会话状态。
 *
 * 顺序不能反：`status` 是从令牌读出来的（`markSessionExpired` 里同一句判断），
 * 先置状态后写令牌会读到旧的「没有令牌」，页面会停在未登录。
 */
export function signIn(tokens: SessionTokens): void {
  providerTokenStore.save(tokens);
  markSessionExpired();
}

/**
 * 退出：先通知服务端吊销 Refresh，再清本地。
 *
 * 服务端那一步失败**不阻塞**本地清理——用户点了退出就该退出去，
 * 而 Refresh 已经能被服务端吊销（失败时它会自然过期，最多 7 天）。这条与 C 端的取舍一致。
 */
export async function signOut(): Promise<void> {
  const refreshToken = providerTokenStore.get()?.refreshToken;
  try {
    if (refreshToken) {
      await providerAuth.logout({ refresh_token: refreshToken });
    }
  } finally {
    providerTokenStore.clear();
    markSessionExpired();
  }
}

/** 会话失效：令牌已被请求层清掉，这里把界面状态一起收掉。 */
function markSessionExpired(): void {
  status.value = providerTokenStore.get() ? "authenticated" : "anonymous";
}

/**
 * 单例（一个页面只有一个会话），所以直接返回同一份状态，不需要 pinia。
 *
 * ⚠️ **用法：先解构再进模板**
 * ```ts
 * const { status, hasToken } = useProviderSession();   // ✅ status 是顶层 ref，模板自动解包
 * const session = useProviderSession();                // ❌ 模板里 session.hasToken 拿到的是
 *                                                      //    ComputedRef 对象，恒为真
 * ```
 * 这条不是因为「解构更优雅」：Vue 只对**顶层** ref 做模板解包，对象属性里的 ref 不解包。
 * 2026-09-30 写登录入口时就踩了这个——`!session.hasToken` 恒为 `false`，
 * 于是「未登录」和「已登录」两个分支永远走后者（测试当场抓到）。
 * 各页面（`const { status } = useProviderSession()`）一直是解构的，所以没出过这个问题。
 */
export function useProviderSession() {
  return {
    status,
    hasToken: computed(() => status.value === "authenticated"),
  };
}
