/**
 * 门店详情（C 端看店，切片 #104）的组件测试。
 *
 * 这一页回答的是「**这家店做这个项目多少钱**」，所以验的重点是：
 *   - **价格与单位如实展示**，且带上「钱在门店付」的口径（金额在这里只是展示值，ADR-0036）；
 *   - **40400 不是「重试」**：未过审 / 冻结 / 资质全过期的店与不存在的店同码，
 *     给的是回服务页的出口——重试按钮会让人一直点一个永远失败的动作；
 *   - **没有在架服务不是错误**：如实说「暂无在架服务」（契约写明）；
 *   - **预约要身份**：未登录时把人带到登录页并带回来处，而不是让他点一下吃一个 40100。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ProviderDetailView } from "../api/providers";
import ProviderDetailViewComponent from "../views/ProviderDetailView.vue";
import { apiFailure, mountPage, networkFailure } from "./support";

const detail = vi.fn();
vi.mock("../api/providers", () => ({
  providers: {
    detail: (...args: unknown[]) => detail(...args),
  },
}));

/**
 * 评价列表是这一页的**第二个数据源**（切片：评价晒单）。
 *
 * 这里显式 mock 它，是为了让本文件的用例只回答「门店详情怎么展示」——
 * 不 mock 的话它会走真实请求层，把一个网络失败的文案混进「资质 / 服务项」的断言里。
 * 评价区块自己的四态（加载 / 失败 / 空 / 列表）在 `order-review.spec.ts` 里逐个验。
 */
const providerReviews = vi.fn();
vi.mock("../api/commerce", () => ({
  commerce: {
    listProviderReviews: (...args: unknown[]) => providerReviews(...args),
  },
}));

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
  rating: "5.00",
  business_hours: [
    { day_of_week: 1, open_time: "09:00", close_time: "18:00" },
    { day_of_week: 6, open_time: "10:00", close_time: "16:00" },
  ],
  qualifications: [{ type: 1, type_name: "营业执照", name: "动物诊疗许可证", valid_until: "2027-03-31" }],
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

beforeEach(() => {
  detail.mockReset();
  providerReviews.mockReset();
  // 默认「这家店还没有评价」：本文件的用例不关心评价内容，只要它不干扰四态
  providerReviews.mockResolvedValue({ list: [], page: 1, page_size: 5, total: 0, has_more: false });
});

describe("门店详情：看得到什么", () => {
  it("门店信息：分类名、脱敏电话、地址与营业时间（不出现的星期几就是休息）", async () => {
    detail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).toContain("康宠动物医院");
    expect(wrapper.text()).toContain("医院");
    expect(wrapper.text()).toContain("138****1111");
    expect(wrapper.text()).toContain("上海市徐汇区测试路 1 号");
    expect(wrapper.text()).toContain("周一 09:00–18:00");
    expect(wrapper.text()).toContain("周六 10:00–16:00");
    expect(detail).toHaveBeenCalledWith(3, expect.anything());
  });

  it("在架服务项：价格、计价单位、耗时与分类都摆出来（这一页就是价格页）", async () => {
    detail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).toContain("基础体检");
    expect(wrapper.text()).toContain("¥150.00");
    expect(wrapper.text()).toContain("计价单位：次");
    expect(wrapper.text()).toContain("约 30 分钟");
    // 钱的语义要写清楚：这是展示价，不是线上收款
    expect(wrapper.text()).toContain("费用在门店直接付给服务者，平台不经手资金");
  });

  it("耗时缺失时不显示成 0 分钟", async () => {
    detail.mockResolvedValue({
      ...PROVIDER,
      services: [{ ...PROVIDER.services![0]!, duration_minutes: null }],
    });

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).not.toContain("0 分钟");
    expect(wrapper.text()).toContain("基础体检");
  });

  it("资质摘要：有效期 / 长期有效，并明说证照编号不展示", async () => {
    detail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).toContain("动物诊疗许可证");
    expect(wrapper.text()).toContain("有效期至 2027年03月31日");
    expect(wrapper.text()).toContain("证件编号不对外展示");

    detail.mockResolvedValue({
      ...PROVIDER,
      qualifications: [{ type: 3, type_name: "法人身份证", name: "法人身份证", valid_until: null }],
    });
    const { wrapper: second } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");
    expect(second.text()).toContain("长期有效");
  });
});

describe("门店详情：看不到的时候", () => {
  it("没有在架服务是正常结果，不是错误", async () => {
    detail.mockResolvedValue({ ...PROVIDER, services: [] });

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).toContain("暂无在架服务");
    // 不是错误态：没有重试按钮，门店与资质照常显示
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
    expect(wrapper.text()).toContain("动物诊疗许可证");
  });

  it("40400 给「暂不可浏览」与回服务页的出口，**不给重试**", async () => {
    detail.mockRejectedValue(apiFailure(40400, "服务者不存在或当前不可预约"));

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/999", "anonymous");

    expect(wrapper.text()).toContain("这家门店暂不可浏览");
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
    const back = wrapper.findAll("a").find((node) => node.text() === "回服务页");
    expect(back?.attributes("href")).toBe("/services");
  });

  it("网络失败仍然是「加载失败 + 重试」（能重试的与不能重试的要分开）", async () => {
    detail.mockRejectedValue(new Error("Failed to fetch"));

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.find(".ph-state--error").exists()).toBe(true);
    expect(wrapper.text()).toContain("加载门店失败，请稍后重试");
    expect(wrapper.text()).not.toContain("Failed to fetch");
  });

  it("请求层给的网络失败文案原样展示", async () => {
    detail.mockRejectedValue(networkFailure());

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    expect(wrapper.text()).toContain("网络连接失败，请检查网络后重试");
  });
});

describe("门店详情：预约入口", () => {
  it("已登录：预约带到下单页，带上门店与服务项 id", async () => {
    detail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "authenticated");

    const book = wrapper.findAll("a").find((node) => node.text() === "预约");
    const href = book?.attributes("href") ?? "";
    expect(href).toContain("/orders/new");
    expect(href).toContain("provider_id=3");
    expect(href).toContain("service_id=21");
  });

  it("未登录：预约先去登录，并带回来处（不是让用户点一下吃一个 40100）", async () => {
    detail.mockResolvedValue(PROVIDER);

    const { wrapper } = await mountPage(ProviderDetailViewComponent, "/providers/3", "anonymous");

    const book = wrapper.findAll("a").find((node) => node.text() === "预约");
    const href = book?.attributes("href") ?? "";
    expect(href).toContain("/login");
    expect(href).toContain("redirect=");
    expect(href).toContain("/providers/3");
  });
});
