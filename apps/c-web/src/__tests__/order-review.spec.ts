/**
 * 评价晒单（C 端）的组件测试：订单详情页的入口，与门店详情页的评价列表。
 *
 * 四个最容易骗人的点，逐个钉住：
 *   - **入口只对「已完成」出现**：未完成的订单里用户还没拿到服务，没有什么可评的
 *     （服务端同一条规则：不是已完成 → 40900）；已评价的订单显示那条评价而不是再给一次入口；
 *   - **提交时带的是评分 + 文字 + 幂等键**，且文字为空时传 `null`（契约里它是可选的，
 *     空串按「没写」处理——别把空字符串发给服务端）；
 *   - **门店页的评价列表**：条数来自 `total`（评分来自门店详情的 `rating`），空态说「还没有评价」，
 *     「只打了分没写字」的行要显示成一句话而不是空白块；未登录时**不发请求**、给登录入口
 *     （评价是「内容面」，游客只给「浏览服务」——发出去只会拿回 40100）；
 *   - **失败露后端 message**：40900（已经评过 / 订单不是已完成）与 40001 都按原话展示。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { OrderReviewView, OrderView } from "../api/commerce";
import type { ProviderDetailView } from "../api/providers";
import OrderDetailView from "../views/OrderDetailView.vue";
import ProviderDetailViewComponent from "../views/ProviderDetailView.vue";
import { apiFailure, mountPage } from "./support";

const getOrder = vi.fn();
const cancelOrder = vi.fn();
const reviewOrder = vi.fn();
const listProviderReviews = vi.fn();
const providerDetail = vi.fn();

vi.mock("../api/commerce", () => ({
  commerce: {
    getOrder: (...args: unknown[]) => getOrder(...args),
    cancelOrder: (...args: unknown[]) => cancelOrder(...args),
    reviewOrder: (...args: unknown[]) => reviewOrder(...args),
    listProviderReviews: (...args: unknown[]) => listProviderReviews(...args),
  },
}));
vi.mock("../api/providers", () => ({
  providers: {
    detail: (...args: unknown[]) => providerDetail(...args),
  },
}));

const BASE_ORDER: OrderView = {
  id: 42,
  order_no: "PH202609301200001234",
  status: 3,
  provider_id: 3,
  provider_name: "安心宠物医院",
  service_id: 21,
  service_name: "洗护套餐",
  pet_name: "豆豆",
  appointment_date: "2026-10-02",
  start_time: "10:00",
  end_time: "11:00",
  total_amount: "128.00",
  coupon_discount: "0.00",
  estimated_pay_amount: "128.00",
  created_at: "2026-09-30 12:00:00",
};

function order(patch: Partial<OrderView>): OrderView {
  return { ...BASE_ORDER, ...patch };
}

const REVIEW: OrderReviewView = {
  id: 9,
  order_id: 42,
  provider_id: 3,
  rating: 4,
  content: "技师很有耐心",
  created_at: "2026-10-02 12:30:00",
};

const PROVIDER: ProviderDetailView = {
  id: 3,
  name: "康宠动物医院",
  type: 1,
  type_name: "医院",
  logo: null,
  intro: "十年老店，擅长皮肤科",
  address: "上海市徐汇区测试路 1 号",
  lng: "121.4",
  lat: "31.2",
  phone: "138****1111",
  rating: "4.00",
  business_hours: [{ day_of_week: 1, open_time: "09:00", close_time: "18:00" }],
  qualifications: [],
  services: [
    {
      id: 21,
      service_code: "HE-004",
      service_name: "基础体检",
      category_code: "HOSPITAL",
      category_name: "医院",
      price: "150.00",
      price_unit: "次",
      duration_minutes: 30,
    },
  ],
};

function reviewPage(list: OrderReviewView[], patch: Record<string, unknown> = {}) {
  return { list, page: 1, page_size: 5, total: list.length, has_more: false, ...patch };
}

beforeEach(() => {
  getOrder.mockReset();
  cancelOrder.mockReset();
  reviewOrder.mockReset();
  listProviderReviews.mockReset();
  providerDetail.mockReset();
});

describe("订单详情的评价入口", () => {
  it("待接单：**没有评价入口**（服务还没发生）", async () => {
    getOrder.mockResolvedValue(order({ status: 0 }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).not.toContain("评价晒单");
    expect(wrapper.findAll("button").some((button) => button.text().includes("提交评价"))).toBe(false);
  });

  it("履约中也没有入口，已完成才有（服务端同一条规则）", async () => {
    getOrder.mockResolvedValue(order({ status: 2 }));
    const { wrapper: inService } = await mountPage(OrderDetailView, "/orders/42");
    expect(inService.text()).not.toContain("评价晒单");

    getOrder.mockResolvedValue(order({ status: 3 }));
    const { wrapper: completed } = await mountPage(OrderDetailView, "/orders/42");
    expect(completed.text()).toContain("评价晒单");
    expect(completed.findAll("button").some((button) => button.text() === "提交评价")).toBe(true);
  });

  it("已评价的订单：显示那条评价，不再给入口（一单一评，重复提交是 40900）", async () => {
    getOrder.mockResolvedValue(order({ review: REVIEW }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    expect(wrapper.text()).toContain("技师很有耐心");
    expect(wrapper.text()).toContain("★★★★☆");
    expect(wrapper.text()).toContain("4 分");
    expect(wrapper.text()).toContain("不能修改或追评");
    expect(wrapper.findAll("button").some((button) => button.text() === "提交评价")).toBe(false);
  });

  it("没选星级时提交按钮是禁用的（先选星级，省一次 40001 往返）", async () => {
    getOrder.mockResolvedValue(order({ status: 3 }));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");

    const submit = wrapper.findAll("button").find((button) => button.text() === "提交评价");
    expect(submit?.attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("先选星级再提交");
  });

  it("提交：带评分 / 文字 / 幂等键，成功后原地显示评价（入口消失）", async () => {
    getOrder.mockResolvedValue(order({ status: 3 }));
    reviewOrder.mockResolvedValue(REVIEW);

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");
    await wrapper.findAll("button").find((button) => button.text() === "4★")?.trigger("click");
    await wrapper.get("textarea").setValue("  技师很有耐心  ");
    await wrapper.findAll("button").find((button) => button.text() === "提交评价")?.trigger("click");
    await flushPromises();

    const [orderId, body, key] = reviewOrder.mock.calls[0] as [number, { rating: number; content: string | null }, string];
    expect(orderId).toBe(42);
    expect(body.rating).toBe(4);
    expect(body.content).toBe("技师很有耐心");
    // 幂等键必须带：一次写操作，用户连点时先由它挡一道（真正的兜底是服务端的唯一键）
    expect(typeof key).toBe("string");
    expect(key.length).toBeGreaterThan(0);

    expect(wrapper.text()).toContain("技师很有耐心");
    expect(wrapper.findAll("button").some((button) => button.text() === "提交评价")).toBe(false);
  });

  it("只打分不写字：content 传 null（空串按「没写」处理，不发给服务端）", async () => {
    getOrder.mockResolvedValue(order({ status: 3 }));
    reviewOrder.mockResolvedValue({ ...REVIEW, content: null });

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");
    await wrapper.findAll("button").find((button) => button.text() === "5★")?.trigger("click");
    await wrapper.findAll("button").find((button) => button.text() === "提交评价")?.trigger("click");
    await flushPromises();

    const [, body] = reviewOrder.mock.calls[0] as [number, { rating: number; content: string | null }];
    expect(body.rating).toBe(5);
    expect(body.content).toBeNull();
    // 只打分也照样显示（`content` 为空是合法的），而不是一个空白块
    expect(wrapper.text()).toContain("只打了分，没有留言");
  });

  it("提交失败（40900）：露出后端那句话与请求 ID，页面仍停在表单上", async () => {
    getOrder.mockResolvedValue(order({ status: 3 }));
    reviewOrder.mockRejectedValue(apiFailure(40900, "这一单已经评价过了（一单一评，不能改也不能追评）", "req-409"));

    const { wrapper } = await mountPage(OrderDetailView, "/orders/42");
    await wrapper.findAll("button").find((button) => button.text() === "5★")?.trigger("click");
    await wrapper.findAll("button").find((button) => button.text() === "提交评价")?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("这一单已经评价过了（一单一评，不能改也不能追评）");
    expect(wrapper.text()).toContain("req-409");
    expect(wrapper.findAll("button").some((button) => button.text() === "提交评价")).toBe(true);
  });
});

describe("门店详情的评价列表", () => {
  it("未登录：**不发评价请求**，给登录入口（发出去只会拿回 40100）", async () => {
    providerDetail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(listProviderReviews).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("登录后可以查看这家的用户评价");
    // 门店与价格照常显示：未登录只是看不到评价，不是看不到店
    expect(wrapper.text()).toContain("康宠动物医院");
    expect(wrapper.text()).toContain("¥150.00");
    const login = wrapper.findAll("a").find((node) => node.text() === "去登录");
    expect(login?.attributes("href")).toContain("/login");
    expect(login?.attributes("href")).toContain("redirect=");
  });

  it("评分（聚合值）与条数一起显示，列表按服务端给的顺序展示", async () => {
    providerDetail.mockResolvedValue(PROVIDER);
    listProviderReviews.mockResolvedValue(
      reviewPage([REVIEW, { ...REVIEW, id: 8, rating: 5, content: null, created_at: "2026-10-01 09:00:00" }]),
    );

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3");

    expect(wrapper.text()).toContain("用户评价");
    expect(wrapper.text()).toContain("共 2 条评价");
    expect(wrapper.text()).toContain("技师很有耐心");
    expect(wrapper.text()).toContain("★★★★☆");
    // 只打分没写字的行也要显示成一句话
    expect(wrapper.text()).toContain("只打了分，没有留言");
    expect(listProviderReviews).toHaveBeenCalledWith(3, { page: 1, pageSize: 5 }, expect.anything());
  });

  it("没有评价时说「还没有评价」，不是错误态", async () => {
    providerDetail.mockResolvedValue(PROVIDER);
    listProviderReviews.mockResolvedValue(reviewPage([]));

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3");

    expect(wrapper.text()).toContain("还没有评价");
    expect(wrapper.text()).toContain("共 0 条评价");
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("评价加载失败：只把这一块变成失败 + 重试，门店与价格照常显示", async () => {
    providerDetail.mockResolvedValue(PROVIDER);
    listProviderReviews.mockRejectedValueOnce(new Error("Failed to fetch"));
    listProviderReviews.mockResolvedValueOnce(reviewPage([REVIEW]));

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3");

    expect(wrapper.text()).toContain("评价加载失败，请稍后重试");
    expect(wrapper.text()).toContain("康宠动物医院");
    expect(wrapper.text()).toContain("¥150.00");
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);

    await wrapper.findAll("button").find((button) => button.text() === "重试")?.trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("技师很有耐心");
    expect(wrapper.text()).not.toContain("评价加载失败");
  });

  it("还有更多评价时给「查看更多」，点了取下一页", async () => {
    providerDetail.mockResolvedValue(PROVIDER);
    listProviderReviews.mockResolvedValueOnce(
      reviewPage([REVIEW], { total: 6, has_more: true }),
    );
    listProviderReviews.mockResolvedValueOnce(
      reviewPage([{ ...REVIEW, id: 8, content: "第二次来也很好" }], {
        page: 2,
        total: 6,
        has_more: false,
      }),
    );

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3");
    await wrapper.findAll("button").find((button) => button.text() === "查看更多评价")?.trigger("click");
    await flushPromises();

    expect(listProviderReviews).toHaveBeenLastCalledWith(3, { page: 2, pageSize: 5 }, expect.anything());
    expect(wrapper.text()).toContain("第二次来也很好");
    expect(wrapper.text()).toContain("技师很有耐心");
    expect(wrapper.findAll("button").some((button) => button.text() === "查看更多评价")).toBe(false);
  });
});
