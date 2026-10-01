/**
 * 服务者后台登录页（F021）的组件测试。
 *
 * 这一页是「20 个后台页面在真实使用中到不了」那个缺口的出口，所以验的就是那几件事：
 *   - **登得进去**：令牌落进本域的 tokenStore（登录域的隔离靠它），并跳到今日概览；
 *   - **失败要说话**：40300 / 40100 的文案由服务端给（两句不同的话），前端原样展示；
 *   - **登不进去就别落盘**：失败时 tokenStore 必须还是空的——落一个坏令牌会让后续每个请求都 40100；
 *   - **顶栏有入口**：未登录时外壳的账号区给出「登录」，登录后给出「退出登录」。
 */
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { providerTokenStore } from "../api/client";
import { initProviderSession } from "../session";
import { routes as realRoutes } from "../router";
import LoginView from "../views/LoginView.vue";
import AppShell from "../layouts/AppShell.vue";
import { apiFailure, mountPage, seedSession } from "./support";

const login = vi.fn();
const logout = vi.fn();
const register = vi.fn();

vi.mock("../api/auth", () => ({
  providerAuth: {
    login: (...args: unknown[]) => login(...args),
    logout: (...args: unknown[]) => logout(...args),
    register: (...args: unknown[]) => register(...args),
  },
}));

const TOKENS = {
  access_token: "provider-access",
  refresh_token: "provider-refresh",
  expires_in: 7200,
  user: null,
};

describe("服务者后台 · 登录", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    register.mockReset();
    providerTokenStore.clear();
    initProviderSession();
  });

  it("登录成功后令牌落进 provider 域，并跳到今日概览", async () => {
    login.mockResolvedValue(TOKENS);
    const { wrapper, router } = await mountPage(LoginView, "/login", "anonymous");

    await wrapper.find('input[type="tel"]').setValue("13800139000");
    await wrapper.find('input[type="password"]').setValue("Passw0rd123");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(login).toHaveBeenCalledWith({ phone: "13800139000", password: "Passw0rd123" });
    expect(providerTokenStore.get()?.accessToken).toBe("provider-access");
    // 「跳到今日概览」——用例名与文件头都写了这条，就得真的验它。
    // 目标路由是懒加载的（dynamic import），导航要等模块加载完才落地，所以用 `vi.waitFor`
    // 重试而不是「等一拍」：`flushPromises()` 就是 `setTimeout(resolve, 0)`，
    // 机器一忙就不够，断言会落在导航之前（偶发红、单跑必过）。
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe("dashboard"));
  });

  it("40300（账号被禁用）时原样展示服务端文案，且不落任何令牌", async () => {
    login.mockRejectedValue(apiFailure(40300, "账号已被禁用，请联系客服"));
    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");

    await wrapper.find('input[type="tel"]').setValue("13800139001");
    await wrapper.find('input[type="password"]').setValue("Passw0rd123");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("账号已被禁用，请联系客服");
    expect(providerTokenStore.get()).toBeNull();
  });

  it("凭据不全时按钮禁用，也不发请求", async () => {
    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");

    const submit = wrapper.find('button[type="submit"]');
    expect(submit.attributes("disabled")).toBeDefined();

    // 只填手机号仍然不能提交（密码是必填）
    await wrapper.find('input[type="tel"]').setValue("13800139002");
    await flushPromises();
    expect(wrapper.find('button[type="submit"]').attributes("disabled")).toBeDefined();

    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(login).not.toHaveBeenCalled();
  });
});

describe("服务者后台 · 外壳的账号区", () => {
  /**
   * 只验**本端填进外壳 `account` 插槽的那段逻辑**，所以把共享的 `ConsoleShell` 换成一个
   * 只渲染插槽的壳。
   *
   * 为什么不用真外壳：外壳的 `RouterView` 会渲染当前路由的组件，而 `/b/**` 的组件**就是
   * 外侧这个 AppShell**——真挂起来会渲染两层外壳（第一版踩过，断言里出现两份导航与两个账号区），
   * 而且那两层分属不同的模块实例，会话状态互相看不见，断言会变得不可信。
   * 外壳自身怎么摆放插槽由 packages/ui 的测试负责（本包不重复测别人的组件）。
   */
  async function mountShell(kind: "authenticated" | "anonymous") {
    seedSession(kind);
    const router = createRouter({ history: createMemoryHistory(), routes: realRoutes });
    await router.push("/login");
    await router.isReady();
    return mount(AppShell, {
      global: {
        plugins: [router],
        stubs: {
          ConsoleShell: { template: '<div><slot name="account" /><slot /></div>' },
          RouterView: { template: "<div />" },
          RouterLink: { props: ["to"], template: '<a :href="to"><slot /></a>' },
        },
      },
    });
  }

  beforeEach(() => {
    vi.clearAllMocks();
    providerTokenStore.clear();
    initProviderSession();
  });

  it("未登录时顶栏给出「登录」入口，登录后变成「退出登录」", async () => {
    const anonymous = await mountShell("anonymous");
    expect(anonymous.text()).toContain("登录");
    expect(anonymous.text()).not.toContain("退出登录");

    const signedIn = await mountShell("authenticated");
    expect(signedIn.text()).toContain("退出登录");
  });
});

describe("服务者后台 · 注册", () => {
  it("注册即登录：拿到的 provider 域令牌落盘并跳今日概览（与登录同一手感）", async () => {
    register.mockResolvedValue(TOKENS);

    const { wrapper, router } = await mountPage(LoginView, "/login", "anonymous");
    await wrapper.findAll("button").find((b) => b.text().includes("去注册"))!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("注册一个账号就能提交入驻申请");
    await wrapper.find('input[type="tel"]').setValue("13800139501");
    await wrapper.find('input[type="password"]').setValue("pet12345");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    // 契约里注册返回的就是本域令牌对（provider.yaml 的 /auth/register），
    // 所以这里与登录完全同一条路径：落盘 + 跳预
    expect(register).toHaveBeenCalledWith({ phone: "13800139501", password: "pet12345", nickname: undefined });
    expect(providerTokenStore.get()?.accessToken).toBe("provider-access");
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe("dashboard"));
  });

  it("注册失败照常说话（40900 手机号已注册由服务端给文案），且不落令牌", async () => {
    register.mockRejectedValue(apiFailure(40900, "该手机号已注册"));

    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");
    await wrapper.findAll("button").find((b) => b.text().includes("去注册"))!.trigger("click");
    await flushPromises();

    await wrapper.find('input[type="tel"]').setValue("13800139502");
    await wrapper.find('input[type="password"]').setValue("pet12345");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("该手机号已注册");
    expect(providerTokenStore.get()).toBeNull();
  });
});
