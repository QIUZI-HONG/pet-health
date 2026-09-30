/**
 * 注册流程里的那一次归因调用（切片 #111；决策见 ADR-0039 第一节 / ADR-0046）。
 *
 * 契约把归因定义成「注册成功后的那一次」：**只有一次机会、不做事后补填**。所以这里验四件事：
 *   - 注册成功 → 立刻调一次 `/invites/attribution`，带上用户填的码与渠道标签；
 *   - 链接预填来的码渠道是 1（分享链接），用户自己敲的是 2（注册表单手工填）；
 *   - **归因失败不阻塞**：注册已经成功，用户照常进首页（不能因为邀请码可疑就不让注册）；
 *   - 登录（不是注册）不发归因。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import LoginView from "../views/LoginView.vue";

const register = vi.fn();
const me = vi.fn();
const listPets = vi.fn();
const attributeInvite = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      register: (...args: unknown[]) => register(...args),
      me: (...args: unknown[]) => me(...args),
      listPets: (...args: unknown[]) => listPets(...args),
      login: vi.fn(),
      logout: vi.fn().mockResolvedValue(undefined),
      activatePet: vi.fn(),
    },
    tokenStore: {
      get: vi.fn(() => ({ accessToken: "at-1", refreshToken: "rt-1" })),
      accessToken: "at-1",
      refreshToken: "rt-1",
      save: vi.fn(),
      clear: vi.fn(),
    },
  };
});

vi.mock("../api/commerce", () => ({
  commerce: {
    attributeInvite: (...args: unknown[]) => attributeInvite(...args),
  },
}));

/** 注册成功的返回：一对令牌 + 用户（契约的 TokenPair）。 */
const TOKENS = {
  access_token: "at-1",
  refresh_token: "rt-1",
  user: { id: 1, nickname: "小明", active_pet_id: 7 },
};

async function mountLogin(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  setActivePinia(createPinia());
  // 这里用一张最小路由表而不是真表：本文件验的是**归因调用与跳转目标**，
  // 真表会把首页那一串懒加载页面拖进来（它们的动态 import 在测试收尾时会和 teardown 抢跑）。
  // 真表由 shell.spec 守（点导航能真的换页），这里只需要 `home` 这个名字存在。
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", name: "login", component: LoginView },
      { path: "/", name: "home", component: { template: "<div />" } },
      // 注册表单底部有《用户协议》《隐私政策》两个入口，补上免得路由告警
      { path: "/legal/:code", name: "legal", component: { template: "<div />" } },
    ],
  });
  await router.push("/login");
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [router] } });
  // 切到注册表单（登录表单上没有邀请码这一栏）
  await wrapper.findAll("button").find((button) => button.text() === "没有账号？去注册")?.trigger("click");
  return { wrapper, router };
}

/** 填完一份合法表单并提交。 */
async function registerWith(wrapper: ReturnType<typeof mount>, inviteCode: string): Promise<void> {
  await wrapper.get('input[autocomplete="tel"]').setValue("13800138000");
  await wrapper.get('input[autocomplete="new-password"]').setValue("abcd1234");
  if (inviteCode) {
    await wrapper.get('input[placeholder="好友的邀请码"]').setValue(inviteCode);
  }
  await wrapper.get('button[type="submit"]').trigger("submit");
  await flushPromises();
  await flushPromises();
}

beforeEach(() => {
  register.mockReset();
  me.mockReset();
  listPets.mockReset();
  attributeInvite.mockReset();
  window.localStorage.clear();

  register.mockResolvedValue(TOKENS);
  me.mockResolvedValue({ id: 1, nickname: "小明", active_pet_id: 7 });
  listPets.mockResolvedValue([{ id: 7, name: "豆豆" }]);
  attributeInvite.mockResolvedValue({ attributed: true, reason: null, relation_status: 1 });
});

describe("注册后的归因调用", () => {
  it("手工填的码：注册成功后立即提交，渠道是「注册表单手工填（2）」", async () => {
    const { wrapper, router } = await mountLogin();

    await registerWith(wrapper, "ABC123");

    const [body] = attributeInvite.mock.calls[0] as [Record<string, unknown>];
    expect(body.invite_code).toBe("ABC123");
    expect(body.channel).toBe(2);
    expect(router.currentRoute.value.name).toBe("home");
  });

  it("落地页记住的码会预填，渠道是「分享链接（1）」", async () => {
    window.localStorage.setItem("ph.c.invite", JSON.stringify({ code: "LINK456", at: Date.now() }));
    const { wrapper } = await mountLogin();

    expect((wrapper.get('input[placeholder="好友的邀请码"]').element as HTMLInputElement).value).toBe("LINK456");

    await registerWith(wrapper, "");
    const [body] = attributeInvite.mock.calls[0] as [Record<string, unknown>];
    expect(body.invite_code).toBe("LINK456");
    expect(body.channel).toBe(1);
  });

  it("归因失败不阻塞：注册已经成功，用户照常进首页", async () => {
    attributeInvite.mockRejectedValue(new Error("网络断了"));
    const { wrapper, router } = await mountLogin();

    await registerWith(wrapper, "ABC123");

    expect(attributeInvite).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.name).toBe("home");
  });

  it("没填码就不发归因请求（少一次必然失败的往返）", async () => {
    const { wrapper } = await mountLogin();

    await registerWith(wrapper, "");

    expect(attributeInvite).not.toHaveBeenCalled();
  });

  it("登录（不是注册）不发归因：归因的时点就是注册那一刻", async () => {
    const { wrapper } = await mountLogin();
    // 切回登录表单
    await wrapper.findAll("button").find((button) => button.text() === "已有账号？去登录")?.trigger("click");
    await wrapper.get('input[autocomplete="tel"]').setValue("13800138000");
    await wrapper.get('input[autocomplete="current-password"]').setValue("abcd1234");
    await wrapper.get('button[type="submit"]').trigger("submit");
    await flushPromises();

    expect(attributeInvite).not.toHaveBeenCalled();
  });
});
