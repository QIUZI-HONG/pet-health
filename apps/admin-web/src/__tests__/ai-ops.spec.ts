/**
 * AI 运营页（AiOpsView）的组件测试。
 *
 * 这一页是**配置写入面**，所以测试盯的是四条规则（不是渲染细节）：
 *   - **读**：每个分段打开时真的按接口取数（提示词 / 红线词 / 分级规则 / 护栏词 / 开关）；
 *   - **改**：提交时把表单拼成契约要求的形状（`enabled` 是 JSON boolean；新建版本必须带
 *     `tool_schema` 且版本号不能是已有的号）；
 *   - **危险动作有二次确认**：停用红线、切换开关都要「点两次」——第一次只展开确认区，一次点击不执行；
 *   - **失败有提示**：后端那句话与请求 ID 都要露出来。
 *
 * 另外验一条容易漏的：未登录时一个业务请求都不发（闸门管住插槽的挂载）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { GradingRuleRow, GuardTermRow, PromptRow, RedFlagRow, SwitchRow } from "../api/adminApi";
import AiOpsView from "../views/AiOpsView.vue";
import { apiFailure, buttonByText, mountPage } from "./support";

const listAiPrompts = vi.fn();
const createAiPrompt = vi.fn();
const updateAiPrompt = vi.fn();
const listAiRedFlags = vi.fn();
const createAiRedFlag = vi.fn();
const updateAiRedFlag = vi.fn();
const disableAiRedFlag = vi.fn();
const listAiGradingRules = vi.fn();
const createAiGradingRule = vi.fn();
const updateAiGradingRule = vi.fn();
const listAiGuardTerms = vi.fn();
const createAiGuardTerm = vi.fn();
const updateAiGuardTerm = vi.fn();
const listAiSwitches = vi.fn();
const updateAiSwitch = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    listAiPrompts: (...args: unknown[]) => listAiPrompts(...args),
    createAiPrompt: (...args: unknown[]) => createAiPrompt(...args),
    updateAiPrompt: (...args: unknown[]) => updateAiPrompt(...args),
    listAiRedFlags: (...args: unknown[]) => listAiRedFlags(...args),
    createAiRedFlag: (...args: unknown[]) => createAiRedFlag(...args),
    updateAiRedFlag: (...args: unknown[]) => updateAiRedFlag(...args),
    disableAiRedFlag: (...args: unknown[]) => disableAiRedFlag(...args),
    listAiGradingRules: (...args: unknown[]) => listAiGradingRules(...args),
    createAiGradingRule: (...args: unknown[]) => createAiGradingRule(...args),
    updateAiGradingRule: (...args: unknown[]) => updateAiGradingRule(...args),
    listAiGuardTerms: (...args: unknown[]) => listAiGuardTerms(...args),
    createAiGuardTerm: (...args: unknown[]) => createAiGuardTerm(...args),
    updateAiGuardTerm: (...args: unknown[]) => updateAiGuardTerm(...args),
    listAiSwitches: (...args: unknown[]) => listAiSwitches(...args),
    updateAiSwitch: (...args: unknown[]) => updateAiSwitch(...args),
  },
}));

const PROMPT: PromptRow = {
  id: 1,
  code: "triage",
  version: "v3",
  system_prompt: "你是分诊助手",
  tool_schema: "{\"name\":\"report_triage\"}",
  gray_ratio: 50,
  enabled: true,
  review_status: "vetted",
  updated_by: 9,
  updated_at: "2026-09-20 10:00:00",
};

const RED_FLAG: RedFlagRow = {
  id: 11,
  code: "RF-007",
  pattern: "呼吸困难",
  variants: ["喘不上气"],
  species_scope: "all",
  age_stage_scope: "all",
  level: 3,
  action_hint: "请立刻联系就近的宠物医院",
  enabled: true,
  review_status: "pending_review",
  updated_at: "2026-09-20 10:00:00",
};

const GRADING: GradingRuleRow = {
  id: 21,
  code: "GR-001",
  name: "呕吐类",
  match_terms: ["呕吐", "干呕"],
  min_level: 2,
  species_scope: "all",
  age_stage_scope: "all",
  advice: "观察饮水与精神状态",
  enabled: true,
  review_status: "vetted",
  updated_at: "2026-09-20 10:00:00",
};

const GUARD: GuardTermRow = {
  id: 31,
  kind: "drug",
  term: "阿莫西林",
  note: "处方药，不许出现在给用户的文案里",
  enabled: true,
  review_status: "pending_review",
  updated_at: "2026-09-20 10:00:00",
};

const SWITCH: SwitchRow = {
  id: 41,
  code: "force_rule_only",
  enabled: false,
  remark: "打开后全量走规则通道，不调模型",
  updated_at: "2026-09-20 10:00:00",
};

function page(list: unknown[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listAiPrompts.mockReset().mockResolvedValue([PROMPT]);
  createAiPrompt.mockReset().mockResolvedValue(PROMPT);
  updateAiPrompt.mockReset().mockResolvedValue(PROMPT);
  listAiRedFlags.mockReset().mockResolvedValue(page([RED_FLAG]));
  createAiRedFlag.mockReset().mockResolvedValue(RED_FLAG);
  updateAiRedFlag.mockReset().mockResolvedValue(RED_FLAG);
  disableAiRedFlag.mockReset().mockResolvedValue(undefined);
  listAiGradingRules.mockReset().mockResolvedValue([GRADING]);
  createAiGradingRule.mockReset().mockResolvedValue(GRADING);
  updateAiGradingRule.mockReset().mockResolvedValue(GRADING);
  listAiGuardTerms.mockReset().mockResolvedValue([GUARD]);
  createAiGuardTerm.mockReset().mockResolvedValue(GUARD);
  updateAiGuardTerm.mockReset().mockResolvedValue(GUARD);
  listAiSwitches.mockReset().mockResolvedValue([SWITCH]);
  updateAiSwitch.mockReset().mockResolvedValue(SWITCH);
});

describe("AI 运营：能读", () => {
  it("默认打开提示词版本：读得到版本、灰度与「现在跑的是哪一版」", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");

    expect(listAiPrompts).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("当前启用中的版本：triage v3（灰度 50%）");
    expect(wrapper.text()).toContain("已复核");
  });

  it("每个分段各自取数：红线词 / 分级规则 / 护栏词 / 开关", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");

    await buttonByText(wrapper, "硬红线词").trigger("click");
    await flushPromises();
    expect(listAiRedFlags).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("RF-007");
    expect(wrapper.text()).toContain("3 红（建议立即就医）");

    await buttonByText(wrapper, "分级规则").trigger("click");
    await flushPromises();
    expect(listAiGradingRules).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("GR-001");

    await buttonByText(wrapper, "护栏词").trigger("click");
    await flushPromises();
    expect(listAiGuardTerms).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("阿莫西林");

    await buttonByText(wrapper, "运行时开关").trigger("click");
    await flushPromises();
    expect(listAiSwitches).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain("force_rule_only");
  });

  it("加载失败：显示后端那句话与请求 ID，重试真的重读", async () => {
    listAiRedFlags
      .mockReset()
      .mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"))
      .mockResolvedValueOnce(page([RED_FLAG]));

    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "硬红线词").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listAiRedFlags).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("RF-007");
  });

  it("未登录：闸门说话，一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listAiPrompts).not.toHaveBeenCalled();
    expect(listAiRedFlags).not.toHaveBeenCalled();
  });
});

describe("AI 运营：能改", () => {
  it("新建提示词版本：正文与工具定义沿用服务端值，只填新版本号", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");

    await buttonByText(wrapper, "新建版本").trigger("click");
    await wrapper.get('input[placeholder="如 v4"]').setValue("v4");
    await wrapper.get("form.ph-ai__form").trigger("submit");
    await flushPromises();

    expect(createAiPrompt).toHaveBeenCalledTimes(1);
    const body = createAiPrompt.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.code).toBe("triage");
    expect(body.version).toBe("v4");
    expect(body.system_prompt).toBe("你是分诊助手");
    expect(body.tool_schema).toBe("{\"name\":\"report_triage\"}");
    expect(body.gray_ratio).toBe(0);
    // 新建的版本一律 pending_review + 启用，这个字段不该由前端来说
    expect("review_status" in body).toBe(false);
  });

  it("版本号不能重：本地就拦住（后端是 40900），不发请求", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");

    await buttonByText(wrapper, "新建版本").trigger("click");
    await wrapper.get('input[placeholder="如 v4"]').setValue("v3");
    await wrapper.get("form.ph-ai__form").trigger("submit");
    await flushPromises();

    expect(createAiPrompt).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("版本号只能新增");
  });

  it("调灰度：提交形状是 gray_ratio + enabled，且在表单里改不动正文", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");

    await buttonByText(wrapper, "调灰度 / 启停").trigger("click");
    // 正文不在这个接口里（契约的 PromptTemplateUpdateRequest 没有这个字段）：表单里两个大文本框都只读
    const textareas = wrapper.findAll("form.ph-ai__form textarea");
    expect(textareas.length).toBe(2);
    expect(textareas.every((item) => item.attributes("readonly") !== undefined)).toBe(true);

    await wrapper.get('input[placeholder="0"]').setValue("0");
    await wrapper.get("form.ph-ai__form").trigger("submit");
    await flushPromises();

    expect(updateAiPrompt).toHaveBeenCalledTimes(1);
    expect(updateAiPrompt.mock.calls[0]?.[0]).toBe(1);
    const body = updateAiPrompt.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.gray_ratio).toBe(0);
    // 灰度归零 = 不再被命中：这是 ADR-0010 的回滚口径，不能留一个 enabled=true 的 0 灰度版本
    expect(body.enabled).toBe(false);
  });

  it("新增红线的表单里不预置等级（界面不替产品挑 2 还是 3）", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "硬红线词").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增红线").trigger("click");
    const form = wrapper.get("form.ph-ai__form");

    // 第一格是「规则编号」、第二格是「主词」，第三格是等级 select：它的当前值必须是空的占位
    const level = form.findAll("select")[0]!;
    expect(level.element.value).toBe("");
    expect(level.text()).toContain("请选择");

    await form.get('input[placeholder="RF-007"]').setValue("RF-009");
    await form.get('input[placeholder="兽医复核过的症状主词"]').setValue("抽搐");
    await form.get('input[placeholder="命中后要用户做什么（必须建议就医）"]').setValue("请立刻就医");
    await form.trigger("submit");
    await flushPromises();

    expect(createAiRedFlag).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("请选风险等级");
  });

  it("红线停用要二次确认：一次点击只展开确认区，确认后才调接口", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "硬红线词").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "停用").trigger("click");
    await flushPromises();

    expect(disableAiRedFlag).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("不再参与短路判定");

    await buttonByText(wrapper, "确认停用").trigger("click");
    await flushPromises();

    expect(disableAiRedFlag).toHaveBeenCalledWith(11);
    expect(wrapper.text()).toContain("已停用 RF-007");
  });

  it("分级规则的新增把表单拼成契约的形状（命中词按行拆成数组）", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "分级规则").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "新增分级规则").trigger("click");
    const form = wrapper.get("form.ph-ai__form");
    await form.get('input[placeholder="如 GR-003"]').setValue("GR-009");
    await form.get('input[placeholder="这条规则在管什么"]').setValue("腹泻类");
    await form.get("textarea").setValue("腹泻\n软便");
    await form.trigger("submit");
    await flushPromises();

    // 风险下限留空（界面不替产品挑抬到几级）：本地先拦住
    expect(createAiGradingRule).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("请选风险下限");

    await form.findAll("select")[0]!.setValue("2");
    await form.trigger("submit");
    await flushPromises();

    expect(createAiGradingRule).toHaveBeenCalledTimes(1);
    const body = createAiGradingRule.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.match_terms).toEqual(["腹泻", "软便"]);
    expect(body.min_level).toBe(2);
    expect(body.enabled).toBe(true);
  });

  it("开关切换要二次确认，确认后带目标状态调接口", async () => {
    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "运行时开关").trigger("click");
    await flushPromises();

    await buttonByText(wrapper, "打开").trigger("click");
    await flushPromises();

    expect(updateAiSwitch).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("确认打开");

    await buttonByText(wrapper, "确认打开").trigger("click");
    await flushPromises();

    // 契约里路径参数是 code，请求体是 { enabled: boolean }（写 0/1 会被拒成 40001）
    expect(updateAiSwitch).toHaveBeenCalledWith("force_rule_only", true);
  });

  it("写失败：后端那句话与请求 ID 都露出来，开关状态不被当成已改", async () => {
    updateAiSwitch.mockReset().mockRejectedValue(apiFailure(40001, "开关不存在，或这个 code 代码里没人读", "req-400"));

    const { wrapper } = await mountPage(AiOpsView, "/admin/ai-ops");
    await buttonByText(wrapper, "运行时开关").trigger("click");
    await flushPromises();
    await buttonByText(wrapper, "打开").trigger("click");
    await flushPromises();
    await buttonByText(wrapper, "确认打开").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("开关不存在，或这个 code 代码里没人读");
    expect(wrapper.text()).toContain("req-400");
    expect(listAiSwitches).toHaveBeenCalledTimes(1);
  });
});
