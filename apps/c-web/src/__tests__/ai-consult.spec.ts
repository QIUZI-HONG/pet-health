/**
 * AI 管家页（切片 #98）的组件测试。
 *
 * 桩打在 axios 传输层（与 photo-upload.spec.ts 同一手法）：要验的是「请求发对了没」与
 * 「三条行为在界面上看得见没」——红线结论未经模型、降级答复要标明、到量不拦人。
 */
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AiConsultView from "../views/AiConsultView.vue";

const transport = vi.hoisted(() => ({
  request: vi.fn(),
  post: vi.fn(),
  /** 直传图片走 axios.put（http.putRaw）——上传用例要断言它被调到了正确的凭证地址。 */
  put: vi.fn(),
}));

vi.mock("axios", () => {
  const instance = {
    request: transport.request,
    post: transport.post,
    interceptors: { request: { use: () => undefined } },
  };
  const axios = { create: () => instance, put: transport.put };
  return { default: axios, ...axios };
});

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

function ok<T>(data: T) {
  return { data: { code: 0, message: "success", data, request_id: "test" } };
}

/** 一次咨询结果的默认形状，各用例只改关心的字段。 */
function consult(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    risk_level: 2,
    possible_causes: ["饮食不当", "急性肠胃炎"],
    action_suggestion: "建议尽快就医",
    need_hospital: true,
    care_tips: ["禁食 4 小时"],
    red_flag_hits: [],
    degraded: false,
    degrade_reason: null,
    model_version: "deepseek-flash",
    prompt_version: "p0-code",
    latency_ms: 4177,
    disclaimer: "以上依据宠物的健康档案与 AI 判断，只表示就医紧迫程度，不能替代兽医诊断。",
    created_at: "2026-09-28 21:30:00",
    quota_per_day: 3,
    remaining_today: 2,
    ...overrides,
  };
}

/** 登录态 + 一只宠物：页面依赖会话 store 拿当前宠物（登录态由 status 决定）。 */
async function mountLoggedIn() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const { useSessionStore } = await import("../stores/session");
  const session = useSessionStore();
  session.$patch({
    status: "authenticated",
    hasSession: true,   // 闸门看的是它，不是 status（见 SessionGate 的四态判断）
    user: {
      id: 1,
      phone: "139****0007",
      nickname: "测试主人",
      avatar: null,
      gender: 0,
      active_pet_id: 7,
      created_at: "2026-09-01 10:00:00",
    },
    pets: [
      {
        id: 7,
        name: "豆豆",
        species: 1,
        breed: "柯基",
        gender: 1,
        birthday: "2022-05-01",
        weight: "8.20",
        is_chronic: false,
      },
    ],
  } as never);
  return mount(AiConsultView, { global: { plugins: [pinia] } });
}

describe("AiConsultView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setActivePinia(createPinia());
    transport.request.mockResolvedValue(ok(consult()));
  });

  it("发送提问：请求打到该宠物下的咨询接口，并渲染三段结构化内容", async () => {
    const wrapper = await mountLoggedIn();

    await wrapper.find("textarea").setValue("今天吐了两次，精神不太好");
    await wrapper.find("button").trigger("click");
    await flushPromises();

    const call = transport.request.mock.calls[0][0];
    expect(call.url).toBe("/api/v1/app/pets/7/ai-consults");
    expect(call.data.question).toBe("今天吐了两次，精神不太好");

    const text = wrapper.text();
    expect(text).toContain("建议尽快就医");
    expect(text).toContain("饮食不当");
    expect(text).toContain("禁食 4 小时");
    expect(text).toContain("不能替代兽医诊断");
    // 右侧栏显示模型与提示词版本，便于事后归因
    expect(text).toContain("deepseek-flash");
  });

  it("需求为空时按钮禁用，不发请求", async () => {
    const wrapper = await mountLoggedIn();

    expect(wrapper.find("button").attributes("disabled")).toBeDefined();
    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(transport.request).not.toHaveBeenCalled();
  });

  it("红线命中：标明结论由规则给出、未经模型", async () => {
    transport.request.mockResolvedValue(ok(consult({
      risk_level: 3,
      red_flag_hits: ["RF-007"],
      model_version: "",
      latency_ms: 0,
    })));

    const wrapper = await mountLoggedIn();
    await wrapper.find("textarea").setValue("呕吐不止，喝水都吐");
    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("立即就医");
    expect(wrapper.text()).toContain("命中急症信号（RF-007）");
    expect(wrapper.text()).toContain("没有经过模型");
  });

  it("降级答复要标明不是模型结论", async () => {
    transport.request.mockResolvedValue(ok(consult({
      degraded: true,
      degrade_reason: "java_client: 调用 AI 服务失败：ResourceAccessException",
    })));

    const wrapper = await mountLoggedIn();
    await wrapper.find("textarea").setValue("今天精神不太好");
    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("降级答复");
  });

  it("额度到量不拦人：照常出结果，只提示可以邀请好友", async () => {
    transport.request.mockResolvedValue(ok(consult({ remaining_today: 0 })));

    const wrapper = await mountLoggedIn();
    await wrapper.find("textarea").setValue("再问一次");
    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("还剩 0 次");
    expect(wrapper.text()).toContain("邀请好友");
    // 结论仍然展示——这正是 ADR-0024 与「拦截」的区别
    expect(wrapper.text()).toContain("建议尽快就医");
  });

  it("后端报错时显示文案，不留空", async () => {
    transport.request.mockResolvedValue({
      data: { code: 40001, message: "question 请描述一下症状", data: null, request_id: "t" },
    });

    const wrapper = await mountLoggedIn();
    await wrapper.find("textarea").setValue("吐了");
    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("请描述一下症状");
  });
});

