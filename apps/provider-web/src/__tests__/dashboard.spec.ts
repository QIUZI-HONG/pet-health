/**
 * 今日概览的组件测试。
 *
 * 这一页有三块独立的数据（今日订单 / 券 / 考核），每块自己一套四态——所以除了「渲染出接口的数据」，
 * 这里专门验一条**独立性**：券接口失败时，当天最要紧的待接单不能被一起藏起来。
 *
 * 订单的计数是「对五个状态各取一次列表、读信封的 `total`」（契约没有订单统计接口），
 * 所以桩要按 `status` 返回不同的 `total` ——测试里也顺便把这条取数口径钉住。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AssessmentRow, ContributionRow, OrderRow } from "../api/providerApi";
import DashboardView from "../views/DashboardView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();
const listCouponContributions = vi.fn();
const listAssessments = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listOrders: (...args: unknown[]) => listOrders(...args),
    listCouponContributions: (...args: unknown[]) => listCouponContributions(...args),
    listAssessments: (...args: unknown[]) => listAssessments(...args),
  },
}));

const PENDING: OrderRow = {
  id: 11,
  order_no: "PH2026093000012345",
  status: 0,
  user_nickname: "张*三",
  user_phone: "138****8888",
  pet_name: "豆豆",
  pet_species: 1,
  service_name: "洗护套餐",
  appointment_date: "2026-09-30",
  start_time: "10:00",
  end_time: "11:00",
  total_amount: "128.00",
  coupon_discount: "20.00",
  estimated_pay_amount: "108.00",
};

const CONTRIBUTION: ContributionRow = {
  id: 7,
  template_id: 5,
  template_code: "CP-001",
  template_name: "基础体检立减 30 元",
  face_value: "30.00",
  total_count: 50,
  redeemed_count: 18,
  reserved_count: 2,
  available_count: 30,
  completion_rate: "0.36",
  status: 1,
};

const ASSESSMENT: AssessmentRow = {
  id: 1,
  period: "2026-08",
  total_score: "86.50",
  level: 2,
  level_name: "优选",
  recommend_priority: 2,
  participated_weight: 100,
  overridden: false,
};

/** 今日各状态的订单数（按 status 分流，与页面「每状态一次、读 total」的取数方式一致） */
const TOTALS: Record<number, number> = { 0: 3, 1: 5, 2: 2, 3: 12, 4: 1 };

beforeEach(() => {
  listOrders.mockReset().mockImplementation((params: { status?: number; pageSize?: number }) =>
    Promise.resolve({
      // 只有待接单那一格里带行（预览用的是 pageSize=5 的那次请求）
      list: params.status === 0 && params.pageSize === 5 ? [PENDING] : [],
      page: 1,
      page_size: params.pageSize ?? 20,
      total: TOTALS[params.status ?? -1] ?? 0,
      has_more: false,
    }),
  );
  listCouponContributions.mockReset().mockResolvedValue({ list: [CONTRIBUTION], page: 1, page_size: 3, total: 1, has_more: false });
  listAssessments.mockReset().mockResolvedValue({ list: [ASSESSMENT], page: 1, page_size: 1, total: 1, has_more: false });
});

describe("今日概览：一屏看完", () => {
  it("今日订单按状态计数（读 total 而不是数一页的行），待接单给出预览", async () => {
    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(wrapper.text()).toContain("今日订单");
    // 五个状态各一格：数字来自信封的 total（3 待接单 / 12 已完成）
    const stats = wrapper.findAll(".ph-dash__stat").map((item) => item.text().replace(/\s+/g, " "));
    expect(stats).toHaveLength(5);
    expect(stats[0]).toContain("3");
    expect(stats[0]).toContain("待接单");
    expect(stats[3]).toContain("12");
    expect(stats[3]).toContain("已完成");
    expect(wrapper.text()).toContain("待接单（最多 5 条）");
    expect(wrapper.text()).toContain("张*三");
    expect(wrapper.text()).toContain("豆豆（犬）");
    expect(wrapper.text()).toContain("¥108.00");
    // 计数口径写在页面上：契约里没有订单统计接口，这一点必须看得见
    expect(wrapper.text()).toContain("契约里没有订单统计接口");
  });

  it("券与考核的摘要都来自真实接口，并给出跳转到明细页的入口", async () => {
    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(listCouponContributions).toHaveBeenCalledWith({ status: 1, page: 1, pageSize: 3 }, expect.anything());
    expect(wrapper.text()).toContain("基础体检立减 30 元");
    expect(wrapper.text()).toContain("30 / 50"); // 可发放 / 承诺额度
    expect(wrapper.text()).toContain("36%");
    expect(wrapper.text()).toContain("86.50");
    expect(wrapper.text()).toContain("优选");
    expect(wrapper.text()).toContain("账期 2026-08");

    const links = wrapper.findAll("a").map((link) => link.text());
    expect(links.some((text) => text.includes("去订单管理"))).toBe(true);
    expect(links.some((text) => text.includes("去券管理"))).toBe(true);
    expect(links.some((text) => text.includes("去考核中心"))).toBe(true);
  });

  it("今天没有订单时说明「计数只按今天筛」，不摆一个空表格", async () => {
    listOrders.mockResolvedValue({ list: [], page: 1, page_size: 1, total: 0, has_more: false });

    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(wrapper.text()).toContain("今天没有要履约的订单");
    expect(wrapper.text()).toContain("今天没有待接单的预约");
  });

  it("三块各自独立：券接口失败时，待接单照常显示（只让券那一格报错）", async () => {
    listCouponContributions.mockRejectedValue(apiFailure(50000, "券服务暂时不可用", "req-coupon"));

    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(wrapper.text()).toContain("券服务暂时不可用");
    expect(wrapper.text()).toContain("req-coupon");
    expect(wrapper.text()).toContain("张*三"); // 待接单照常显示
    expect(wrapper.find(".ph-state--error").exists()).toBe(true);
  });

  it("错误态：失败那一格给「重新加载」，点了重新拉", async () => {
    listOrders.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listOrders.mockImplementation(() => Promise.resolve({ list: [PENDING], page: 1, page_size: 5, total: 3, has_more: false }));
    const retry = wrapper.findAll(".ph-state--error button")[0];
    await retry?.trigger("click");
    await flushPromises();

    // 首屏 6 次（五个状态各一次计数 + 一次待接单预览）+ 重试又是 6 次
    expect(listOrders).toHaveBeenCalledTimes(12);
  });

  it("无权限态：40300 落到对应的那一格，不给无用的「重试」", async () => {
    listAssessments.mockRejectedValue(apiFailure(40300, "无权限", "req-403"));

    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");

    expect(wrapper.text()).toContain("暂无权限");
    expect(wrapper.text()).toContain("考核：最近一期");
  });

  it("未登录：闸门先拦，三块请求一个都不发", async () => {
    const { wrapper } = await mountPage(DashboardView, "/b/dashboard", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
    expect(listCouponContributions).not.toHaveBeenCalled();
    expect(listAssessments).not.toHaveBeenCalled();
  });
});

describe("今日概览：考核卡上的 AI 推荐优先级（4.16.8）", () => {
  it("把推荐优先级写出来（等级决定流量，这一行是它的读数）", async () => {
    listAssessments.mockResolvedValue({ list: [ASSESSMENT], page: 1, page_size: 1, total: 1, has_more: false });

    const { wrapper } = await mountPage(DashboardView, "/b/dashboard");
    await flushPromises();

    expect(wrapper.text()).toContain("AI 推荐优先级：较高");
  });
});
