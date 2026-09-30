/**
 * 系统配置页（SettingsView）的组件测试。
 *
 * 这一页是**只读快照**（为什么只读写在页面的文件头里：可写项在各自的模块页，这一页只汇总 + 指向），
 * 所以测试除了四态，还要把「只读」这件事验成一条断言——页面里不该出现 form 与提交按钮。
 * 否则将来有人顺手加一个文本框，测试仍然全绿。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import SettingsView from "../views/SettingsView.vue";
import { apiFailure, mountPage } from "./support";

const getAssessmentRules = vi.fn();
const getPointsSettings = vi.fn();
const getCouponPoolOverview = vi.fn();
const listAiPrompts = vi.fn();
const listAiSwitches = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    getAssessmentRules: (...args: unknown[]) => getAssessmentRules(...args),
    getPointsSettings: (...args: unknown[]) => getPointsSettings(...args),
    getCouponPoolOverview: (...args: unknown[]) => getCouponPoolOverview(...args),
    listAiPrompts: (...args: unknown[]) => listAiPrompts(...args),
    listAiSwitches: (...args: unknown[]) => listAiSwitches(...args),
  },
}));

beforeEach(() => {
  getAssessmentRules.mockReset().mockResolvedValue({
    invite_weight: 40,
    coupon_weight: 40,
    process_weight: 20,
    invite_target: 3,
    coupon_target: "0.60",
    response_minutes_target: 30,
    levels: [{ level: 1, level_name: "基础", min_score: "0.00", recommend_priority: 3 }],
    updated_at: "2026-09-01 00:00:00",
  });
  getPointsSettings.mockReset().mockResolvedValue({ daily_earn_limit: 20 });
  getCouponPoolOverview.mockReset().mockResolvedValue({ template_count: 4, template_active_count: 3 });
  listAiPrompts.mockReset().mockResolvedValue([
    { id: 1, code: "triage", version: "v3", gray_ratio: 50, enabled: true, review_status: "vetted", updated_by: 9, updated_at: "2026-09-20 10:00:00" },
  ]);
  listAiSwitches.mockReset().mockResolvedValue([
    { id: 1, code: "force_rule_only", enabled: false, remark: "打开后全量走规则通道，不调模型", updated_at: "2026-09-20 10:00:00" },
  ]);
});

describe("系统配置：只读快照", () => {
  it("渲染当前生效的配置，并把每个数字的来源写在分组标题上", async () => {
    const { wrapper } = await mountPage(SettingsView, "/admin/settings");

    expect(wrapper.text()).toContain("来源：GET /api/v1/admin/assessments/rules");
    expect(wrapper.text()).toContain("拉新 40%");
    expect(wrapper.text()).toContain("每日获取上限");
    expect(wrapper.text()).toContain("20 分");
    expect(wrapper.text()).toContain("启用中 3 个");
    expect(wrapper.text()).toContain("triage v3（灰度 50%）");
    expect(wrapper.text()).toContain("force_rule_only");
  });

  it("明说本页是只读快照，并把每个可写项指到承载页", async () => {
    const { wrapper } = await mountPage(SettingsView, "/admin/settings");

    expect(wrapper.text()).toContain("本页是只读快照");
    expect(wrapper.text()).toContain("可写项分散在各自的模块页");
    // 这一波补上的两个承载页：AI 运营与积分/邀请配置（此前只在文字里承认缺页）
    expect(wrapper.text()).toContain("AI 运营");
    expect(wrapper.text()).toContain("积分与邀请配置");
  });

  it("全 0 与空列表照常显示说明，不长出「暂无数据」", async () => {
    listAiPrompts.mockResolvedValue([]);
    listAiSwitches.mockResolvedValue([]);

    const { wrapper } = await mountPage(SettingsView, "/admin/settings");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(false);
    expect(wrapper.text()).toContain("还没有入库的提示词版本");
    expect(wrapper.text()).toContain("开关列表为空");
  });

  it("错误态：显示后端那句话与请求 ID，重试只重读 AI 那一块", async () => {
    listAiSwitches.mockReset().mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(SettingsView, "/admin/settings");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listAiSwitches.mockResolvedValue([
      { id: 1, code: "retrieval_enabled", enabled: true, remark: "知识检索总开关", updated_at: "2026-09-21 10:00:00" },
    ]);
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("retrieval_enabled");
    expect(wrapper.text()).not.toContain("服务器内部错误");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(SettingsView, "/admin/settings", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getPointsSettings).not.toHaveBeenCalled();
    expect(listAiSwitches).not.toHaveBeenCalled();
  });

  it("只读：页面里没有表单、没有输入框，写操作一个都没有", async () => {
    const { wrapper } = await mountPage(SettingsView, "/admin/settings");

    expect(wrapper.find("form").exists()).toBe(false);
    expect(wrapper.find("input").exists()).toBe(false);
    expect(wrapper.find("textarea").exists()).toBe(false);
    // 唯一的按钮是「刷新」
    const labels = wrapper.findAll("button").map((item) => item.text().trim());
    expect(labels).toEqual(["刷新"]);
  });
});
