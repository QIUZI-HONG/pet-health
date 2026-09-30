/**
 * 页面测试的公共件。三件事只有一份，避免九个 spec 各写一遍、然后各错一处。
 *
 *   - **会话怎么种**：本端没有 pinia，会话就是「本地有没有本域令牌」（`src/session.ts`）。
 *     所以种会话 = 往 `providerTokenStore` 写/清一对令牌，再跑一次 `initProviderSession()`——
 *     **走真实的会话实现**，不 mock 它：闸门的判断顺序（先看有没有令牌，再发请求）正是要验的东西。
 *     注意本端**没有** C 端那种 `error` 会话态：`ConsoleGate` 只认「有没有身份」（unknown/anonymous/
 *     authenticated），后端不可用表现为**请求失败**，由页面自己的错误态覆盖——两端的会话模型不同，
 *     所以这里的 SessionKind 比 c-web 少一种，不是漏了。
 *   - **失败怎么造**：业务错误一律是 {@link ApiError}（码、文案、请求 ID 三样都要能指定）。
 *   - **页面怎么挂**：**用真路由表**（`src/router/index.ts`）。测试里复制一份路由，等于把
 *     「页面挂在框架上」这条验收标准变成空话——路由名改了、页面没挂上，测试照样绿。
 *     但只挂页面组件（不挂 AppShell）：外壳的导航与顶栏与这一层的断言无关。
 */
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import type { Component } from "vue";
import { ApiError } from "@pet-health/shared";
import { routes as realRoutes } from "../router";
import { providerTokenStore } from "../api/client";
import { initProviderSession } from "../session";

/** 三种会话态：已登录（有本域令牌）/ 未登录（没有令牌）。见文件头对「为什么没有 error 态」的说明。 */
export type SessionKind = "authenticated" | "anonymous";

/** 种出会话态：先写令牌，再初始化会话单例（顺序不能反——会话状态是从令牌读出来的）。 */
export function seedSession(kind: SessionKind = "authenticated"): void {
  if (kind === "authenticated") {
    providerTokenStore.save({ accessToken: "test-provider-access", refreshToken: "test-provider-refresh" });
  } else {
    providerTokenStore.clear();
  }
  initProviderSession();
}

/** 造一个业务错误：契约里 `message` 是给用户看的那句话，`request_id` 用于报障。 */
export function apiFailure(code: number, message: string, requestId = "req-test-1"): ApiError {
  return new ApiError(message, { code, requestId, httpStatus: 200 });
}

/** 造一个网络类失败（没有请求 ID）。 */
export function networkFailure(): ApiError {
  return new ApiError("网络连接失败，请检查网络后重试", { code: 0, network: true });
}

export interface MountedPage {
  wrapper: VueWrapper;
  router: Router;
}

/**
 * 挂一个页面：真路由表 + 真会话，并把首屏的异步加载跑完。
 *
 * `path` 用真路径（`/b/orders`），所以路由表里少了一条或名字改了，这里立刻红。
 */
export async function mountPage(
  component: Component,
  path: string,
  sessionKind: SessionKind = "authenticated",
): Promise<MountedPage> {
  seedSession(sessionKind);
  const router = createRouter({ history: createMemoryHistory(), routes: realRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(component, { global: { plugins: [router] } });
  await flushPromises();
  await flushPromises();
  return { wrapper, router };
}
