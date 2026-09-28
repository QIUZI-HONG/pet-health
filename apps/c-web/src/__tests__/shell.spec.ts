/**
 * 框架与导航的行为测试（切片 #96 的验收标准）。
 *
 * 验收标准原话：「五个主页面（首页/服务/AI管家/档案/我的）在桌面布局下可导航」。
 * 这里把它拆成三条可执行的断言：
 *   1. 左栏永远有五个入口；
 *   2. 每条路由都能渲染出自己的页面标题（页面挂了或路由漏了都会红）；
 *   3. 点左栏能真的换页，且当前项会高亮。
 *
 * 接口一律 mock：这一层验的是框架与导航，不是后端（后端有 Testcontainers 那套，见 ADR-0014）。
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
// 真路由表：测试与实现共用一份，页面被删掉测试就红（见下方说明）
import { routes as realRoutes } from "../router";
import { createPinia, setActivePinia } from "pinia";

vi.mock("@pet-health/shared", () => ({
  cApp: {
    me: vi.fn(),
    listPets: vi.fn().mockResolvedValue([]),
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    activatePet: vi.fn(),
  },
  tokenStore: {
    get: vi.fn().mockReturnValue(null),
    accessToken: null,
    refreshToken: null,
    save: vi.fn(),
    clear: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
  http: {},
}));

async function mountShell(path: string): Promise<{ router: Router; wrapper: ReturnType<typeof mount> }> {
  setActivePinia(createPinia());
  // **必须用真路由表**：早先这里复制了一份 routes，结果从真路由里删掉一个主页面测试照样绿——
  // 验收标准「五个主页面可导航」就守不住了。这里换成把 router/index.ts 的 routes 直接拿来用。
  const router = createRouter({ history: createMemoryHistory(), routes: realRoutes });
  await router.push(path);
  await router.isReady();
  const wrapper = mount((await import("../App.vue")).default, { global: { plugins: [router] } });
  await router.isReady();
  return { router, wrapper };
}

const NAV_LABELS = ["首页", "服务", "AI 管家", "健康档案", "我的", "消息中心"];

describe("C 端框架与导航", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("左栏入口与路由表里的主页面一一对应（漏一个就红）", async () => {
    const { wrapper } = await mountShell("/");
    const nav = wrapper.get('nav[aria-label="主导航"]');
    const labels = nav.findAll(".ph-sidebar__label").map((node) => node.text());
    expect(labels).toEqual(NAV_LABELS);

    // 反向校验：真路由表里挂在框架下的子路由，必须都在左栏里
    const shell = realRoutes.find((route) => route.path === "/");
    // 次级页：从「我的」等入口进入，**故意不在左栏**。登记在这里的路径也必须真的存在，
    // 否则白名单会慢慢烂掉（删了页面还留着名字，谁也发现不了）。
    // 注意这里写的是**子路由的相对路径**（与路由表里的声明一致，不带前导斜杠）
    const SECONDARY_PATHS = ["legal/:code"];
    const children = shell?.children ?? [];
    for (const path of SECONDARY_PATHS) {
      expect(children.some((child) => child.path === path), `次级页 ${path} 不在路由表里了`).toBe(true);
    }
    const childTitles = children
      .filter((child) => !SECONDARY_PATHS.includes(child.path))
      .map((child) => (child.meta?.title as string) ?? "");
    expect(childTitles).toEqual(NAV_LABELS);
  });

  it.each([
    ["/", "首页"],
    ["/services", "服务"],
    ["/ai", "AI 管家"],
    ["/records", "健康档案"],
    ["/profile", "我的"],
  ])("路由 %s 能渲染出「%s」页", async (path, title) => {
    const { wrapper } = await mountShell(path);
    // 顶栏标题来自路由 meta，页面标题在内容区——两者都在，说明框架把页面挂上了
    expect(wrapper.get(".ph-topbar__title").text()).toBe(title);
    expect(wrapper.get(".ph-shell__content").text().length).toBeGreaterThan(0);
  });

  it("点左栏能换页，且当前项高亮", async () => {
    const { wrapper, router } = await mountShell("/");

    expect(wrapper.get(".ph-sidebar__item--active").text()).toContain("首页");

    const items = wrapper.findAll(".ph-sidebar__item");
    const services = items.find((item) => item.text().includes("服务"));
    expect(services).toBeTruthy();
    await services!.trigger("click");
    // RouterLink 的跳转是异步的，等它落地再断言
    await flushPromises();

    expect(router.currentRoute.value.name).toBe("services");
    expect(wrapper.get(".ph-sidebar__item--active").text()).toContain("服务");
    expect(wrapper.get(".ph-topbar__title").text()).toBe("服务");
  });

  it("未登录时数据页给「登录后查看」，而不是空白或报错", async () => {
    const { wrapper } = await mountShell("/records");
    expect(wrapper.text()).toContain("登录后查看");
    expect(wrapper.text()).toContain("去登录");
  });

  it("顶栏在未登录时显示登录入口，不显示退出", async () => {
    const { wrapper } = await mountShell("/");
    const topbar = wrapper.get(".ph-topbar");
    expect(topbar.text()).toContain("登录");
    expect(topbar.text()).not.toContain("退出");
  });
});
