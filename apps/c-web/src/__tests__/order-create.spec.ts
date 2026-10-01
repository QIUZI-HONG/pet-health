/**
 * 下单页（切片 #109）的组件测试。
 *
 * 这一页的三条硬要求，逐条钉住：
 *   - **选券**：只列能用于本门店的券（服务者贡献券只能在本店核销，平台补贴券哪都能用），
 *     并给「不用券」这个明确的选项；
 *   - **预估实付**：金额只能来自服务端（下单成功后的 `OrderView`），前端不算钱；
 *     而且必须写明**它是预估、费用在门店直接付给服务者**——不许出现「去支付」；
 *   - **约满的时段照样列出来但不可选**：让它消失，用户会以为门店那天不营业。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { AppointmentSlotView, CouponView, OrderView } from "../api/commerce";
import OrderCreateView from "../views/OrderCreateView.vue";
import { apiFailure, mountPage } from "./support";

const listAppointmentSlots = vi.fn();
const listCoupons = vi.fn();
const createOrder = vi.fn();
const providerDetail = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    listAppointmentSlots: (...args: unknown[]) => listAppointmentSlots(...args),
    listCoupons: (...args: unknown[]) => listCoupons(...args),
    createOrder: (...args: unknown[]) => createOrder(...args),
  },
}));
// 门店详情：下单页从它取本单服务项的**报价与目录项编码**（门槛与适用范围都要服务端判，
// 而这一页在提交之前不知道价格）。取不到不阻断下单，所以它在用例里也可以失败——见下面的用例。
vi.mock("../api/providers", () => ({
  providers: { detail: (...args: unknown[]) => providerDetail(...args) },
}));

const CREATE_PATH = "/orders/new?provider_id=3&service_id=21&service_name=%E6%B4%97%E6%8A%A4%E5%A5%97%E9%A4%90";

const SLOTS: AppointmentSlotView[] = [
  { date: "2026-09-30", start_time: "10:00", end_time: "11:00", capacity: 1, booked_count: 0, available_count: 1, full: false },
  { date: "2026-09-30", start_time: "11:00", end_time: "12:00", capacity: 1, booked_count: 1, available_count: 0, full: true },
];

const COUPON_LOCAL: CouponView = {
  id: 5,
  code: "C-LOCAL",
  provider_id: 3,
  provider_name: "安心宠物医院",
  template_name: "洗护满减券",
  face_value: "20.00",
  min_amount: "100.00",
  source: 4,
  status: 1,
  valid_from: "2026-09-01",
  valid_until: "2026-10-31",
  applies: true,
  recommended: false,
};

/** 别家门店的券：服务端判 `applies=false`（服务者贡献券只能在本店核销），**不能**出现在可选券里。 */
const COUPON_OTHER: CouponView = {
  ...COUPON_LOCAL,
  id: 6,
  code: "C-OTHER",
  provider_id: 99,
  provider_name: "别家洗护",
  applies: false,
};

/** 平台补贴券：没有门店，按适用范围核销，所以可以备选。 */
const COUPON_PLATFORM: CouponView = {
  ...COUPON_LOCAL,
  id: 7,
  code: "C-PLATFORM",
  provider_id: null,
  provider_name: null,
  template_name: "平台补贴券",
  face_value: "10.00",
  min_amount: "0.00",
  // 服务端挑出来的最优券（面额 10 元、无门槛、范围不限）
  recommended: true,
};

const CREATED: OrderView = {
  id: 77,
  order_no: "PH202609301300009999",
  status: 0,
  provider_name: "安心宠物医院",
  service_name: "洗护套餐",
  appointment_date: "2026-09-30",
  start_time: "10:00",
  end_time: "11:00",
  total_amount: "128.00",
  coupon_discount: "20.00",
  estimated_pay_amount: "108.00",
};

