/**
 * 订单详情（切片 #109）的组件测试。
 *
 * 三个最容易骗人的点，逐个钉住：
 *   - **核销码的可见性**由服务端判（到预约时间、且在「已预约 / 履约中」）——前端不许自己看时间算，
 *     所以这里验「服务端给了就展示、没给就说清什么时候可见」；
 *   - **状态到动作的映射**：待接单可取消、已预约只是申请、**履约中不可取消**（ADR-0038 第一节）；
 *   - **照片墙是只读的**：`reportable` / `missing_slots` 由服务端算，界面不自己数照片。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { FileView } from "@pet-health/shared";
import type { OrderView } from "../api/commerce";
import OrderDetailView from "../views/OrderDetailView.vue";
import { apiFailure, mountPage } from "./support";

const getOrder = vi.fn();
const cancelOrder = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    getOrder: (...args: unknown[]) => getOrder(...args),
    cancelOrder: (...args: unknown[]) => cancelOrder(...args),
  },
}));

const BASE_ORDER: OrderView = {
  id: 42,
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
  remark: "怕吹风机，请轻一点",
  created_at: "2026-09-30 12:00:00",
};

function order(patch: Partial<OrderView>): OrderView {
  return { ...BASE_ORDER, ...patch };
}

/** 一条照片元数据（契约的 FileView 是必填较多的形状，这里只填测试用得到的字段）。 */
function photo(id: number): FileView {
  return { id, biz_type: "care", role: "original", mime: "image/jpeg", size_bytes: 1024 };
}

beforeEach(() => {
  getOrder.mockReset();
  cancelOrder.mockReset();
});

