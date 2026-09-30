/**
 * 数据看板的组件测试。
 *
 * 这一页的数字来自两份接口（订单列表的 `total` 与考核明细的过程子项），页面本身不做聚合计算。
 * 所以测试盯两件事：**口径写在不在页面上**（「契约里没有订单统计接口」「核销率不在这里另算」），
 * 以及**换日期真的换了请求参数**（日这一档是 `appointment_date` 单日参数能做到的）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AssessmentRow, AssessmentView, ContributionRow } from "../api/providerApi";
import StatsView from "../views/StatsView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();
const listAssessments = vi.fn();
const getAssessment = vi.fn();
const listCouponContributions = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listOrders: (...args: unknown[]) => listOrders(...args),
    listAssessments: (...args: unknown[]) => listAssessments(...args),
    getAssessment: (...args: unknown[]) => getAssessment(...args),
    listCouponContributions: (...args: unknown[]) => listCouponContributions(...args),
  },
}));

const STATUS_TOTALS: Record<number, number> = { 0: 2, 1: 6, 2: 1, 3: 40, 4: 3 };

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
    { item_code: "PROCESS", item_name: "过程", weight: 20, participated: true, score: "88.00", raw_value: "五项等权", data_source: "加权合成" },
    {
      item_code: "PROCESS_REDEEM_RATE",
      item_name: "核销率",
      parent_code: "PROCESS",
      participated: true,
      score: "92.00",
      raw_value: "已核销 18 / 已预约 20",
      target_value: "0.90",
      data_source: "ph-order 订单统计",
    },
    {
      item_code: "PROCESS_REPORT_RATE",
      item_name: "报工完整率",
      parent_code: "PROCESS",
      participated: false,
      score: null,
      data_source: "ph-order 报工事实未接线",
      note: "数据源未接线",
    },
  ],
};

const CONTRIBUTION: ContributionRow = {
  id: 7,
  template_id: 5,
  template_code: "CP-001",
  template_name: "基础体检立减 30 元",
  total_count: 50,
  issued_count: 20,
  redeemed_count: 18,
  reserved_count: 2,
  expired_count: 0,
  completion_rate: "0.36",
  status: 1,
};

function page<T>(list: T[], total = list.length) {
  return { list, page: 1, page_size: 20, total, has_more: false };
}

beforeEach(() => {
  listOrders.mockReset().mockImplementation((params: { status?: number }) =>
    Promise.resolve(page([], STATUS_TOTALS[params.status ?? -1] ?? 0)),
  );
  listAssessments.mockReset().mockResolvedValue(page([ASSESSMENT], 1));
  getAssessment.mockReset().mockResolvedValue(DETAIL);
  listCouponContributions.mockReset().mockResolvedValue(page([CONTRIBUTION]));
});

describe("数据看板：订单量", () => {
  it("按状态显示订单量，并把「累计 / 某一天」的口径与取数方式写清楚", async () => {
    const { wrapper } = await mountPage(StatsView, "/b/stats");

    const stats = wrapper.findAll(".ph-stats__stat").map((item) => item.text().replace(/\s+/g, " "));
    expect(stats).toHaveLength(5);
    expect(stats[0]).toContain("2");
    expect(stats[3]).toContain("40");
    expect(wrapper.text()).toContain("累计（本店全部订单），共 52 单");
    expect(wrapper.text()).toContain("契约里没有订单统计接口");
    expect(wrapper.text()).toContain("「周 / 月」这种时间切片这一版做不了");
  });

  it("选某一天：把日期作为 appointment_date 传给接口（「日」这一档做得到）", async () => {
    const { wrapper } = await mountPage(StatsView, "/b/stats");

    const date = wrapper.get(".ph-stats__filter input");
    await date.setValue("2026-09-30");
    await date.trigger("change");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith(
      { status: 4, appointmentDate: "2026-09-30", page: 1, pageSize: 1 },
      expect.anything(),
    );
    expect(wrapper.text()).toContain("2026年09月30日 要履约的订单");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listOrders.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(StatsView, "/b/stats");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.findAll(".ph-state--error button")[0]?.trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenCalledTimes(10); // 首屏 5 次 + 重试 5 次
  });
});

describe("数据看板：过程指标与券", () => {
  it("过程指标来自考核明细的过程子项（核销率、报工完整率带原始值与数据来源）", async () => {
    const { wrapper } = await mountPage(StatsView, "/b/stats");

    expect(wrapper.text()).toContain("过程指标（账期 2026-08）");
    expect(wrapper.text()).toContain("核销率");
    expect(wrapper.text()).toContain("已核销 18 / 已预约 20");
    expect(wrapper.text()).toContain("ph-order 订单统计");
    expect(wrapper.text()).toContain("报工完整率");
    expect(wrapper.text()).toContain("数据源未接线");
    // 不在前端另算一遍：口径只有考核明细那一份
    expect(wrapper.text()).toContain("不在这里另算");
  });

  it("没有考核记录时说明过程指标随月度考核一起算，而不是显示 0 分", async () => {
    listAssessments.mockResolvedValue(page([], 0));

    const { wrapper } = await mountPage(StatsView, "/b/stats");

    expect(wrapper.text()).toContain("还没有考核记录");
    expect(getAssessment).not.toHaveBeenCalled();
  });

  it("券的额度账：完成率由服务端算好，这里只做展示换算", async () => {
    const { wrapper } = await mountPage(StatsView, "/b/stats");

    expect(wrapper.text()).toContain("券的额度账");
    expect(wrapper.text()).toContain("36%");
    expect(wrapper.text()).toContain("完成率 = 已核销 ÷ 承诺额度，由服务端算好");
  });

  it("无权限态：40300 分别落到订单量与过程指标两格，不给「重试」", async () => {
    listOrders.mockRejectedValue(apiFailure(40300, "无权限", "req-403"));

    const { wrapper } = await mountPage(StatsView, "/b/stats");

    expect(wrapper.findAll(".ph-state--forbidden").length).toBeGreaterThan(0);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("未登录：闸门先拦，数据请求一个都不发", async () => {
    const { wrapper } = await mountPage(StatsView, "/b/stats", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
    expect(listAssessments).not.toHaveBeenCalled();
  });
});
