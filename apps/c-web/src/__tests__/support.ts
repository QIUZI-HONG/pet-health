/**
 * 新增页面（订单 / 券 / 积分 / 权益 / 邀请）的测试公共件。
 *
 * 三件事只有一份，避免五个 spec 各写一遍、然后各错一处：
 *   - **会话怎么种**：这些页面都包在 `SessionGate` 里，测试要能直接造出「已登录 / 未登录 / 服务异常」
 *     三种会话态（走真实的 session store，不 mock 它——闸门的顺序正是要验的东西）；
 *   - **失败怎么造**：业务错误一律是 {@link ApiError}，测试要能指定码、文案与请求 ID；
 *   - **页面怎么挂**：**用真路由表**（与 shell.spec 同一个理由：复制一份路由，等于把
 *     「页面挂进了框架」这条验收标准变成一句空话），但只挂页面组件（不挂 AppShell，
 *     免得顶栏的消息轮询把测试拖进无关的依赖里）。
 */
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import type { Component } from "vue";
import { ApiError, type PetView, type UserProfile } from "@pet-health/shared";
import { routes as realRoutes } from "../router";
import { useSessionStore } from "../stores/session";

/** 一只测试用宠物：多宠家庭的下单页要按它选宠物。 */
export const TEST_PET: PetView = {
  id: 7,
  name: "豆豆",
  species: 1,
  gender: 1,
  birthday: "2022-05-01",
} as PetView;

export type SessionKind = "authenticated" | "anonymous" | "error";

/** 种出三种会话态之一；调用前必须先 `setActivePinia(createPinia())`。 */
export function seedSession(kind: SessionKind = "authenticated"): void {
  const session = useSessionStore();
  if (kind === "authenticated") {
    session.status = "authenticated";
    session.hasSession = true;
    session.user = { id: 1, nickname: "小明", active_pet_id: TEST_PET.id } as UserProfile;
    session.pets = [TEST_PET];
    return;
  }
  if (kind === "anonymous") {
    session.status = "anonymous";
    session.hasSession = false;
    session.user = null;
    session.pets = [];
    return;
  }
  // 服务异常：会话还在（有令牌）但后端不可用——闸门必须显示「重试」而不是骗用户去登录
  session.status = "error";
  session.hasSession = true;
  session.errorMessage = "服务暂时不可用，请稍后重试";
  session.errorRequestId = "req-session-1";
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
 * 挂一个页面：真路由表 + 真 session store，并把首屏的异步加载跑完。
 *
 * `path` 用真路径（`/orders/42`），所以路由表里少了一条或名字改了，这里立刻红。
 */
export async function mountPage(
  component: Component,
  path: string,
  sessionKind: SessionKind = "authenticated",
): Promise<MountedPage> {
  setActivePinia(createPinia());
  seedSession(sessionKind);
  const router = createRouter({ history: createMemoryHistory(), routes: realRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(component, { global: { plugins: [router] } });
  await flushPromises();
  await flushPromises();
  return { wrapper, router };
}