describe("订单详情：四态", () => {
  it("加载中给骨架", async () => {
    getOrder.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("错误态：带请求 ID 与重试", async () => {
    getOrder.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    getOrder.mockResolvedValueOnce(order({}));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("req-500");
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("洗护套餐");
  });

  it("订单不存在（40400）：重试没有意义，给「回到我的订单」而不是错误态", async () => {
    getOrder.mockRejectedValue(apiFailure(40400, "订单不存在"));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("订单不存在");
    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("无权限态：未登录时不发请求，给去登录的入口", async () => {
    const { wrapper } = await mountPage(OrderDetailView, "/orders/42", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(getOrder).not.toHaveBeenCalled();
  });
});

describe("订单详情：核销码与费用口径", () => {
  it("服务端给了核销码就展示（6 位），并写明核销与收款无关", async () => {
    getOrder.mockResolvedValue(order({ status: 1, redeem_code: "042317" }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("042317");
    expect(wrapper.text()).toContain("与收款无关");
    expect(wrapper.text()).toContain("费用在门店直接付给服务者");
  });

  it("核销码为空（未到可见条件）时说清什么时候可见，而不是留一个空框", async () => {
    getOrder.mockResolvedValue(order({ status: 0, redeem_code: null }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("门店接单后会生成核销码");
  });

  it("已完成的订单不再显示核销码（终态让位，服务端判）", async () => {
    getOrder.mockResolvedValue(order({ status: 3, redeem_code: null, reported_at: "2026-10-02 11:20:00" }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("服务已完成，核销码不再显示");
  });

  it("金额三行都在，且大字标的是预估实付（不是「实付」）", async () => {
    getOrder.mockResolvedValue(order({}));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("¥128.00");
    expect(wrapper.text()).toContain("¥108.00");
    expect(wrapper.text()).toContain("预估实付");
    expect(wrapper.text()).not.toContain("去支付");
  });
});

describe("订单详情：状态到动作的映射（ADR-0038 第一节）", () => {
  it("待接单：可取消；提交时带理由与幂等键，成功后状态转已取消", async () => {
    getOrder.mockResolvedValue(order({ status: 0 }));
    cancelOrder.mockResolvedValue(order({ status: 4, cancelled_at: "2026-09-30 13:00:00", cancelled_by: 1 }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    const cancelButton = wrapper.findAll("button").find((button) => button.text() === "取消订单");
    expect(cancelButton).toBeTruthy();
    await cancelButton?.trigger("click");

    await wrapper.get("textarea").setValue("时间冲突，改约下周");
    const confirm = wrapper.findAll("button").find((button) => button.text() === "确认取消订单");
    await confirm?.trigger("click");
    await flushPromises();

    const [orderId, reason, key] = cancelOrder.mock.calls[0] as [number, string, string];
    expect(orderId).toBe(42);
    expect(reason).toBe("时间冲突，改约下周");
    // 幂等键必须带：重复提交时后端只执行一次（ADR-0028）
    expect(typeof key).toBe("string");
    expect(key.length).toBeGreaterThan(0);
    expect(wrapper.text()).toContain("已取消");
  });

  it("已预约：按钮是「申请取消」，并写明订单仍然是「已预约」", async () => {
    getOrder.mockResolvedValue(order({ status: 1, redeem_code: "042317", cancel_request_status: null }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.findAll("button").some((button) => button.text() === "申请取消")).toBe(true);
    await wrapper.findAll("button").find((button) => button.text() === "申请取消")?.trigger("click");
    expect(wrapper.text()).toContain("仍然是「已预约」");
  });

  it("履约中：**没有取消入口**，并说明服务已开始", async () => {
    getOrder.mockResolvedValue(order({ status: 2, redeem_code: "042317" }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.findAll("button").some((button) => button.text().includes("取消"))).toBe(false);
    expect(wrapper.text()).toContain("不能取消");
  });

  it("已预约且有待处理的取消申请：不再给按钮，只说等门店处理", async () => {
    getOrder.mockResolvedValue(
      order({ status: 1, cancel_request_status: 1, cancel_requested_at: "2026-09-30 13:00:00" }),
    );

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.findAll("button").some((button) => button.text().includes("取消"))).toBe(false);
    expect(wrapper.text()).toContain("取消申请已提交");
  });

  it("门店拒绝过：显示拒绝理由，并允许再申请一次", async () => {
    getOrder.mockResolvedValue(
      order({ status: 1, cancel_request_status: 3, cancel_rejected_reason: "技师已排班，请到店沟通" }),
    );

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("技师已排班，请到店沟通");
    expect(wrapper.findAll("button").some((button) => button.text() === "再次申请取消")).toBe(true);
  });

  it("取消失败（40900）：展示后端那句话与请求 ID，页面状态不变", async () => {
    getOrder.mockResolvedValue(order({ status: 1, cancel_request_status: null }));
    cancelOrder.mockRejectedValue(apiFailure(40900, "当前订单是「已预约」，不能取消", "req-409"));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");
    await wrapper.findAll("button").find((button) => button.text() === "申请取消")?.trigger("click");
    await wrapper.findAll("button").find((button) => button.text() === "确认申请取消")?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("当前订单是「已预约」，不能取消");
    expect(wrapper.text()).toContain("req-409");
  });
});

describe("订单详情：三道照片墙（只读）", () => {
  it("缺哪一道由服务端给，界面照实说，并给「每道至少一张」的口径", async () => {
    getOrder.mockResolvedValue(
      order({
        status: 2,
        photo_wall: {
          slots: [
            { slot: 1, slot_name: "接宠检查", photos: [photo(1)], satisfied: true },
            { slot: 2, slot_name: "服务防护", photos: [], satisfied: false },
            { slot: 3, slot_name: "取宠对比", photos: [], satisfied: false },
          ],
          reportable: false,
          missing_slots: [2, 3],
        },
      }),
    );

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("还缺：服务防护、取宠对比");
    expect(wrapper.text()).toContain("已上传 1 张");
    // C 端没有上传 / 报工能力，所以这一块不该出现任何写操作按钮
    expect(wrapper.findAll("button").some((button) => button.text().includes("上传"))).toBe(false);
  });

  it("三道齐了就说可以报工（结论来自服务端）", async () => {
    getOrder.mockResolvedValue(
      order({
        status: 2,
        photo_wall: {
          slots: [
            { slot: 1, slot_name: "接宠检查", photos: [photo(1)], satisfied: true },
            { slot: 2, slot_name: "服务防护", photos: [photo(2)], satisfied: true },
            { slot: 3, slot_name: "取宠对比", photos: [photo(3)], satisfied: true },
          ],
          reportable: true,
          missing_slots: [],
        },
      }),
    );

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("三道照片墙已齐");
  });
});
