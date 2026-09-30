/**
 * 结算中心（对账视图）的组件测试。
 *
 * 这一页的头号验收项不是「显示了什么」，而是**没有显示什么**：ADR-0036 让「余额 / 提现」这一整族
 * 字段在系统里根本不存在，所以测试要钉住页面上有一句明确的说明，且不出现余额、提现、退款字样
 * （有人后来「顺手补上」余额时，这里应该立刻红）。
 *
 * 第二块是对账的账：券贡献的额度账要能核对恒等式（`已发放 = 已核销 + 占用中 + 已过期`），
 * 对不上要看得见；订单流水按状态筛，金额用「到店应收」的口径。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { ContributionRow, CouponRow, OrderRow } from "../api/providerApi";
import SettlementView from "../views/SettlementView.vue";
import { apiFailure, mountPage } from "./support";

const listCouponContributions = vi.fn();
const listContributionCoupons = vi.fn();
const listOrders = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listCouponContributions: (...args: unknown[]) => listCouponContributions(...args),
    listContributionCoupons: (...args: unknown[]) => listContributionCoupons(...args),
    listOrders: (...args: unknown[]) => listOrders(...args),
  },
}));

const CONTRIBUTION: ContributionRow = {
  id: 7,
  template_id: 5,
  template_code: "CP-001",
  template_name: "基础体检立减 30 元",
  face_value: "30.00",
  total_count: 50,
  issued_count: 20,
  redeemed_count: 18,
  reserved_count: 2,
  expired_count: 0,
  available_count: 30,
  completion_rate: "0.36",
  status: 1,
};

const COUPON: CouponRow = {
  id: 91,
  code: "C-20260930-0001",
  user_id: 33,
  template_id: 5,
  template_name: "基础体检立减 30 元",
  face_value: "30.00",
  min_amount: "100.00",
  source: 1,
  status: 3,
  issued_at: "2026-09-10 09:00:00",
  valid_until: "2026-10-10 23:59:59",
  redeemed_at: "2026-09-30 14:02:00",
};

const ORDER: OrderRow = {
  id: 41,
  order_no: "PH2026093000041414",
  status: 3,
  user_nickname: "张*三",
  pet_name: "豆豆",
  service_name: "基础体检",
  appointment_date: "2026-09-30",
  total_amount: "150.00",
  coupon_discount: "30.00",
  estimated_pay_amount: "120.00",
};

function page<T>(list: T[]) {
  return { list, page: 1, page_size: 10, total: list.length, has_more: false };
}

beforeEach(() => {
  listCouponContributions.mockReset().mockResolvedValue(page([CONTRIBUTION]));
  listContributionCoupons.mockReset().mockResolvedValue(page([COUPON]));
  listOrders.mockReset().mockResolvedValue(page([ORDER]));
});

describe("结算中心：钱的语义", () => {
  it("页面明确写出「没有余额，也没有提现」，并说明对账在这里的含义", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.text()).toContain("这一页没有余额，也没有提现");
    expect(wrapper.text()).toContain("钱在门店直接付给服务者");
    expect(wrapper.text()).toContain("不是平台的收款事实");

    // 反向断言：整页**没有任何**余额 / 提现 / 退款 / 导出这类动作（ADR-0036 之后它们不存在）。
    // 文字里出现「没有余额」是说明，动作才是要防的东西——所以逐个按钮看。
    const labels = wrapper.findAll("button").map((button) => button.text());
    for (const forbidden of ["提现", "余额", "退款", "打款", "导出"]) {
      expect(labels.some((text) => text.includes(forbidden))).toBe(false);
    }
  });

  it("首屏两段账都加载：券贡献（用于对账）与订单流水", async () => {
    await mountPage(SettlementView, "/b/settle");

    expect(listCouponContributions).toHaveBeenCalledWith({ page: 1, pageSize: 10 }, expect.anything());
    expect(listOrders).toHaveBeenCalledWith({ status: undefined, page: 1, pageSize: 20 }, expect.anything());
  });
});

describe("结算中心：券的对账", () => {
  it("额度账恒等式核对通过时给出「核对通过」，并列出账上的六个数", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.text()).toContain("恒等式核对通过");
    expect(wrapper.text()).toContain("50 / 20");
    expect(wrapper.text()).toContain("36%");
  });

  it("恒等式对不上时如实指出（这是对账页存在的意义）", async () => {
    listCouponContributions.mockResolvedValue(page([{ ...CONTRIBUTION, issued_count: 25 }]));

    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.text()).toContain("恒等式对不上");
  });

  it("缺少额度字段时显示「判不了」，不把「不知道」说成「已核对」", async () => {
    listCouponContributions.mockResolvedValue(page([{ ...CONTRIBUTION, issued_count: undefined, expired_count: undefined }]));

    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.text()).toContain("数据不全，判不了");
  });

  it("选一条贡献看明细：按贡献 id 拉券的发放与核销明细，并按状态筛", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    await wrapper.findAll(".ph-table__action").find((button) => button.text() === "看明细")?.trigger("click");
    await flushPromises();

    expect(listContributionCoupons).toHaveBeenCalledWith(
      7,
      { status: undefined, page: 1, pageSize: 10 },
      expect.anything(),
    );
    expect(wrapper.text()).toContain("C-20260930-0001");
    expect(wrapper.text()).toContain("用户 #33");

    const filter = wrapper.get(".ph-settle__detail select");
    await filter.setValue("3");
    await filter.trigger("change");
    await flushPromises();

    expect(listContributionCoupons).toHaveBeenLastCalledWith(
      7,
      { status: 3, page: 1, pageSize: 10 },
      expect.anything(),
    );
  });
});

describe("结算中心：订单流水", () => {
  it("按履约日期列出订单与「到店应收（总额 − 券面额）」", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.text()).toContain("PH2026093000041414");
    expect(wrapper.text()).toContain("¥150.00");
    expect(wrapper.text()).toContain("¥30.00");
    expect(wrapper.text()).toContain("¥120.00");
  });

  it("状态筛选传给接口；没有导出按钮（契约里没有导出接口）", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    const select = wrapper.get(".ph-settle__filter select");
    await select.setValue("3");
    await select.trigger("change");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith({ status: 3, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("契约里没有对账单 / 导出接口");
    expect(wrapper.findAll("button").some((button) => button.text().includes("导出"))).toBe(false);
  });

  it("空态与错误态（带请求 ID 与重试）", async () => {
    listOrders.mockResolvedValueOnce(page([]));
    listCouponContributions.mockResolvedValueOnce(page([]));
    const { wrapper: empty } = await mountPage(SettlementView, "/b/settle");
    expect(empty.text()).toContain("这一段没有订单");
    expect(empty.text()).toContain("还没有券贡献");

    listCouponContributions.mockReset().mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-500"));
    const { wrapper } = await mountPage(SettlementView, "/b/settle");
    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");
    expect(wrapper.find(".ph-state--error").exists()).toBe(true);
  });

  it("无权限态：40300 进「暂无权限」，不给「重试」", async () => {
    listOrders.mockRejectedValue(apiFailure(40300, "无权限", "req-403"));

    const { wrapper } = await mountPage(SettlementView, "/b/settle");

    expect(wrapper.findAll(".ph-state--forbidden").length).toBeGreaterThan(0);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("未登录：闸门先拦，两段账的请求都不发", async () => {
    const { wrapper } = await mountPage(SettlementView, "/b/settle", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
    expect(listCouponContributions).not.toHaveBeenCalled();
  });
});
