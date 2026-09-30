/**
 * 服务页（C 端找店，切片 #104）的组件测试。
 *
 * 验四件事，都是「肉眼看不出来、串一次就骗人」的：
 *   - **不需要登录**：未登录就能看到门店列表——这一页如果被闸门挡住，损失的是整个转化漏斗的入口；
 *   - **筛选真的传给了接口**（切分类 / 搜关键词不发请求，看起来一样是绿的）；
 *   - **关键词是提交时才搜**：输入过程中的每个字都发一次请求，肉眼完全看不出来；
 *   - **四态**：加载 / 空（带清空筛选）/ 错误（带请求 ID 与重试）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ProviderSummaryView } from "../api/providers";
import ServicesView from "../views/ServicesView.vue";
import { apiFailure, mountPage, networkFailure } from "./support";

const list = vi.fn();
vi.mock("../api/providers", () => ({
  providers: {
    list: (...args: unknown[]) => list(...args),
  },
}));

const HOSPITAL: ProviderSummaryView = {
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
};

const GROOMER: ProviderSummaryView = {
  ...HOSPITAL,
  id: 4,
  name: "萌宠洗护中心",
  type: 2,
  type_name: "洗护美容",
  phone: "139****2222",
};

/** 一页结果的形状（契约的 PageResult）。 */
function page(items: ProviderSummaryView[]) {
  return { list: items, page: 1, page_size: 20, total: items.length, has_more: false };
}

/** 取接口被调用时传的参数（第一个参数）。 */
function lastParams(): { type?: number; keyword?: string; page?: number; pageSize?: number } {
  return list.mock.calls.at(-1)?.[0] as { type?: number };
}

beforeEach(() => {
  list.mockReset();
});

describe("服务页：能不能看", () => {
  it("未登录也能浏览门店列表（只读接口不需要身份，页面不套登录闸门）", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.text()).toContain("康宠动物医院");
    expect(wrapper.text()).toContain("查看门店与价格");
    // 闸门要是套上了，这里会是「登录后查看」而不是列表
    expect(wrapper.text()).not.toContain("登录后查看");
    expect(list).toHaveBeenCalledTimes(1);
  });

  it("门店卡片带上分类名、评分、地址与脱敏电话", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.text()).toContain("医院");
    expect(wrapper.text()).toContain("评分 5.00");
    expect(wrapper.text()).toContain("上海市徐汇区测试路 1 号");
    expect(wrapper.text()).toContain("138****1111");
    expect(wrapper.text()).toContain("十年老店，擅长皮肤科");
  });

  it("点「查看门店与价格」进详情页（价格在那边，列表不显示）", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    const link = wrapper.findAll("a").find((node) => node.text() === "查看门店与价格");
    expect(link?.attributes("href")).toBe("/providers/3");
    // 列表页不摆价格：门店价格与服务项绑定，摆在这里只会让人误以为是「起价」
    expect(wrapper.text()).not.toContain("¥");
  });
});

describe("服务页：筛选与搜索", () => {
  it("切分类会把 type 传给接口，并回到第一页", async () => {
    list.mockResolvedValue(page([GROOMER]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");
    const grooming = wrapper.findAll("button").find((node) => node.text() === "洗护美容");
    await grooming!.trigger("click");

    expect(lastParams().type).toBe(2);
    expect(lastParams().page).toBe(1);
  });

  it("再点同一个分类不发请求（避免无意义的重复查询）", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");
    const hospital = wrapper.findAll("button").find((node) => node.text() === "医院");
    await hospital!.trigger("click");
    const afterFirstClick = list.mock.calls.length;
    await hospital!.trigger("click");

    // 第一次点（含首屏那次）之后，再点同一个分类不再多发请求
    expect(list.mock.calls.length).toBe(afterFirstClick);
  });

  it("关键词在提交时才搜：输入过程中不发请求，回车才发", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");
    const input = wrapper.get('input[type="search"]');
    const beforeTyping = list.mock.calls.length;
    await input.setValue("康宠");
    // 输入过程中一个请求都不该发出去（只有首屏那一次）
    expect(list.mock.calls.length).toBe(beforeTyping);

    await input.trigger("keyup.enter");

    expect(list.mock.calls.length).toBe(beforeTyping + 1);
    expect(lastParams().keyword).toBe("康宠");
  });

  it("空白关键词按「不过滤」处理：不发一个没有意义的关键词参数", async () => {
    list.mockResolvedValue(page([HOSPITAL]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");
    const input = wrapper.get('input[type="search"]');
    await input.setValue("   ");
    await input.trigger("keyup.enter");

    expect(lastParams().keyword).toBeUndefined();
  });
});

describe("服务页：四态", () => {
  it("加载中先给骨架（不给「没有门店」——那会把等待说成没有）", async () => {
    list.mockReturnValue(new Promise(() => undefined));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.find(".ph-state--loading").exists()).toBe(true);
  });

  it("空态：没有门店时给下一步，并允许清空筛选", async () => {
    list.mockResolvedValue(page([]));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");
    expect(wrapper.find(".ph-state--empty").exists()).toBe(true);
    expect(wrapper.text()).toContain("没有找到门店");

    // 有筛选时给「清空筛选」，点它把分类与关键词一起清掉并重查
    await wrapper.findAll("button").find((node) => node.text() === "医院")!.trigger("click");
    list.mockResolvedValue(page([HOSPITAL]));
    await wrapper.findAll("button").find((node) => node.text() === "清空筛选")!.trigger("click");

    expect(lastParams().type).toBeUndefined();
    expect(lastParams().keyword).toBeUndefined();
    expect(wrapper.text()).toContain("康宠动物医院");
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    list.mockRejectedValue(apiFailure(50000, "系统繁忙，请稍后重试"));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.find(".ph-state--error").exists()).toBe(true);
    expect(wrapper.text()).toContain("系统繁忙，请稍后重试");
    expect(wrapper.text()).toContain("req-test-1");

    list.mockResolvedValue(page([HOSPITAL]));
    await wrapper.findAll("button").find((node) => node.text() === "重新加载")!.trigger("click");

    expect(wrapper.text()).toContain("康宠动物医院");
  });

  it("非 ApiError（原生异常）一律用中文兜底，不把英文技术描述摆到界面上", async () => {
    // 原生 Error 的 message 是给日志看的（"Failed to fetch" 这类），不属于界面
    list.mockRejectedValue(new Error("Failed to fetch"));

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.text()).toContain("加载门店失败，请稍后重试");
    expect(wrapper.text()).not.toContain("Failed to fetch");
  });

  it("请求层给的网络失败文案原样展示（它本来就是中文，不必再换一句）", async () => {
    list.mockRejectedValue(networkFailure());

    const { wrapper } = await mountPage(ServicesView, "/services", "anonymous");

    expect(wrapper.text()).toContain("网络连接失败，请检查网络后重试");
  });
});
