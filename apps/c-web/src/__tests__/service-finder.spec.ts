/**
 * AI 找服务（F011 规则版）的组件测试。
 *
 * 这一页最容易错的地方是「把三种没有推荐的情况揉成一句」——契约把它们分成了不同的字段，
 * 界面就必须说不同的话。所以测试盯着：
 *   - **认出了症状**：症状词、就医紧迫程度、推荐项目、**服务端给的理由逐字展示**、区间价；
 *   - **没认出症状**（`matched` 空、`degraded=false`）：说「说得再具体一点」；
 *   - **AI 侧不可用**（`degraded=true`）：说「稍后再试」——与上一条不是同一句话；
 *   - **免责声明/就医提示由服务端给**：前端只展示，不自己拼（安全文案只有一个来源）；
 *   - **需要登录**：未登录看到的是登录提示，而不是一个点了会 40100 的按钮。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ServiceRecommendationView } from "../api/recommendations";
import ServiceFinderView from "../views/ServiceFinderView.vue";
import { apiFailure, mountPage } from "./support";

const recommend = vi.fn();
vi.mock("../api/recommendations", () => ({
  recommendations: {
    recommend: (...args: unknown[]) => recommend(...args),
  },
}));

const YELLOW: ServiceRecommendationView = {
  risk_level: 2,
  matched: [{ symptom: "腹泻", entry_code: "K-0011", title: "犬猫腹泻的家庭观察要点", risk_level: 2 }],
  recommendations: [
    {
      item_code: "HE-012",
      category_code: "HOSPITAL",
      category_name: "医院",
      name: "常见病诊疗",
      price_min: "84.00",
      price_max: "156.00",
      price_unit: "次",
      duration_minutes: 30,
      reason: "因为你说「腹泻」，建议先做常见病诊疗",
    },
  ],
  notice: "以上是按你描述的症状匹配的服务项目，不是诊断。症状持续或加重请及时就医。",
  degraded: false,
};

/**
 * 填一段描述并提交（描述长度满足契约的 2–500），并等这次提交**真的渲染出来**。
 *
 * 等的是「页面变了」而不是「等一拍」。**`flushPromises()` 就是 `setTimeout(resolve, 0)`**
 * （见 @vue/test-utils 的实现），而一次提交要经过 mock 的 promise → 组件状态 → Vue 重渲染；
 * 机器一忙（CI 上并行跑别的套件、本机同时跑别的测试）一拍就不够，断言会落在渲染之前——
 * 表现为**偶发红、单跑必过**，属于最难查的一类。`vi.waitFor` 会重试到条件成立或超时。
 */
async function ask(wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"], text = "我家猫拉稀两次") {
  await wrapper.get("textarea").setValue(text);
  // 在点击前取快照：setValue 已经让字数计数变了，所以基线要取在这一刻
  const before = wrapper.text();
  await wrapper.findAll("button").find((node) => node.text().includes("找服务"))!.trigger("click");
  await vi.waitFor(() => expect(wrapper.text()).not.toBe(before));
}

beforeEach(() => {
  recommend.mockReset();
});

describe("AI 找服务：认出了症状", () => {
  it("列出症状、就医紧迫程度、推荐项目与服务端给的理由", async () => {
    recommend.mockResolvedValue(YELLOW);

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper);

    expect(wrapper.text()).toContain("腹泻");
    expect(wrapper.text()).toContain("建议尽快就医");
    // 「不是诊断结论」这句必须在——风险等级不是诊断（CONTEXT.md 的 RiskLevel）
    expect(wrapper.text()).toContain("不是诊断结论");
    expect(wrapper.text()).toContain("常见病诊疗");
    expect(wrapper.text()).toContain("¥84.00–¥156.00");
    expect(wrapper.text()).toContain("平台区间价");
    // 理由逐字来自服务端
    expect(wrapper.text()).toContain("因为你说「腹泻」，建议先做常见病诊疗");
    // 免责声明逐字来自服务端
    expect(wrapper.text()).toContain("不是诊断。症状持续或加重请及时就医");
  });

  it("推荐项目链到「哪些门店能做」", async () => {
    recommend.mockResolvedValue(YELLOW);

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper);

    const link = wrapper.findAll("a").find((node) => node.text() === "哪些门店能做");
    expect(link?.attributes("href")).toBe("/catalog/items/HE-012");
  });

  it("红色时原样展示服务端那句「立即就医」（前端不自己拼安全文案）", async () => {
    recommend.mockResolvedValue({
      ...YELLOW,
      risk_level: 3,
      matched: [{ symptom: "呼吸异常", entry_code: "K-0013", title: null, risk_level: 3 }],
      notice: "描述里出现了需要尽快处理的情况，请立即就医或联系附近的动物医院。",
    });

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper);

    expect(wrapper.text()).toContain("建议立即就医");
    expect(wrapper.text()).toContain("请立即就医或联系附近的动物医院");
  });
});

describe("AI 找服务：三种「没有推荐」要分开说", () => {
  it("没认出症状：建议把话说具体一点（不是「AI 不可用」）", async () => {
    recommend.mockResolvedValue({
      risk_level: null,
      matched: [],
      recommendations: [],
      notice: "没有识别到具体的症状词。可以把症状说得再具体一些（例如「拉稀」「呕吐」「一直挠」）。",
      degraded: false,
    });

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper, "今天天气不错");

    expect(wrapper.text()).toContain("这次没有给出推荐项目");
    expect(wrapper.text()).toContain("把症状说得再具体一点");
    expect(wrapper.text()).not.toContain("AI 服务暂时不可用");
  });

  it("AI 侧不可用：说稍后再试，并给浏览目录的出口", async () => {
    recommend.mockResolvedValue({
      risk_level: null,
      matched: [],
      recommendations: [],
      notice: "暂时没能读出描述里的症状，请稍后再试。",
      degraded: true,
    });

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper);

    expect(wrapper.text()).toContain("AI 服务暂时不可用");
    const link = wrapper.findAll("a").find((node) => node.text() === "浏览服务目录");
    expect(link?.attributes("href")).toBe("/catalog");
  });

  it("调用失败：显示后端那句话与请求 ID", async () => {
    recommend.mockRejectedValue(apiFailure(50000, "系统繁忙，请稍后重试"));

    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    await ask(wrapper);

    expect(wrapper.text()).toContain("系统繁忙，请稍后重试");
    expect(wrapper.text()).toContain("req-test-1");
  });
});

describe("AI 找服务：门槛与输入", () => {
  it("未登录：给登录提示，不发请求", async () => {
    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "anonymous");

    expect(wrapper.text()).toContain("登录后使用 AI 找服务");
    expect(recommend).not.toHaveBeenCalled();
  });

  it("描述太短时按钮禁用（2–500 字与契约一致）", async () => {
    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");
    const button = wrapper.findAll("button").find((node) => node.text().includes("找服务"))!;

    await wrapper.get("textarea").setValue("吐");
    expect(button.attributes("disabled")).toBeDefined();

    await wrapper.get("textarea").setValue("吐了");
    expect(button.attributes("disabled")).toBeUndefined();
  });

  it("快捷症状点一下填进输入框（这些词来自受控词典，认得出）", async () => {
    const { wrapper } = await mountPage(ServiceFinderView, "/service-finder", "authenticated");

    await wrapper.findAll("button").find((node) => node.text() === "拉稀")!.trigger("click");

    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("拉稀");
  });
});