/**
 * 本轮补的三件事（第十一节之后的增量）：**图片一起发**、**引用要展示**、
 * **转人工**（口径：登记工单给平台人工，不收费——钱在门店付，ADR-0036）。
 */
/** 造一个图片文件（与 photo-upload.spec.ts 同一手法：只需 mime 与 size 真实）。 */
function image(name: string, type = "image/jpeg", size = 1024): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe("AI 管家页：图片 / 引用 / 转人工", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    transport.put.mockResolvedValue({ status: 204, data: "" });
  });

  it("图片一起发：先申请凭证再直传，咨询请求带上 file_ids", async () => {
    transport.request.mockImplementation((config: { url: string, method?: string }) => {
      if (config.url === "/api/v1/app/files/presign") {
        return Promise.resolve(ok([{ file_id: 31, upload_url: "/api/v1/open/files/31/content?token=x", role: "original" }]));
      }
      if (config.url === "/api/v1/app/files") {
        return Promise.resolve(ok([
          { id: 31, biz_type: "ai_consult", role: "original", mime: "image/jpeg", size_bytes: 1024, url: "/u31", thumb_url: "/t31" },
        ]));
      }
      return Promise.resolve(ok(consult()));
    });

    const wrapper = await mountLoggedIn();
    const input = wrapper.find('input[type="file"]');
    Object.defineProperty(input.element, "files", { value: [image("skin.jpg")], configurable: true });
    await input.trigger("change");
    await flushPromises();

    // 凭证申请用的是 ai_consult 这个 biz_type（契约的枚举值之一）
    const presign = transport.request.mock.calls.find((call) => call[0]?.url === "/api/v1/app/files/presign");
    expect(presign?.[0].data.biz_type).toBe("ai_consult");
    expect(transport.put).toHaveBeenCalledTimes(1);
    // 上传成功后页面上能看到这张图（可以在发送前移除）
    expect(wrapper.find(".ph-ai__thumb").exists()).toBe(true);

    await wrapper.get("textarea").setValue("皮肤上有一块掉毛");
    await wrapper.findAll("button").find((node) => node.text().includes("发送"))!.trigger("click");
    await flushPromises();

    // http.post 走 axios 的 request 口（{url, method, data}），与既有用例同一层断言
    const consultCall = transport.request.mock.calls.find(
      (call) => String(call[0]?.url).includes("/ai-consults") && call[0]?.method === "POST",
    );
    expect(consultCall?.[0].data.file_ids).toEqual([31]);
  });

  it("引用要展示，并说明其中有未复核条目（服务端只说「有」，不说是哪几条）", async () => {
    transport.request.mockResolvedValue(ok(consult({
      citations: [{ entry_id: "K-0011", title: "犬猫腹泻的家庭观察要点" }],
      unvetted_used: true,
    })));

    const wrapper = await mountLoggedIn();
    await wrapper.get("textarea").setValue("拉稀两天了");
    await wrapper.findAll("button").find((node) => node.text().includes("发送"))!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("参考的知识条目");
    expect(wrapper.text()).toContain("犬猫腹泻的家庭观察要点");
    expect(wrapper.text()).toContain("K-0011");
    expect(wrapper.text()).toContain("尚未经兽医复核");
  });

  it("转人工：点一次调接口，受理后按钮变「已受理」且不可再点（幂等不在前端重造）", async () => {
    transport.request.mockImplementation((config: { url: string }) => {
      if (String(config.url).includes("/transfer")) {
        return Promise.resolve(ok({ id: 9, consult_id: 1, risk_level: 2, status: 0, created_at: "2026-09-30 14:00:00" }));
      }
      return Promise.resolve(ok(consult()));
    });

    const wrapper = await mountLoggedIn();
    await wrapper.get("textarea").setValue("吐了两次");
    await wrapper.findAll("button").find((node) => node.text().includes("发送"))!.trigger("click");
    await flushPromises();

    const transfer = wrapper.findAll("button").find((node) => node.text().includes("转人工"))!;
    await transfer.trigger("click");
    await flushPromises();

    const call = transport.request.mock.calls.find((item) => String(item[0]?.url).includes("/transfer"));
    expect(call?.[0].url).toBe("/api/v1/app/pets/7/ai-consults/1/transfer");
    expect(wrapper.text()).toContain("已受理");
    expect(wrapper.findAll("button").find((node) => node.text().includes("已受理"))!.attributes("disabled")).toBeDefined();
    // 不收费：按钮与说明里都不出现价格（钱在门店付，ADR-0036）
    expect(wrapper.text()).not.toContain("39");
  });

  it("建议尽快就医时给出就医提示（need_hospital 不只是个字段）", async () => {
    transport.request.mockResolvedValue(ok(consult({ need_hospital: true })));

    const wrapper = await mountLoggedIn();
    await wrapper.get("textarea").setValue("一直吐");
    await wrapper.findAll("button").find((node) => node.text().includes("发送"))!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("建议尽快就医");
  });
});
