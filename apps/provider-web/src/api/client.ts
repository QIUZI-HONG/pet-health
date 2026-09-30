/**
 * 服务者后台的请求口与令牌存放。
 *
 * 后台两端是**独立登录域**（ADR-0012）：本端只认 `provider` 域的令牌，存 `ph.provider.*`
 * 这一套 key，与 C 端、运营后台互不通用（同一个浏览器上三个端可以同时登录，不会互相覆盖）。
 *
 * 换发令牌的路径**已经接上**（F021，2026-09-30）：`contract/provider.yaml` 的
 * `/auth/refresh` 这条路径在那一轮补上，后端由 `ConsoleAuthController` 实现。
 * 在此之前这里传的是 `null`（「这个域还没有换发接口，40101 直接结束会话」），
 * 于是后台的会话一旦过期就只能重新登录——现在 40101 会静默换一次令牌（与 C 端同一条规则：
 * 一次性使用、整族吊销、并发单飞，全在共享请求层里）。
 */
import { createHttpClient, createTokenStore } from "@pet-health/shared";

/** 本端的令牌存放。页面的会话状态、请求层的鉴权头都读它。 */
export const providerTokenStore = createTokenStore("provider");

/** 本端的请求口。除「令牌 + 换发路径」外，行为与 C 端完全一致（同一份实现）。 */
export const providerHttp = createHttpClient({
  tokenStore: providerTokenStore,
  refreshPath: "/api/v1/provider/auth/refresh",
});
