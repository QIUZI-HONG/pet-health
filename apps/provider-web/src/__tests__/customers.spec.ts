/**
 * 客户管理的组件测试。
 *
 * 这一页的契约缺口是重点：`contract/provider.yaml` 里**没有客户接口**，所以这一页只能按订单
 * 看到店的人与宠物。测试要验的不只是「渲染了数据」，还有那条同样重要的验收标准：
 * **页面如实说明缺什么、并且不做假动作**——不摆「客户画像」「档案调取」这类点了会 404 的入口，
 * 也不把一页订单自己聚合一遍冒充「来过几次」。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { OrderRow } from "../api/providerApi";
import CustomersView from "../views/CustomersView.vue";
import { apiFailure, mountPage } from "./support";

const listOrders = vi.fn();

vi.mock("../api/providerApi", () => ({
  providerApp: {
    listOrders: (...args: unknown[]) => listOrders(...args),
  },
}));

const ORDER: OrderRow = {
  id: 31,
  order_no: "PH2026093000031313",
  status: 3,
  user_nickname: "王*五",
  user_phone: "135****1234",
  pet_name: "雪球",
  pet_species: 2,
  service_name: "洗护套餐",
  appointment_date: "2026-09-28",
  start_time: "15:00",
  end_time: "16:00",
  total_amount: "168.00",
  coupon_discount: "0.00",
  estimated_pay_amount: "168.00",
  created_at: "2026-09-27 11:00:00",
};

function page<T>(list: T[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listOrders.mockReset().mockResolvedValue(page([ORDER]));
});

describe("客户管理：按订单看到店的人（没有客户接口）", () => {
  it("渲染到店记录：脱敏手机号、宠物与服务项（脱敏由服务端做，前端不二次加工）", async () => {
    const { wrapper } = await mountPage(CustomersView, "/b/customers");

    expect(wrapper.text()).toContain("王*五");
    expect(wrapper.text()).toContain("135****1234");
    expect(wrapper.text()).toContain("雪球");
    expect(wrapper.text()).toContain("洗护套餐");
    expect(wrapper.text()).toContain("¥168.00");
    expect(listOrders).toHaveBeenCalledWith({ keyword: undefined, page: 1, pageSize: 10 }, expect.anything());
  });

  it("页面上如实说明缺哪个接口：客户维度与档案调取都不存在，且档案要用户授权", async () => {
    const { wrapper } = await mountPage(CustomersView, "/b/customers");

    expect(wrapper.text()).toContain("还差两个接口，契约里都没有");
    expect(wrapper.text()).toContain("缺的接口");
    expect(wrapper.text()).toContain("客户维度");
    expect(wrapper.text()).toContain("宠物健康档案调取");
    expect(wrapper.text()).toContain("调取要带用户授权");
  });

  it("按手机号定位：关键字原样传给订单接口（不另造客户查询接口）", async () => {
    const { wrapper } = await mountPage(CustomersView, "/b/customers");

    await wrapper.get(".ph-customers__search input").setValue("13500001234");
    await wrapper.get(".ph-toolbar button").trigger("click");
    await flushPromises();

    expect(listOrders).toHaveBeenLastCalledWith({ keyword: "13500001234", page: 1, pageSize: 10 }, expect.anything());
  });

  it("空态与错误态：空态说清「聚合要客户接口」，错误态带请求 ID 与重试", async () => {
    listOrders.mockResolvedValueOnce(page([]));
    const { wrapper: empty } = await mountPage(CustomersView, "/b/customers");
    expect(empty.text()).toContain("还没有到店记录");
    expect(empty.text()).toContain("要按客户去重或看历史次数，需要客户接口");

    listOrders.mockReset().mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    const { wrapper } = await mountPage(CustomersView, "/b/customers");
    expect(wrapper.text()).toContain("req-500");
    expect(wrapper.find(".ph-state--error").exists()).toBe(true);

    listOrders.mockResolvedValue(page([ORDER]));
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("王*五");
  });

  it("未登录：闸门先拦，订单请求不发", async () => {
    const { wrapper } = await mountPage(CustomersView, "/b/customers", "anonymous");

    expect(wrapper.text()).toContain("尚未登录服务者账号");
    expect(listOrders).not.toHaveBeenCalled();
  });
});
