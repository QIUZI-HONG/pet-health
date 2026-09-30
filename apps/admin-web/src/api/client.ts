/**
 * 运营后台的请求口与令牌存放。
 *
 * 运营后台与服务者后台是**两个独立登录域**（ADR-0012）：本端只认 `admin` 域的令牌，存
 * `ph.admin.*` 这一套 key。同一个浏览器上三个端可以同时登录，互不覆盖、互不通用
 * （C 端的令牌调本端接口会被后端按未登录处理，见 `JwtAuthenticationFilter` 的域比对）。
 *
 * 换发令牌的路径**已经接上**（F021，2026-09-30）：`contract/admin.yaml` 的 `/auth/refresh`
 * 在那一轮补上、后端由 `ConsoleAuthController` 实现。在此之前这里传的是 `null`
 * （「这个域还没有换发接口，40101 直接结束会话」），会话一过期就只能重新登录；
 * 现在 40101 会静默换一次（一次性使用、整族吊销、并发单飞都在共享请求层里）。
 */
import { createHttpClient, createTokenStore } from "@pet-health/shared";

/** 本端的令牌存放。页面的会话状态、请求层的鉴权头都读它。 */
export const adminTokenStore = createTokenStore("admin");

/** 本端的请求口。除「令牌 + 换发路径」外，行为与 C 端完全一致（同一份实现）。 */
export const adminHttp = createHttpClient({
  tokenStore: adminTokenStore,
  refreshPath: "/api/v1/admin/auth/refresh",
});
