/**
 * 营销中心的组件测试。
 *
 * 这一页最要紧的一条不是「显示了多少数据」，而是**如实说明缺什么**：服务者侧的推广码与拉新明细
 * 在契约里不存在（邀请关系现在是用户对用户，门店维度的归属口径未定，ADR-0052 的「需要协调」第 1 条），
 * 所以页面上不能有「生成推广码」这类点了会 404 的按钮，也不能把不存在的东西摆成占位图。
 * 能接上的是考核明细里的拉新项——测试同时验它、以及它「未参与」时的说明口径。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AssessmentRow, AssessmentView } from "../api/providerApi";
import MarketingView from "../views/MarketingView.vue";
import { apiFailure, mountPage } from "./support";

const listAssessments = vi.fn();
const getAssessment = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listAssessments: (...args: unknown[]) => listAssessments(...args),
    getAssessment: (...args: unknown[]) => getAssessment(...args),
  },
}));

const ASSESSMENT: AssessmentRow = {
  id: 1,
  period: "2026-08",
  total_score: "86.50",
  level: 2,
  level_name: "优选",
  recommend_priority: 2,
  participated_weight: 100,
};

const DETAIL: AssessmentView = {
  ...ASSESSMENT,
  items: [
    {
      item_code: "INVITE",
      item_name: "拉新",
      weight: 40,
      participated: true,
      score: "80.00",
      raw_value: "有效邀请 4 人",
      target_value: "5 人",
      data_source: "ph-privilege 邀请关系",
      note: "只算完成建档且 24 小时内有行为的被邀请人",
    },
  ],
};

function page<T>(list: T[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listAssessments.mockReset().mockResolvedValue(page([ASSESSMENT]));
  getAssessment.mockReset().mockResolvedValue(DETAIL);
});

describe("营销中心：拉新成绩与缺的接口", () => {
  it("本店拉新成绩来自考核明细（原始值、权重、达标线、数据来源都在）", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(listAssessments).toHaveBeenCalledWith({ page: 1, pageSize: 1 }, expect.anything());
    expect(getAssessment).toHaveBeenCalledWith("2026-08", expect.anything());
    expect(wrapper.text()).toContain("本店拉新（考核口径）");
    expect(wrapper.text()).toContain("拉新（权重 40%）");
    expect(wrapper.text()).toContain("有效邀请 4 人");
    expect(wrapper.text()).toContain("5 人");
    expect(wrapper.text()).toContain("ph-privilege 邀请关系");
  });

  it("拉新项未参与时说明「为什么不参与」而不是记成 0 分", async () => {
    getAssessment.mockResolvedValue({
      ...DETAIL,
      items: [
        {
          item_code: "INVITE",
          item_name: "拉新",
          weight: 40,
          participated: false,
          score: null,
          data_source: "ProviderGrowthFactsApi 未接线",
          note: "平台侧无拉新入口：不参与、权重按参与项重算",
        },
      ],
    } as AssessmentView);

    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(wrapper.text()).toContain("未参与计分");
    expect(wrapper.text()).toContain("平台侧无拉新入口：不参与、权重按参与项重算");
    expect(wrapper.text()).toContain("而不是把拉新记成 0 分");
  });

  it("推广码与物料：如实说明缺接口，并**不摆**「生成推广码」这类点了会 404 的按钮", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");

    expect(wrapper.text()).toContain("推广码与店内物料（缺接口）");
    expect(wrapper.text()).toContain("没有服务者侧的邀请接口");
    expect(wrapper.text()).toContain("invite_relation.inviter_user_id");
    const buttons = wrapper.findAll("button").map((button) => button.text());
    expect(buttons.some((text) => text.includes("推广码"))).toBe(false);
    expect(buttons.some((text) => text.includes("二维码"))).toBe(false);
  });

  it("没有考核记录时的空态，以及接口失败时的错误态（带请求 ID 与重试）", async () => {
    listAssessments.mockResolvedValueOnce(page([]));
    const { wrapper: empty } = await mountPage(MarketingView, "/b/marketing");
    expect(empty.text()).toContain("还没有考核记录");

    listAssessments.mockReset().mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    const { wrapper } = await mountPage(MarketingView, "/b/marketing");
    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");
  });

  it("未登录：闸门先拦，考核请求不发", async () => {
    const { wrapper } = await mountPage(MarketingView, "/b/marketing", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listAssessments).not.toHaveBeenCalled();
  });
});
