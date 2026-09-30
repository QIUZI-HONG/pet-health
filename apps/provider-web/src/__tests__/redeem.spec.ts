/**
 * 核销管理的组件测试。
 *
 * 这一页的关键结构是「**先查后核**」：契约里的核销接口没有请求体，核销码是用来**定位订单**的
 * （`GET /orders?keyword=`），所以测试要验的正是这两步各自打到哪个接口、以及结果面板里
 * 「核销 ≠ 收款」那句话在不在——门店最容易把核销当成收过钱了。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { OrderRow, OrderView } from "../api/providerApi";
import RedeemView from "../views/RedeemView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();
const redeemOrder = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listOrders: (...args: unknown[]) => listOrders(...args),
    redeemOrder: (...args: unknown[]) => redeemOrder(...args),
  },
}));

const BOOKED: OrderRow = {
  id: 21,
  order_no: "PH2026093000021212",
  status: 1,
  user_nickname: "李*四",
  user_phone: "139****6666",
  pet_name: "团子",
  pet_species: 2,
  service_name: "基础体检",
  appointment_date: "2026-09-30",
  start_time: "14:00",
  end_time: "14:30",
  total_amount: "150.00",
  coupon_discount: "0.00",
  estimated_pay_amount: "150.00",
  created_at: "2026-09-29 20:00:00",
};

const ONGOING: OrderRow = { ...BOOKED, id: 22, order_no: "PH2026093000030303", status: 2 };
const DONE: OrderRow = { ...BOOKED, id: 23, order_no: "PH2026093000040404", status: 3 };

function page(list: OrderRow[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listOrders.mockReset().mockImplementation((params: { status?: number; keyword?: string }) => {
    // 定位（带关键字）与记录（按状态）走的是同一个接口，桩按参数分流——与真实调用点一致
    if (params.keyword) return Promise.resolve(page([BOOKED]));
    if (params.status === 3) return Promise.resolve(page([DONE]));
    return Promise.resolve(page([ONGOING]));
  });
  redeemOrder.mockReset().mockResolvedValue({ ...BOOKED, status: 2, redeemed_at: "2026-09-30 14:02:00" } as OrderView);
});

describe("核销管理：先查后核", () => {
  it("首屏加载核销记录（履约中），并且**在查之前不显示空态**（空态会被读成「这个码没订单」）", async () => {
    const { wrapper } = await mountPage(RedeemView, "/b/redeem");

    expect(listOrders).toHaveBeenCalledWith({ status: 2, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("输入核销码后，命中的订单会显示在这里。");
    expect(wrapper.find(".ph-state--empty").exists()).toBe(false);
  });

  it("输入核销码查询：关键字原样传给接口，命中的订单显示出来", async () => {
    const { wrapper } = await mountPage(RedeemView, "/b/redeem");

    await wrapper.get(".ph-redeem__input input").setValue("483920");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(listOrders).toHaveBeenCalledWith({ keyword: "483920", page: 1, pageSize: 5 }, expect.anything());
    expect(wrapper.text()).toContain("PH2026093000021212");
    expect(wrapper.text()).toContain("李*四");
    expect(wrapper.text()).toContain("139****6666"); // 脱敏值原样来自服务端
    expect(wrapper.text()).toContain("团子");
    expect(wrapper.text()).toContain("¥150.00");
  });

  it("没有命中的订单时给「怎么办」，而不是只说空", async () => {
    listOrders.mockImplementation((params: { keyword?: string }) =>
      Promise.resolve(params.keyword ? page([]) : page([ONGOING])),
    );

    const { wrapper } = await mountPage(RedeemView, "/b/redeem");
    await wrapper.get(".ph-redeem__input input").setValue("000000");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("没有命中的订单");
    expect(wrapper.text()).toContain("让用户在 App 的订单详情里重新出示");
  });

  it("核销：调用 redeemOrder（只带订单 id，码不提交），结果面板说明「费用在门店直接付」", async () => {
    const { wrapper } = await mountPage(RedeemView, "/b/redeem");
    await wrapper.get(".ph-redeem__input input").setValue("483920");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const redeem = wrapper.findAll(".ph-table__action").find((button) => button.text() === "核销");
    await redeem?.trigger("click");
    await flushPromises();

    expect(redeemOrder).toHaveBeenCalledWith(21);
    expect(wrapper.text()).toContain("核销成功：PH2026093000021212");
    expect(wrapper.text()).toContain("本次服务费用请在门店直接付给服务者");
    expect(wrapper.text()).toContain("核销后不可撤销");
  });

  it("记录分段：切到「已完成」按状态 3 重新拉，并回到第一页", async () => {
    const { wrapper } = await mountPage(RedeemView, "/b/redeem");

    const done = wrapper.findAll(".ph-tabs__item").find((tab) => tab.text() === "已完成");
    await done?.trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith({ status: 3, page: 1, pageSize: 20 }, expect.anything());
    expect(wrapper.text()).toContain("PH2026093000040404");
  });

  it("核销失败（40900 重复核销）：把后端那句话与请求 ID 显示出来", async () => {
    redeemOrder.mockRejectedValue(apiFailure(40900, "订单不在「已预约」", "req-409"));

    const { wrapper } = await mountPage(RedeemView, "/b/redeem");
    await wrapper.get(".ph-redeem__input input").setValue("483920");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const redeem = wrapper.findAll(".ph-table__action").find((button) => button.text() === "核销");
    await redeem?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("订单不在「已预约」");
    expect(wrapper.text()).toContain("req-409");
  });

  it("无权限态：记录与定位各自的 40300 都由列表四态收口（不给「重试」）", async () => {
    listOrders.mockRejectedValue(apiFailure(40300, "无权限", "req-403"));

    const { wrapper } = await mountPage(RedeemView, "/b/redeem");

    expect(wrapper.findAll(".ph-state--forbidden").length).toBeGreaterThan(0);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("核销记录加载失败：错误态带请求 ID，点重试重新拉", async () => {
    listOrders.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(RedeemView, "/b/redeem");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    listOrders.mockResolvedValue(page([ONGOING]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("PH2026093000030303");
  });

  it("未登录：闸门先拦，核销请求一个都不发", async () => {
    const { wrapper } = await mountPage(RedeemView, "/b/redeem", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
    expect(redeemOrder).not.toHaveBeenCalled();
  });
});
