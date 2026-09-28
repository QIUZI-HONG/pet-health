/**
 * 合规页与账号操作（切片 #74 / ADR-0025）的组件测试。
 *
 * 守的是三件在界面上看不出来、但出事就是合规问题的事：
 *  1. **占位正文必须标明**——把待法务定稿的文字当生效条款展示；
 *  2. **注销必须二次确认**——它不可撤销（匿名化手机号 + 停用宠物档案）；
 *  3. 导出是真的把 JSON 副本交给用户，而不是「点一下弹个提示」。
 *
 * 桩打在 axios 传输层（与 photo-upload / ai-consult 同一手法）。
 */
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ComplianceCard from "../components/ComplianceCard.vue";
import LegalView from "../views/LegalView.vue";

const transport = vi.hoisted(() => ({ request: vi.fn() }));
const clearTokens = vi.hoisted(() => vi.fn());
const push = vi.hoisted(() => vi.fn());

vi.mock("axios", () => {
  const instance = {
    request: transport.request,
    post: transport.request,
    interceptors: { request: { use: () => undefined } },
  };
  const axios = { create: () => instance };
  return { default: axios, ...axios };
});

vi.mock("vue-router", () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ params: { code: "privacy_policy" } }),
}));

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    tokenStore: { clear: clearTokens, get: () => null, accessToken: null, refreshToken: null },
  };
});

function ok<T>(data: T) {
  return { data: { code: 0, message: "success", data, request_id: "t" } };
}

function document(overrides: Record<string, unknown> = {}) {
  return {
    code: "privacy_policy",
    title: "隐私政策",
    body: "【占位文本 · 待法务定稿】本页将说明：收集哪些信息……",
    version: "v0-placeholder",
    effective_from: null,
    is_placeholder: true,
    ...overrides,
  };
}

async function mountCard() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const { useSessionStore } = await import("../stores/session");
  useSessionStore().$patch({
    status: "authenticated",
    hasSession: true,
    user: { id: 1, nickname: "测试主人", phone: "139****0001", active_pet_id: null },
  } as never);
  return mount(ComplianceCard, {
    global: {
      plugins: [pinia],
      // 不装真路由也能断言链接目标：桩一个会渲染 href 的 a
      stubs: { RouterLink: { props: ["to"], template: '<a :href="to"><slot /></a>' } },
    },
  });
}

describe("合规文档页", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    transport.request.mockResolvedValue(ok(document()));
  });

  it("占位正文要明确标注，不能当生效条款展示", async () => {
    const wrapper = mount(LegalView, { global: { stubs: { SessionGate: { template: "<slot />" } } } });
    await flushPromises();

    const text = wrapper.text();
    expect(transport.request.mock.calls[0][0].url).toBe("/api/v1/app/compliance/documents/privacy_policy");
    expect(text).toContain("待法务定稿");
    expect(text).toContain("不作为生效条款");
  });

  it("已定稿时显示版本与生效日期，不再显示占位提示", async () => {
    transport.request.mockResolvedValue(ok(document({
      is_placeholder: false, version: "v1", effective_from: "2026-10-01",
      body: "我们收集以下信息……（已定稿正文）",
    })));

    const wrapper = mount(LegalView, { global: { stubs: { SessionGate: { template: "<slot />" } } } });
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain("v1");
    expect(text).toContain("2026-10-01");
    expect(text).not.toContain("待法务定稿");
  });
});

describe("账号与条款卡片", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    transport.request.mockResolvedValue(ok({
      user: { id: 1, nickname: "测试主人" }, pets: [], messages: [],
      exported_at: "2026-09-28 22:30:00", notice: "这是你的账号数据副本。",
    }));
  });

  it("三份文档都有入口", async () => {
    const wrapper = await mountCard();
    const hrefs = wrapper.findAll("a").map((link) => link.attributes("href") ?? "");
    expect(hrefs).toContain("/legal/privacy_policy");
    expect(hrefs).toContain("/legal/user_agreement");
    expect(hrefs).toContain("/legal/ai_disclaimer");
  });

  it("导出会下载一份 JSON 副本", async () => {
    const wrapper = await mountCard();
    const createUrl = vi.fn(() => "blob:test");
    const revokeUrl = vi.fn();
    vi.stubGlobal("URL", { createObjectURL: createUrl, revokeObjectURL: revokeUrl });

    await wrapper.findAll("button").find((b) => b.text().includes("导出"))!.trigger("click");
    await flushPromises();

    expect(transport.request.mock.calls[0][0].url).toBe("/api/v1/app/users/me/export");
    expect(createUrl).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("已开始下载");
    vi.unstubAllGlobals();
  });

  it("注销要二次确认：点一次只是展开确认区，再确认才调接口", async () => {
    const wrapper = await mountCard();

    await wrapper.findAll("button").find((b) => b.text() === "注销账号")!.trigger("click");
    await flushPromises();

    expect(transport.request).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("此操作不可撤销");

    await wrapper.findAll("button").find((b) => b.text() === "确认注销")!.trigger("click");
    await flushPromises();

    expect(transport.request.mock.calls[0][0].url).toBe("/api/v1/app/users/me/deactivation");
    expect(clearTokens).toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith({ name: "login" });
  });

  it("确认前可以取消", async () => {
    const wrapper = await mountCard();
    await wrapper.findAll("button").find((b) => b.text() === "注销账号")!.trigger("click");
    await wrapper.findAll("button").find((b) => b.text() === "取消")!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).not.toContain("此操作不可撤销");
    expect(transport.request).not.toHaveBeenCalled();
  });
});
