/**
 * 运营后台测试的公共件（与 `apps/c-web/src/__tests__/support.ts` 同源，两处差别只有会话怎么种）。
 *
 * 三件事只有一份，避免七个 spec 各写一遍、然后各错一处：
 *   - **会话怎么种**：每个页面都包在 `ConsoleGate` 里，测试要能直接造出「已登录 / 未登录」两种态
 *     （走**真实的** session 模块，不 mock 它——闸门的顺序正是要验的东西）；
 *   - **失败怎么造**：业务错误一律是 {@link ApiError}，测试要能指定码、文案与请求 ID；
 *   - **页面怎么挂**：**用真路由表**（复制一份路由，等于把「页面挂进了框架」这条验收标准变成一句空话），
 *     但只挂页面组件（不挂 AppShell，免得外壳的导航与标题逻辑混进页面测试）。
 *
 * 后台的会话态只有两种，因为 `session.ts` 就是「本地有没有本域令牌」这一件事：没有「服务异常」
 * 那第三种态（那是 C 端会话 store 的东西——它要拉用户与宠物，后台不拉）。未登录时页面必须
 * **一个请求都不发**，所以 `anonymous` 这一档也是必需的验点。
 */
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import type { Component } from "vue";
import { ApiError } from "@pet-health/shared";
import { routes as realRoutes } from "../router";
import { adminTokenStore } from "../api/client";
import { initAdminSession, useAdminSession } from "../session";

export type SessionKind = "authenticated" | "anonymous";

/**
 * 种出会话态：令牌是唯一的事实来源，所以先写令牌再让 session 初始化（它就是这么读的）。
 *
 * 必须**每次都调**：令牌与 session 的 status 都是模块级单例，不重置会让上一个用例的登录态
 * 漏到下一个（表现是「未登录」那一条用例意外地有令牌、于是断言全绿——最坏的一种假通过）。
 */
export function seedSession(kind: SessionKind = "authenticated"): void {
  if (kind === "authenticated") {
    adminTokenStore.save({ accessToken: "test-admin-access", refreshToken: "test-admin-refresh" });
  } else {
    adminTokenStore.clear();
  }
  initAdminSession();
}

/**
 * 造一个业务错误：契约里 `message` 是给用户看的那句话，`request_id` 用于报障。
 */
export function apiFailure(code: number, message: string, requestId = "req-test-1"): ApiError {
  return new ApiError(message, { code, requestId, httpStatus: 200 });
}

/**
 * 把「当前身份是超级管理员」这一位置真（或复原）。
 *
 * 为什么要这个开关：ADR-0037 的矩阵把考核规则配置划给超管，而 `session.isSuperAdmin`
 * **当前恒为 false**（令牌里只有登录域、没有角色，见 session.ts）。它返回的是一个 ref，
 * 测试直接写它，是**当前唯一**能验「超管才看得到写入口」这条行为的接缝——不用 mock 掉 session
 * 模块（那样连「角色未落地时页面是只读的」这条也一起验不到了）。
 *
 * 用完记得复位（见各 spec 的 beforeEach）：ref 是模块级单例。
 */
export function setSuperAdmin(value: boolean): void {
  useAdminSession().isSuperAdmin.value = value;
}

export interface MountedPage {
  wrapper: VueWrapper;
  router: Router;
}

/**
 * 按**可见文案**找按钮。
 *
 * 为什么不用 data-test 属性：后台的按钮就是用户能读到的那个词（「新建券模板」「关停窗口」），
 * 用文案找等于顺手把「这个按钮叫什么」也验了一遍——改文案时测试会红，而那是应当被看见的改动。
 * 找不到就抛错而不是返回 undefined：`.trigger()` 打在一个 undefined 上会静默通过。
 */
export function buttonByText(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll("button").find((item) => item.text().trim() === text);
  if (!found) {
    throw new Error(`找不到文案为「${text}」的按钮；当前按钮：${wrapper.findAll("button").map((item) => item.text().trim()).join(" / ")}`);
  }
  return found;
}

/**
 * 挂一个页面：真路由表 + 真 session 模块，并把首屏的异步加载跑完。
 *
 * `path` 用真路径（`/admin/coupon-pool`），所以路由表里少了一条或名字改了，这里立刻红。
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
