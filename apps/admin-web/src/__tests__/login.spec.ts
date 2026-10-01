/**
 * 运营后台登录页（F021）的组件测试。
 *
 * 这一页是「9 个运营页面在真实使用中到不了」那个缺口的出口。与服务者后台那份的差别只在
 * **准入是名单制**，所以这里专门钉住那条：
 *   - **登不进去要说话**：40300 的文案（「该账号不是运营后台账号」）由服务端给，前端原样展示；
 *   - **失败不落盘**：落一个坏令牌会让后续每个请求都 40100，比停在登录页糟得多；
 *   - **顶栏有入口**：未登录时外壳账号区给出「登录」，登录后给出「退出登录」。
 */
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { adminTokenStore } from "../api/client";
import { initAdminSession } from "../session";
import { seedSession } from "./support";
import { routes as realRoutes } from "../router";
import LoginView from "../views/LoginView.vue";
import AppShell from "../layouts/AppShell.vue";
import { apiFailure, mountPage } from "./support";

const login = vi.fn();
const logout = vi.fn();

const register = vi.fn();

vi.mock("../api/auth", () => ({
  adminAuth: {
    login: (...args: unknown[]) => login(...args),
    logout: (...args: unknown[]) => logout(...args),
    register: (...args: unknown[]) => register(...args),
  },
}));

const TOKENS = {
  access_token: "admin-access",
  refresh_token: "admin-refresh",
  expires_in: 7200,
  user: null,
};

describe("运营后台 · 登录", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    register.mockReset();
    adminTokenStore.clear();
    initAdminSession();
  });

  it("登录成功后令牌落进 admin 域，并跳到服务者审核页", async () => {
    login.mockResolvedValue(TOKENS);
    const { wrapper, router } = await mountPage(LoginView, "/login", "anonymous");

    await wrapper.find('input[type="tel"]').setValue("13800139400");
    await wrapper.find('input[type="password"]').setValue("Passw0rd123");
    await wrapper.find("form").trigger("submit");
    expect(login).toHaveBeenCalledWith({ phone: "13800139400", password: "Passw0rd123" });
    expect(adminTokenStore.get()?.accessToken).toBe("admin-access");
    // 目标路由是懒加载的（dynamic import），导航要几轮微/宏任务才落地——
    // 用轮询而不是固定 sleep：固定时长在慢机器上是随机红
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe("providers"));
  });

  it("不在运营名单里（40300）时展示服务端文案，且不落任何令牌", async () => {
    login.mockRejectedValue(apiFailure(40300, "该账号不是运营后台账号"));
    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");

    await wrapper.find('input[type="tel"]').setValue("13800139401");
    await wrapper.find('input[type="password"]').setValue("Passw0rd123");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("该账号不是运营后台账号");
    expect(adminTokenStore.get()).toBeNull();
  });

  it("凭据不全时按钮禁用，也不发请求", async () => {
    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");

    expect(wrapper.find('button[type="submit"]').attributes("disabled")).toBeDefined();
    await wrapper.find('input[type="tel"]').setValue("13800139402");
    await flushPromises();
    expect(wrapper.find('button[type="submit"]').attributes("disabled")).toBeDefined();

    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(login).not.toHaveBeenCalled();
  });
});

describe("运营后台 · 外壳的账号区", () => {
  /**
   * 只验本端填进外壳 `account` 插槽的那段逻辑，所以把共享 `ConsoleShell` 换成一个只渲染插槽的壳
   * ——真外壳的 `RouterView` 会渲染当前路由组件，而 `/admin/**` 的组件就是外侧这个 AppShell，
   * 挂起来会渲染两层外壳（服务者后台那边的测试踩过，见它的注释）。
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
    adminTokenStore.clear();
    initAdminSession();
  });

  it("未登录时顶栏给出「登录」入口，登录后变成「退出登录」", async () => {
    const anonymous = await mountShell("anonymous");
    expect(anonymous.text()).toContain("登录");
    expect(anonymous.text()).not.toContain("退出登录");

    const signedIn = await mountShell("authenticated");
    expect(signedIn.text()).toContain("退出登录");
  });
});

describe("运营后台 · 注册（权限后置）", () => {
  it("注册**不发令牌**：只展示服务端那句「还差加入名单」，并切回登录态", async () => {
    register.mockResolvedValue({
      user: { id: 9, phone: "138****0000", nickname: "张运营", gender: 0, avatar: null },
      notice: "账号已创建。运营后台是名单制：需要平台管理员把该账号加入名单（CONSOLE_ADMIN_USER_IDS）之后才能登录。",
    });

    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");
    // 切到注册态（文字链，与另两端同一个手感）
    await wrapper.findAll("button").find((b) => b.text().includes("去注册"))!.trigger("click");
    await flushPromises();

    await wrapper.get('input[type="tel"]').setValue("13800000000");
    await wrapper.get('input[type="password"]').setValue("pet12345");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(register).toHaveBeenCalledWith({ phone: "13800000000", password: "pet12345", nickname: undefined });
    expect(wrapper.text()).toContain("名单");
    // **不落盘、也不跳转**：注册不发令牌，给了令牌反而误导（用户以为已经能进后台）
    expect(adminTokenStore.get()).toBeNull();
    // 注册成功后切回登录态：按钮又变回「登录」——用户下一步就是去登录（等名单加完）
    expect(wrapper.find('button[type="submit"]').text()).toContain("登录");
  });

  it("注册失败照常说话（40900 手机号已注册由服务端给文案）", async () => {
    register.mockRejectedValue(apiFailure(40900, "该手机号已注册", "req-409"));

    const { wrapper } = await mountPage(LoginView, "/login", "anonymous");
    await wrapper.findAll("button").find((b) => b.text().includes("去注册"))!.trigger("click");
    await flushPromises();

    await wrapper.get('input[type="tel"]').setValue("13800000000");
    await wrapper.get('input[type="password"]').setValue("pet12345");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("该手机号已注册");
  });
});