beforeEach(() => {
  listAppointmentSlots.mockReset();
  listCoupons.mockReset();
  createOrder.mockReset();
  providerDetail.mockReset();
  listAppointmentSlots.mockResolvedValue(SLOTS);
  providerDetail.mockResolvedValue({
    id: 3,
    name: "安心宠物医院",
    rating: "4.80",
    services: [{ id: 21, service_code: "GR-001", service_name: "洗护套餐", price: "128.00" }],
  });
  listCoupons.mockResolvedValue({ list: [COUPON_LOCAL, COUPON_OTHER, COUPON_PLATFORM], page: 1, page_size: 100, total: 3, has_more: false });
});

describe("下单页：入口与四态", () => {
  it("没有门店与服务项时给引导态，一个请求都不发（id 是下单依据，编不出来）", async () => {
    const { wrapper } = await mountPage(OrderCreateView, "/orders/new");

    expect(wrapper.text()).toContain("先选好门店与服务项");
    expect(listAppointmentSlots).not.toHaveBeenCalled();
    expect(listCoupons).not.toHaveBeenCalled();
  });

  it("无权限态：未登录时不发请求", async () => {
    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH, "anonymous");

    expect(wrapper.find(".ph-state--forbidden").exists()).toBe(true);
    expect(listAppointmentSlots).not.toHaveBeenCalled();
  });

  it("号源加载失败：错误态带请求 ID，并能重试", async () => {
    listAppointmentSlots.mockRejectedValueOnce(apiFailure(50000, "服务器内部错误", "req-500"));

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    expect(wrapper.text()).toContain("req-500");
    await wrapper.get(".ph-state--error button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("10:00–11:00");
  });

  it("这天没有时段：空态引导换一天", async () => {
    listAppointmentSlots.mockResolvedValue([]);

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("这天没有可约时段");
  });
});

describe("下单页：时段与选券", () => {
  it("约满的时段照样列出来，但是灰的、不可选（显示「已满」）", async () => {
    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    const slots = wrapper.findAll(".ph-order-form__slot");
    expect(slots).toHaveLength(2);
    expect(slots[1]?.text()).toContain("已满");
    expect(slots[1]?.attributes("disabled")).toBeDefined();
    expect(slots[0]?.attributes("disabled")).toBeUndefined();
  });

  it("选券：只列能用于本门店的券（本店券 + 平台券），别家的券不列", async () => {
    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    expect(wrapper.text()).toContain("洗护满减券");
    expect(wrapper.text()).toContain("平台补贴券");
    expect(wrapper.text()).not.toContain("别家洗护");
    // 明确给「不用券」这个选项，而不是把券当成必选
    expect(wrapper.text()).toContain("不用券");
  });

  it("判定与选优都由服务端给：请求带上门店 / 金额 / 服务项编码，列表按 applies 过滤", async () => {
    await mountPage(OrderCreateView, CREATE_PATH);

    const [params] = listCoupons.mock.calls[0] as [Record<string, unknown>];
    expect(params.provider_id ?? (params as { providerId?: number }).providerId).toBe(3);
    expect((params as { amount?: string }).amount).toBe("128.00");
    expect((params as { serviceCode?: string }).serviceCode).toBe("GR-001");
  });

  it("默认选中服务端推荐的那张券（用户不点也能用上最优券，仍可改选）", async () => {
    createOrder.mockResolvedValue(CREATED);

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    // 一个券都没点：推荐的那张已经是选中态，并且标出了它是服务端推荐的
    expect(wrapper.text()).toContain("服务端推荐");
    expect(wrapper.get(".ph-coupon--selected").text()).toContain("平台补贴券");

    await wrapper.findAll(".ph-order-form__slot")[0]?.trigger("click");
    await wrapper.get(".ph-order-form__submit").trigger("click");
    await flushPromises();

    const [body] = createOrder.mock.calls[0] as [Record<string, unknown>];
    expect(body.coupon_id).toBe(7);            // COUPON_PLATFORM：服务端标了 recommended
  });

  it("门店详情取不到报价时照常可选券：只是门槛留给下单复核（不带金额）", async () => {
    providerDetail.mockRejectedValue(apiFailure(50000, "服务器内部错误", "req-detail"));

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    const [params] = listCoupons.mock.calls[0] as [{ amount?: string | null }];
    expect(params.amount ?? null).toBeNull();
    expect(wrapper.text()).toContain("洗护满减券");
  });

  it("选中的券会显示面额（字符串原样，不做算术）", async () => {
    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);

    await wrapper.findAll(".ph-coupon--selectable")[0]?.trigger("click");

    expect(wrapper.text()).toContain("−¥20.00");
  });
});

