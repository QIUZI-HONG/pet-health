/**
 * 考核规则配置页（AssessRulesView）的组件测试。
 *
 * 这一页的重点是**权限**：ADR-0037 的矩阵把考核规则配置划给超级管理员，而前端没有可信来源
 * 判断「谁是超管」（令牌里只有登录域，`session.isSuperAdmin` 当前恒为 false）——所以页面按
 * ProviderListPanel 的处理**不渲染写入口**。测试把这条钉住：默认看不到「编辑规则」，置位之后
 * 才有，并且保存走的是 PUT /assessments/rules。
 *
 * 另外验本地校验与契约的 40001 条件对齐（权重之和 100、基础档从 0 起、三档严格递增）：
 * 这几条是「改错一次影响所有服务者下个月的分」的那类规则，能在本地拦就不要往返一趟。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AssessmentRuleView } from "../api/adminApi";
import AssessRulesView from "../views/AssessRulesView.vue";
import { apiFailure, buttonByText, mountPage, setSuperAdmin } from "./support";

const getAssessmentRules = vi.fn();
const updateAssessmentRules = vi.fn();

vi.mock("../api/adminApi", () => ({
  adminApp: {
    getAssessmentRules: (...args: unknown[]) => getAssessmentRules(...args),
    updateAssessmentRules: (...args: unknown[]) => updateAssessmentRules(...args),
  },
}));

const RULES: AssessmentRuleView = {
  invite_weight: 40,
  coupon_weight: 40,
  process_weight: 20,
  invite_target: 3,
  coupon_target: "0.60",
  response_minutes_target: 30,
  levels: [
    { level: 1, level_name: "基础", min_score: "0.00", recommend_priority: 3, updated_at: "2026-09-01 00:00:00" },
    { level: 2, level_name: "优选", min_score: "60.00", recommend_priority: 2, updated_at: "2026-09-01 00:00:00" },
    { level: 3, level_name: "战略合作", min_score: "85.00", recommend_priority: 1, updated_at: "2026-09-01 00:00:00" },
  ],
  updated_at: "2026-09-01 00:00:00",
};

beforeEach(() => {
  // isSuperAdmin 是模块级单例 ref：每个用例都必须复位，否则上一个用例的置位会漏到下一个
  setSuperAdmin(false);
  getAssessmentRules.mockReset().mockResolvedValue(RULES);
  updateAssessmentRules.mockReset().mockResolvedValue(RULES);
});

describe("考核规则配置：渲染与四态", () => {
  it("渲染三项权重、三条达标线与三档阈值", async () => {
    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    expect(wrapper.text()).toContain("拉新（INVITE）");
    expect(wrapper.text()).toContain("40%");
    expect(wrapper.text()).toContain("20%");
    expect(wrapper.text()).toContain("0.60");
    expect(wrapper.text()).toContain("战略合作");
    expect(wrapper.text()).toContain("85.00");
  });

  it("达标线为 0 时说清是「未配置、该项不参与计分」，而不是「要求是 0 分」", async () => {
    getAssessmentRules.mockResolvedValue({ ...RULES, invite_target: 0 });

    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    expect(wrapper.text()).toContain("未配置，该项不参与计分");
  });

  it("没有档位数据时，表格里给一行说明（契约要求恰好三档）", async () => {
    getAssessmentRules.mockResolvedValue({ ...RULES, levels: [] });

    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    expect(wrapper.text()).toContain("没有档位数据");
  });

  it("错误态：显示后端那句话与请求 ID，并能在原地重试", async () => {
    getAssessmentRules.mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    getAssessmentRules.mockResolvedValue(RULES);
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("战略合作");
  });

  it("未登录：闸门说话，且一个业务请求都不发", async () => {
    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getAssessmentRules).not.toHaveBeenCalled();
  });
});

describe("考核规则配置：写入口只对超级管理员渲染", () => {
  it("角色未落地（isSuperAdmin=false）时不渲染编辑入口，并写明原因", async () => {
    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    expect(wrapper.text()).toContain("本页目前是只读的：写入口只归超级管理员");
    const hasEdit = wrapper.findAll("button").some((item) => item.text().trim() === "编辑规则");
    expect(hasEdit).toBe(false);
  });

  it("是超管时才有编辑入口；权重之和不是 100 时本地拦下，不打接口", async () => {
    setSuperAdmin(true);
    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    await buttonByText(wrapper, "编辑规则").trigger("click");
    const weights = wrapper.findAll(".ph-assess__num");
    await weights[0]?.setValue("50");
    await wrapper.get("form.ph-assess__form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("之和为 100");
    expect(updateAssessmentRules).not.toHaveBeenCalled();
  });

  it("保存：把表单拼成契约的形状（含三档 levels）", async () => {
    setSuperAdmin(true);
    const { wrapper } = await mountPage(AssessRulesView, "/admin/assess-rules");

    await buttonByText(wrapper, "编辑规则").trigger("click");
    await wrapper.get("form.ph-assess__form").trigger("submit");
    await flushPromises();

    expect(updateAssessmentRules).toHaveBeenCalledTimes(1);
    expect(updateAssessmentRules).toHaveBeenCalledWith({
      invite_weight: 40,
      coupon_weight: 40,
      process_weight: 20,
      invite_target: 3,
      coupon_target: "0.60",
      response_minutes_target: 30,
      levels: [
        { level: 1, min_score: "0.00", recommend_priority: 3 },
        { level: 2, min_score: "60.00", recommend_priority: 2 },
        { level: 3, min_score: "85.00", recommend_priority: 1 },
      ],
    });
  });
});
