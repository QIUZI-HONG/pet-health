/**
 * 我的订单（切片 #109）的组件测试。
 *
 * 验四件事，都是「肉眼看不出来、串一次就骗人」的：
 *   - **四态**：加载 / 空 / 错误（带请求 ID 与重试）/ 无权限；
 *   - **状态筛选真的传给了接口**（切筛选不发请求，看起来一样是绿的）；
 *   - **竞态**：先发的请求后回来，不许把新筛选的结果盖成旧的（页面用 createLatestGuard）；
 *   - **状态到动作的映射**：列表上「再次预约」只出现在终态上——这是给下单页留的唯一入口。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { OrderSummaryView } from "../api/commerce";
import MyOrdersView from "../views/MyOrdersView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    listOrders: (...args: unknown[]) => listOrders(...args),
  },
}));

const ORDER_PENDING: OrderSummaryView = {
  id: 11,
  order_no: "PH202609301200001234",
  status: 0,
  provider_id: 3,
  provider_name: "安心宠物医院",
  service_id: 21,
  service_name: "洗护套餐",
  pet_name: "豆豆",
  appointment_date: "2026-10-02",
  start_time: "10:00",
  end_time: "11:00",
  total_amount: "128.00",
  coupon_discount: "20.00",
  estimated_pay_amount: "108.00",
  created_at: "2026-09-30 12:00:00",
};

const ORDER_DONE: OrderSummaryView = { ...ORDER_PENDING, id: 12, status: 3, order_no: "PH202609301200005678" };

/** 一页结果的形状（契约的 PageResult）。 */
function page(list: OrderSummaryView[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listOrders.mockReset();
});

describe("我的订单：四态", () => {
  it("加载中先给骨架（不给「暂无订单」——那会把等待说成没有）", async () => {
    listOrders.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("空态：没有订单时说明下一步去哪，而不是只写「暂无数据」", async () => {
    listOrders.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("还没有订单");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listOrders.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    listOrders.mockResolvedValueOnce(page([ORDER_PENDING]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    expect(wrapper.text()).toContain("服务器内部错误");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("洗护套餐");
  });

  it("服务异常（后端不可用）时说「重试」，不骗用户去登录", async () => {
    listOrders.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders", "error");

    expect(wrapper.text()).toContain("服务暂时不可用");
    expect(wrapper.text()).toContain("req-session-1");
    expect(wrapper.text()).toContain("重新加载");
  });

  it("无权限态：未登录时给去登录的入口，且不发订单请求", async () => {
    const { wrapper } = await mountPage(MyOrdersView, "/orders", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(wrapper.text()).toContain("登录后查看");
    expect(listOrders).not.toHaveBeenCalled();
  });
});

describe("我的订单：筛选与竞态", () => {
  it("切状态筛选：把状态码传给接口，并回到第一页", async () => {
    listOrders.mockResolvedValue(page([ORDER_PENDING]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");
    const filters = wrapper.findAll(".ph-orders__filter");
    // 顺序与契约的状态码一致：全部 / 待接单 / 已预约 / 履约中 / 已完成 / 已取消
    await filters[3]?.trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith({ status: 2, page: 1, pageSize: 20 }, expect.anything());
  });

  it("竞态：先发的请求后回来，不许把新筛选的结果盖掉（createLatestGuard）", async () => {
    // 手动的 deferred：先把第一次请求挂在半空，等新筛选的结果落地后再放行它
    let releaseFirst!: (value: unknown) => void;
    const firstRequest = new Promise((resolve) => {
      releaseFirst = resolve;
    });
    listOrders.mockReturnValueOnce(firstRequest);
    listOrders.mockResolvedValueOnce(page([ORDER_DONE]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");
    // 第一次请求还挂着，用户切到「已完成」
    await wrapper.findAll(".ph-orders__filter")[4]?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("PH202609301200005678");

    // 旧请求现在才回来（先发后回）：它的结果必须被丢弃
    releaseFirst(page([ORDER_PENDING]));
    await flushPromises();

    expect(wrapper.text()).not.toContain("PH202609301200001234");
    expect(wrapper.text()).toContain("PH202609301200005678");
  });
});

describe("我的订单：状态到动作的映射", () => {
  it("待接单：只给「查看详情」，不给「再次预约」（它还能取消，不需要重下单）", async () => {
    listOrders.mockResolvedValue(page([ORDER_PENDING]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    expect(wrapper.text()).toContain("待接单");
    expect(wrapper.text()).toContain("查看详情");
    expect(wrapper.text()).not.toContain("再次预约");
  });

  it("已完成：给「再次预约」，链接带上门店与服务项（下单页靠这两个 id）", async () => {
    listOrders.mockResolvedValue(page([ORDER_DONE]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    const rebook = wrapper.findAll("a").find((link) => link.text() === "再次预约");
    expect(rebook).toBeTruthy();
    expect(rebook?.attributes("href")).toContain("provider_id=");
    expect(rebook?.attributes("href")).toContain("service_id=");
  });

  it("预估实付带 ¥ 前缀展示，且标明是预估（不是「实付」）", async () => {
    listOrders.mockResolvedValue(page([ORDER_PENDING]));

    const { wrapper } = await mountPage(MyOrdersView, "/orders");

    expect(wrapper.text()).toContain("¥108.00");
    expect(wrapper.text()).toContain("预估实付");
  });
});
