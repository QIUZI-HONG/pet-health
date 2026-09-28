/**
 * 会话失败的分类：**身份失效** vs **服务不可用**（这是踩出来的用例）。
 *
 * 真实场景：后端发版重启 30 秒，在线用户刷新页面。修好之前的行为是——
 * 令牌被清、显示「登录后查看」，用户以为自己的账号出了问题。
 * 正确行为是保留会话、显示错误态与重试。
 *
 * 这条测试把它钉住：改坏了 CI 就红，不靠人记得。
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { createPinia, setActivePinia, type Pinia } from "pinia";
import { mount } from "@vue/test-utils";
import { ApiError, tokenStore } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import AppTopbar from "../components/AppTopbar.vue";
import LoginView from "../views/LoginView.vue";
import { createRouter, createMemoryHistory } from "vue-router";

const me = vi.fn();
const listPets = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      me: (...args: unknown[]) => me(...args),
      listPets: (...args: unknown[]) => listPets(...args),
      login: vi.fn(),
      register: vi.fn(),
      logout: vi.fn(),
      activatePet: vi.fn(),
      getUnreadCount: vi.fn().mockResolvedValue({ unread: 0, unread_reminders: 0 }),
      createPet: vi.fn(),
      updatePet: vi.fn(),
      deletePet: vi.fn(),
      restorePet: vi.fn(),
    },
    tokenStore: {
      get: vi.fn(() => ({ accessToken: "a", refreshToken: "r" })),
      accessToken: "a",
      refreshToken: "r",
      save: vi.fn(),
      clear: vi.fn(),
    },
  };
});

describe("会话失败的分类", () => {
  let pinia: Pinia;

  beforeEach(() => {
    // 组件与 store 必须挂同一个 pinia 实例：各挂各的，组件看到的是另一个全新空 store
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  it("服务不可用（5xx）：保住会话，页面给错误态与重试，且提示里带请求 ID", async () => {
    me.mockRejectedValueOnce(new ApiError("服务异常（HTTP 500）", { code: -1, requestId: "trace-500" }));
    listPets.mockRejectedValueOnce(new ApiError("服务异常（HTTP 500）", { code: -1, requestId: "trace-500" }));

    const session = useSessionStore();
    await session.reload();

    expect(session.status).toBe("error");
    expect(session.hasSession).toBe(true);
    expect(session.errorRequestId).toBe("trace-500");
    // 关键：**不许**因为后端抖了一下就把用户登出
    expect(tokenStore.clear).not.toHaveBeenCalled();
  });

  it("身份失效（40101）：清掉本地会话，回到未登录", async () => {
    me.mockRejectedValueOnce(new ApiError("Refresh Token 已失效，请重新登录", { code: 40101 }));
    listPets.mockRejectedValueOnce(new ApiError("Refresh Token 已失效，请重新登录", { code: 40101 }));

    const session = useSessionStore();
    await session.reload();

    expect(session.status).toBe("anonymous");
    expect(session.hasSession).toBe(false);
    expect(tokenStore.clear).toHaveBeenCalled();
  });

  it("闸门组件：错误态显示「重新加载」，而不是「去登录」", async () => {
    me.mockRejectedValueOnce(new ApiError("网络连接失败，请检查网络后重试", { code: 0, network: true }));
    listPets.mockRejectedValueOnce(new ApiError("网络连接失败，请检查网络后重试", { code: 0, network: true }));

    const session = useSessionStore();
    await session.reload();

    const wrapper = mount(SessionGate, { global: { plugins: [pinia] } });
    expect(wrapper.text()).toContain("网络连接失败");
    expect(wrapper.text()).toContain("重新加载");
    expect(wrapper.text()).not.toContain("去登录");
  });

  it("错误态下顶栏显示账号与退出，而不是「登录」（避免与内容区自相矛盾）", async () => {
    me.mockRejectedValueOnce(new ApiError("服务异常（HTTP 500）", { code: -1, requestId: "trace-x" }));
    listPets.mockRejectedValueOnce(new ApiError("服务异常（HTTP 500）", { code: -1, requestId: "trace-x" }));

    const session = useSessionStore();
    await session.reload();

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", name: "home", component: { template: "<div/>" } },
        // 顶栏的铃铛指向消息中心，路由表里没有它会渲染失败
        { path: "/messages", name: "messages", component: { template: "<div/>" } },
      ],
    });
    await router.push("/");
    await router.isReady();

    const wrapper = mount(AppTopbar, { global: { plugins: [pinia, router] } });
    expect(wrapper.text()).toContain("退出");
    // 注意别用 `not.toContain("登录")`：顶栏文案是「已登录」，里面也含这两个字。
    // 要验的是「没有登录入口」，所以按元素找。
    const loginEntries = wrapper
      .findAll("a, button")
      .filter((node) => node.text().trim() === "登录");
    expect(loginEntries).toHaveLength(0);
  });

  it("登录页：手机号格式不对且失焦后，明确指出哪里不对（而不是只把按钮变灰）", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/login", name: "login", component: { template: "<div/>" } }],
    });
    await router.push("/login");
    await router.isReady();

    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });
    const phone = wrapper.get('input[autocomplete="tel"]');

    // 还没失焦时不提示（别一打开页面就红一片）
    await phone.setValue("12345");
    expect(wrapper.text()).not.toContain("手机号格式不对");

    await phone.trigger("blur");
    expect(wrapper.text()).toContain("手机号格式不对");
    expect(wrapper.get('button[type="submit"]').attributes("disabled")).toBeDefined();
  });

  it("闸门组件：真的未登录时才显示「去登录」", async () => {
    const session = useSessionStore();
    session.$patch({ status: "anonymous", hasSession: false });

    const wrapper = mount(SessionGate, {
      global: { plugins: [pinia] },
      props: { forbiddenDescription: "登录后管理你的账号与宠物。" },
    });
    expect(wrapper.text()).toContain("登录后查看");
    expect(wrapper.text()).toContain("去登录");
  });
});
