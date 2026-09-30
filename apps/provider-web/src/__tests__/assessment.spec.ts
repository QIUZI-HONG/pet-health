/**
 * 考核中心的组件测试。
 *
 * 考核最容易「看起来对了但其实是错的」的地方是**明细**：未参与的项、被覆盖的项、每项的数据来源，
 * 藏起来就只剩一个说不清的总分（ADR-0050 第四节点名要求注明未参与）。所以测试重点盯这几样
 * 有没有真的渲染出来，以及分数是**字符串两位小数**而不是金额（不带 ¥）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AssessmentRow, AssessmentView } from "../api/providerApi";
import AssessmentPage from "../views/AssessmentView.vue";
import { apiFailure, mountPage } from "./support";

const listAssessments = vi.fn();
const getAssessment = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listAssessments: (...args: unknown[]) => listAssessments(...args),
    getAssessment: (...args: unknown[]) => getAssessment(...args),
  },
}));

const SUMMARY: AssessmentRow = {
  id: 1,
  period: "2026-08",
  total_score: "86.50",
  level: 2,
  level_name: "优选",
  recommend_priority: 2,
  participated_weight: 100,
  overridden: false,
  calculated_at: "2026-09-01 03:00:00",
};

const DETAIL: AssessmentView = {
  ...SUMMARY,
  items: [
    {
      item_code: "INVITE",
      item_name: "拉新",
      weight: 40,
      participated: true,
      score: "80.00",
      calculated_score: "80.00",
      raw_value: "有效邀请 4 人",
      target_value: "5 人",
      data_source: "ph-privilege 邀请关系",
      note: null,
      overridden: false,
    },
    { item_code: "COUPON", item_name: "券", weight: 40, participated: true, score: "90.00", raw_value: "完成率 0.90 × 核销 9 张", target_value: "0.80", data_source: "ph-privilege 券额度账", overridden: false },
    { item_code: "PROCESS", item_name: "过程", weight: 20, participated: true, score: "88.00", raw_value: "五项等权", data_source: "加权合成", overridden: false },
    {
      item_code: "PROCESS_REDEEM_RATE",
      item_name: "核销率",
      parent_code: "PROCESS",
      participated: true,
      score: "92.00",
      calculated_score: "92.00",
      raw_value: "已核销 18 / 已预约 20",
      target_value: "0.90",
      data_source: "ph-order 订单统计",
      overridden: false,
    },
    {
      item_code: "PROCESS_REPORT_RATE",
      item_name: "报工完整率",
      parent_code: "PROCESS",
      participated: false,
      score: null,
      calculated_score: null,
      raw_value: null,
      data_source: "ph-order 报工事实未接线",
      note: "数据源未接线：不参与、权重按参与项重算",
      overridden: false,
    },
  ],
  overrides: [
    {
      id: 3,
      item_code: "PROCESS_REPORT_RATE",
      item_name: "报工完整率",
      before_score: "60.00",
      after_score: "70.00",
      reason: "门店上传凭证延迟，核后调整",
      operator_id: 1,
      created_at: "2026-09-02 10:00:00",
    },
  ],
};

function page(list: AssessmentRow[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listAssessments.mockReset().mockResolvedValue(page([SUMMARY]));
  getAssessment.mockReset().mockResolvedValue(DETAIL);
});

describe("考核中心：列表", () => {
  it("渲染账期、总分（两位小数、不带 ¥）、等级名与推荐优先级、参与权重", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");

    expect(wrapper.text()).toContain("2026-08");
    expect(wrapper.text()).toContain("86.50");
    expect(wrapper.text()).not.toContain("¥86.50"); // 分数不是金额
    expect(wrapper.text()).toContain("优选");
    expect(wrapper.text()).toContain("AI 推荐优先级：较高");
    expect(wrapper.text()).toContain("三项全部参与计分");
  });

  it("空态：说明账期什么时候算出来", async () => {
    listAssessments.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有考核记录");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listAssessments.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listAssessments.mockResolvedValueOnce(page([SUMMARY]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("86.50");
  });

  it("未登录：闸门先拦，考核请求不发", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listAssessments).not.toHaveBeenCalled();
  });

  it("规则说明写成文案（服务者侧没有规则接口，不能摆成「当前生效的配置」）", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");

    expect(wrapper.text()).toContain("拉新 40% + 券 40% + 过程 20%");
    expect(wrapper.text()).toContain("这里只是说明，不是页面上读到的配置");
  });
});

describe("考核中心：明细", () => {
  it("点「看明细」按账期拉明细：三项 + 过程子项 + 原始值 + 数据来源都显示", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");

    await wrapper.findAll(".ph-table__action")[0]?.trigger("click");
    await flushPromises();

    expect(getAssessment).toHaveBeenCalledWith("2026-08", expect.anything());
    expect(wrapper.text()).toContain("2026-08 考核明细");
    expect(wrapper.text()).toContain("有效邀请 4 人");
    expect(wrapper.text()).toContain("完成率 0.90 × 核销 9 张");
    expect(wrapper.text()).toContain("ph-order 订单统计");
    expect(wrapper.text()).toContain("已核销 18 / 已预约 20");
  });

  it("未参与计分的项单列一段并注明原因（藏起来就只剩一个说不清的总分）", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");
    await wrapper.findAll(".ph-table__action")[0]?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("未参与计分的项（1）");
    expect(wrapper.text()).toContain("数据源未接线：不参与、权重按参与项重算");
    // 未参与项的得分显示成「未参与」，不漏成「0.00」（0 分与不参与是两种事实）
    expect(wrapper.text()).toContain("未参与");
  });

  it("覆盖留痕对服务者可见（谁、何时、改成多少、为什么）", async () => {
    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");
    await wrapper.findAll(".ph-table__action")[0]?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("单项分覆盖留痕（1）");
    expect(wrapper.text()).toContain("60.00 → 70.00");
    expect(wrapper.text()).toContain("门店上传凭证延迟，核后调整");
    expect(wrapper.text()).toContain("操作者 1");
  });

  it("明细 40400（该账期没有记录）：显示后端那句话与请求 ID，并可重新加载", async () => {
    getAssessment.mockRejectedValueOnce(apiFailure(40400, "该账期没有考核记录", "req-404"));

    const { wrapper } = await mountPage(AssessmentPage, "/b/assess");
    await wrapper.findAll(".ph-table__action")[0]?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("该账期没有考核记录");
    expect(wrapper.text()).toContain("req-404");
    expect(wrapper.text()).toContain("重新加载明细");
  });
});
