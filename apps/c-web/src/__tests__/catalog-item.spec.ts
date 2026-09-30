/**
 * 「哪些门店能做」（按项目找店的落点）的组件测试。
 *
 * 验的重点是这一页独有的两件事：
 *   - **门店定价与 platform 区间要分清**：这里显示的是门店自己的价（¥150.00/次），
 *     并带下单要用的 `service_id`；
 *   - **40400（项目不对外）与「暂时没有门店做」要分开**：前者给回列表的出口、不给重试，
 *     后者是正常结果——契约把这两件事写成了不同的响应。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CatalogItemProviderView } from "../api/catalog";
import CatalogItemView from "../views/CatalogItemView.vue";
import { apiFailure, mountPage, networkFailure } from "./support";

const providersOfItem = vi.fn();
vi.mock("../api/catalog", () => ({
  catalog: {
    providersOfItem: (...args: unknown[]) => providersOfItem(...args),
  },
}));

const ROW: CatalogItemProviderView = {
  provider_id: 3,
  provider_name: "康宠动物医院",
  type: 1,
  type_name: "医院",
  rating: "5.00",
  address: "上海市徐汇区测试路 1 号",
  phone: "138****1111",
  service_id: 21,
  price: "150.00",
  price_unit: "次",
};

function page(list: CatalogItemProviderView[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  providersOfItem.mockReset();
});

describe("按项目找店：门店行", () => {
  it("显示门店定价与计价单位，并带上该店的分类与地址", async () => {
    providersOfItem.mockResolvedValue(page([ROW]));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/HE-004", "anonymous");

    expect(providersOfItem).toHaveBeenCalledWith("HE-004", expect.anything(), expect.anything());
    expect(wrapper.text()).toContain("康宠动物医院");
    expect(wrapper.text()).toContain("¥150.00");
    expect(wrapper.text()).toContain("/ 次");
    expect(wrapper.text()).toContain("医院");
    expect(wrapper.text()).toContain("上海市徐汇区测试路 1 号");
  });

  it("门店详情链接指向门店页（想看资质与全部项目就去那边）", async () => {
    providersOfItem.mockResolvedValue(page([ROW]));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/HE-004", "anonymous");

    const link = wrapper.findAll("a").find((node) => node.text() === "门店详情");
    expect(link?.attributes("href")).toBe("/providers/3");
  });
});

describe("按项目找店：预约入口", () => {
  it("已登录：带去下单页，含门店与服务项 id", async () => {
    providersOfItem.mockResolvedValue(page([ROW]));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/HE-004", "authenticated");

    const href = wrapper.findAll("a").find((node) => node.text() === "预约")?.attributes("href") ?? "";
    expect(href).toContain("/orders/new");
    expect(href).toContain("provider_id=3");
    expect(href).toContain("service_id=21");
  });

  it("未登录：先去登录并带回来处", async () => {
    providersOfItem.mockResolvedValue(page([ROW]));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/HE-004", "anonymous");

    const href = wrapper.findAll("a").find((node) => node.text() === "预约")?.attributes("href") ?? "";
    expect(href).toContain("/login");
    expect(href).toContain("redirect=");
    expect(href).toContain("/catalog/items/HE-004");
  });
});

describe("按项目找店：看不到的时候", () => {
  it("没有门店做这个项目是正常结果，不是错误", async () => {
    providersOfItem.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/IN-006", "anonymous");

    expect(wrapper.text()).toContain("暂时没有门店做这个项目");
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });

  it("40400 给「项目暂不可浏览」与回列表的出口，**不给重试**", async () => {
    providersOfItem.mockRejectedValue(apiFailure(40400, "项目不存在或已停用"));

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/TX-999", "anonymous");

    expect(wrapper.text()).toContain("这个项目暂不可浏览");
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
    const back = wrapper.findAll("a").find((node) => node.text() === "回项目列表");
    expect(back?.attributes("href")).toBe("/catalog");
  });

  it("网络失败仍是「加载失败 + 重试」", async () => {
    providersOfItem.mockRejectedValue(networkFailure());

    const { wrapper } = await mountPage(CatalogItemView, "/catalog/items/HE-004", "anonymous");

    expect(wrapper.find(".ph-state--error").exists()).toBe(true);
    expect(wrapper.text()).toContain("网络连接失败，请检查网络后重试");
  });
});