describe("下单页：提交与预估实付", () => {
  async function fillForm(wrapper: Awaited<ReturnType<typeof mountPage>>["wrapper"]): Promise<void> {
    await wrapper.findAll(".ph-order-form__slot")[0]?.trigger("click");
    await wrapper.findAll(".ph-coupon--selectable")[0]?.trigger("click");
    await wrapper.get("textarea").setValue("怕吹风机");
  }

  it("下单：带上宠物 / 门店 / 服务项 / 时段 / 券 / 备注与幂等键", async () => {
    createOrder.mockResolvedValue(CREATED);

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);
    await fillForm(wrapper);
    await wrapper.get(".ph-order-form__submit").trigger("click");
    await flushPromises();

    const [body, key] = createOrder.mock.calls[0] as [Record<string, unknown>, string];
    expect(body.pet_id).toBe(7);
    expect(body.provider_id).toBe(3);
    expect(body.service_id).toBe(21);
    expect(body.start_time).toBe("10:00");
    expect(body.coupon_id).toBe(5);
    expect(body.remark).toBe("怕吹风机");
    // 幂等键必须带：连点时后端只成一次（ADR-0028）
    expect(typeof key).toBe("string");
    expect(key.length).toBeGreaterThan(0);
  });

  it("下单成功：展示**服务端给的**预估实付，并写明是预估、费用在门店付", async () => {
    createOrder.mockResolvedValue(CREATED);

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);
    await fillForm(wrapper);
    await wrapper.get(".ph-order-form__submit").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("下单成功");
    expect(wrapper.text()).toContain("预估实付");
    expect(wrapper.text()).toContain("¥108.00");
    expect(wrapper.text()).toContain("预估实付只是预估");
    expect(wrapper.text()).toContain("费用在门店直接付给服务者");
    // 钱在门店付：整页不许出现支付入口（ADR-0036）
    expect(wrapper.text()).not.toContain("去支付");
    expect(wrapper.text()).not.toContain("立即支付");
  });

  it("提交前不编服务项价格：只说「下单后按门店定价展示」，并给出已选券的面额", async () => {
    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);
    await fillForm(wrapper);

    expect(wrapper.text()).toContain("下单后按门店定价展示");
    expect(wrapper.text()).toContain("−¥20.00");
    // 不能凭空造出一个总额（前端算金额就是错的）
    expect(wrapper.text()).not.toContain("¥128.00");
  });

  it("下单被拒（80001 券不可用）：展示后端那句话与请求 ID，不显示结果卡", async () => {
    createOrder.mockRejectedValue(apiFailure(80001, "券不可用", "req-80001"));

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);
    await fillForm(wrapper);
    await wrapper.get(".ph-order-form__submit").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("券不可用");
    expect(wrapper.text()).toContain("req-80001");
    // 结果卡不该出现：失败就是失败，页面停在表单上
    expect(wrapper.find(".ph-order-form__done").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("再约一单");
  });

  it("时段被抢（40900）：原样展示「该时段已被预约」，让用户换一个时段", async () => {
    createOrder.mockRejectedValue(apiFailure(40900, "该时段已被预约"));

    const { wrapper } = await mountPage(OrderCreateView, CREATE_PATH);
    await fillForm(wrapper);
    await wrapper.get(".ph-order-form__submit").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("该时段已被预约");
  });
});
