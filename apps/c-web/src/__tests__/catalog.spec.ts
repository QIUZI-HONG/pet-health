/**
 * 按项目找服务（C 端目录浏览）的组件测试。
 *
 * 验四件事：
 *   - **分类名来自服务端**（不是前端常量）：运营改了名字，C 端立刻跟着变——这一条是这次
 *     加分类接口的全部理由，测试必须盯着它（断到具体名字上）；
 *   - **区间价与门店报价分开**：列表显示的是平台允许的范围，界面上要写明「平台区间价」，
 *     否则会被读成「起价」；
 *   - **无需登录**、**提交时才搜**、**四态**（与别的浏览页同一套口径）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CatalogCategoryView, CatalogItemView } from "../api/catalog";
import CatalogView from "../views/CatalogView.vue";
import { apiFailure, mountPage } from "./support";

const categories = vi.fn();
const items = vi.fn();
vi.mock("../api/catalog", () => ({
  catalog: {
    categories: (...args: unknown[]) => categories(...args),
    items: (...args: unknown[]) => items(...args),
  },
}));

const CATEGORIES: CatalogCategoryView[] = [
  { code: "HOSPITAL", name: "医院（服务端给的名字）", description: null, icon: null, item_count: 14 },
  { code: "GROOMING", name: "洗护美容", description: null, icon: null, item_count: 10 },
];

const BASIC_EXAM: CatalogItemView = {
  code: "HE-004",
  category_code: "HOSPITAL",
  category_name: "医院",
  name: "基础体检",
  description: "常规体格检查与血常规",
  price_min: "112.00",
  price_max: "208.00",
  price_unit: "次",
  duration_minutes: 30,
  applicable_pets: 3,
};

/** 一页结果的形状（契约的 PageResult）。 */
function page(list: CatalogItemView[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

/** 取接口被调用时传的参数（第一个参数）。 */
function lastParams(): { categoryCode?: string; keyword?: string; page?: number } {
  return items.mock.calls.at(-1)?.[0] as { categoryCode?: string };
}

beforeEach(() => {
  categories.mockReset();
  items.mockReset();
  categories.mockResolvedValue(CATEGORIES);
});

describe("按项目找服务：分类与列表", () => {
  it("分类名与数量来自服务端（运营改名后 C 端立刻生效）", async () => {
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    expect(wrapper.text()).toContain("医院（服务端给的名字）");
    // 计数在标签墙的角标里（`.ph-catalog__chip-count`），值仍来自服务端的 `item_count`
    expect(wrapper.find(".ph-catalog__chip-count").text()).toBe("14");
    expect(wrapper.text()).toContain("洗护美容");
  });

  it("未登录也能浏览项目（只读接口不需要身份）", async () => {
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    expect(wrapper.text()).toContain("基础体检");
    expect(wrapper.text()).not.toContain("登录后查看");
  });

  it("区间价标明是「平台区间价」，并给出「哪些门店能做」的入口", async () => {
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    expect(wrapper.text()).toContain("¥112.00–¥208.00");
    expect(wrapper.text()).toContain("平台区间价");
    const link = wrapper.findAll("a").find((node) => node.text() === "哪些门店能做");
    expect(link?.attributes("href")).toBe("/catalog/items/HE-004");
  });

  it("耗时缺失时不显示成 0 分钟", async () => {
    items.mockResolvedValue(page([{ ...BASIC_EXAM, duration_minutes: null }]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    expect(wrapper.text()).not.toContain("0 分钟");
    expect(wrapper.text()).toContain("基础体检");
  });
});

describe("按项目找服务：筛选与搜索", () => {
  it("切分类把 category_code 传给接口", async () => {
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");
    await wrapper.findAll("button").find((node) => node.text().startsWith("洗护美容"))!.trigger("click");

    expect(lastParams().categoryCode).toBe("GROOMING");
    expect(lastParams().page).toBe(1);
  });

  it("关键词在提交时才搜（输入过程不发请求）", async () => {
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");
    const input = wrapper.get('input[type="search"]');
    const before = items.mock.calls.length;
    await input.setValue("体检");
    expect(items.mock.calls.length).toBe(before);

    await input.trigger("keyup.enter");
    expect(lastParams().keyword).toBe("体检");
  });

  it("分类接口挂了不影响项目列表（只是少了筛选入口）", async () => {
    categories.mockRejectedValue(apiFailure(50000, "系统繁忙，请稍后重试"));
    items.mockResolvedValue(page([BASIC_EXAM]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    // 项目照常显示，分类区不出现（没有报错页把整页吃掉）
    expect(wrapper.text()).toContain("基础体检");
    expect(wrapper.find(".ph-catalog__filters").exists()).toBe(false);
    expect(wrapper.find(".ph-state--error").exists()).toBe(false);
  });
});

describe("按项目找服务：四态", () => {
  it("空态：给下一步并允许清空筛选", async () => {
    items.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");
    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("没有找到服务项目");

    await wrapper.findAll("button").find((node) => node.text().startsWith("洗护美容"))!.trigger("click");
    items.mockResolvedValue(page([BASIC_EXAM]));
    await wrapper.findAll("button").find((node) => node.text() === "清空筛选")!.trigger("click");

    expect(lastParams().categoryCode).toBeUndefined();
    expect(wrapper.text()).toContain("基础体检");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    items.mockRejectedValue(apiFailure(50000, "系统繁忙，请稍后重试"));

    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");
    expect(wrapper.text()).toContain("系统繁忙，请稍后重试");
    expect(wrapper.text()).toContain("req-test-1");

    items.mockResolvedValue(page([BASIC_EXAM]));
    await wrapper.findAll("button").find((node) => node.text() === "重新加载")!.trigger("click");
    expect(wrapper.text()).toContain("基础体检");
  });
});

describe("按项目找服务：分类标签墙（4.16.5 的第 2 块）", () => {
  it("分类是标签墙（三列流式），带项目数；点一个就按它筛", async () => {
    const { wrapper } = await mountPage(CatalogView, "/catalog", "anonymous");

    const chips = wrapper.findAll(".ph-catalog__chip");
    expect(chips.length).toBeGreaterThan(1);
    expect(chips[0]!.text()).toContain("全部分类");
    // 计数来自服务端的 item_count（前端不自己数）
    expect(wrapper.find(".ph-catalog__chip-count").exists()).toBe(true);
    expect(chips[0]!.classes()).toContain("ph-catalog__chip--active");
  });
});
