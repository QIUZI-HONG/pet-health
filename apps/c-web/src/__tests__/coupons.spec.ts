/**
 * 我的券（切片 #110）的组件测试。
 *
 * 三件事：**筛选真的传给了接口**、**空态给的是「怎么才能拿到券」而不是「暂无数据」**、
 * 以及**只有已锁定的券能被释放**（待使用的券不给锁定按钮——锁定是下单动作的占用，
 * 单独锁一张会留下一张没人释放的锁，契约自己也把这条标成待澄清）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { CouponView } from "../api/commerce";
import CouponsView from "../views/CouponsView.vue";
import { apiFailure, mountPage } from "./support";

const listCoupons = vi.fn();
const releaseCoupon = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    listCoupons: (...args: unknown[]) => listCoupons(...args),
    releaseCoupon: (...args: unknown[]) => releaseCoupon(...args),
  },
}));

const USABLE: CouponView = {
  id: 5,
  code: "C-5",
  provider_id: 3,
  provider_name: "安心宠物医院",
  template_name: "洗护满减券",
  face_value: "20.00",
  min_amount: "100.00",
  source: 2,
  status: 1,
  valid_from: "2026-09-01",
  valid_until: "2026-10-31",
  issued_at: "2026-09-20 10:00:00",
};

const LOCKED: CouponView = { ...USABLE, id: 6, code: "C-6", status: 2 };

function page(list: CouponView[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  listCoupons.mockReset();
  releaseCoupon.mockReset();
});

describe("我的券：四态与筛选", () => {
  it("空态：说清券从哪来（平台定向发放），并给两条去处", async () => {
    listCoupons.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("没有抢券入口");
  });

  it("错误态：带请求 ID 与重试", async () => {
    listCoupons.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));
    listCoupons.mockResolvedValueOnce(page([USABLE]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");
    expect(wrapper.text()).toContain("req-500");

    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("洗护满减券");
  });

  it("无权限态：未登录时不发请求", async () => {
    const { wrapper } = await mountPage(CouponsView, "/coupons", "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listCoupons).not.toHaveBeenCalled();
  });

  it("按状态筛选：把状态码传给接口", async () => {
    listCoupons.mockResolvedValue(page([USABLE]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");
    await wrapper.findAll("select")[0]?.setValue("3");
    await flushPromises();

    expect(listCoupons).toHaveBeenLastCalledWith({ status: 3, source: undefined, page: 1, pageSize: 20 }, expect.anything());
  });

  it("按来源筛选：把来源码传给接口（可与状态叠加）", async () => {
    listCoupons.mockResolvedValue(page([USABLE]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");
    await wrapper.findAll("select")[1]?.setValue("4");
    await flushPromises();

    expect(listCoupons).toHaveBeenLastCalledWith({ status: undefined, source: 4, page: 1, pageSize: 20 }, expect.anything());
  });

  it("面额、门槛、有效期、核销门店都在卡上（到店抵扣要用的四样）", async () => {
    listCoupons.mockResolvedValue(page([USABLE]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");

    expect(wrapper.text()).toContain("¥20.00");
    expect(wrapper.text()).toContain("满 ¥100.00 可用");
    expect(wrapper.text()).toContain("2026年10月31日");
    expect(wrapper.text()).toContain("安心宠物医院");
  });
});

describe("我的券：锁定与释放", () => {
  it("已锁定的券可以释放；成功后就地变成「待使用」", async () => {
    listCoupons.mockResolvedValue(page([LOCKED]));
    releaseCoupon.mockResolvedValue({ ...LOCKED, status: 1 });

    const { wrapper } = await mountPage(CouponsView, "/coupons");
    await wrapper.findAll("button").find((button) => button.text() === "释放这张券")?.trigger("click");
    await flushPromises();

    const [couponId, key] = releaseCoupon.mock.calls[0] as [number, string];
    expect(couponId).toBe(6);
    expect(typeof key).toBe("string");
    expect(wrapper.text()).toContain("待使用");
  });

  it("释放被拒（40900：券被未结束的订单占用）：展示后端那句话与请求 ID", async () => {
    listCoupons.mockResolvedValue(page([LOCKED]));
    releaseCoupon.mockRejectedValue(apiFailure(40900, "券被未结束的订单占用，请先取消那一单", "req-409"));

    const { wrapper } = await mountPage(CouponsView, "/coupons");
    await wrapper.findAll("button").find((button) => button.text() === "释放这张券")?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("请先取消那一单");
    expect(wrapper.text()).toContain("req-409");
  });

  it("待使用的券没有「锁定」按钮：锁由订单持有、也由订单释放，独立锁会留下无主的锁", async () => {
    listCoupons.mockResolvedValue(page([USABLE]));

    const { wrapper } = await mountPage(CouponsView, "/coupons");

    expect(wrapper.findAll("button").some((button) => button.text().includes("锁定"))).toBe(false);
    expect(wrapper.findAll("button").some((button) => button.text().includes("释放"))).toBe(false);
  });
});
