/**
 * 知识库浏览页（F024 / F025）的组件测试。
 *
 * 钉住的是三条**规则**，不是渲染细节：
 *  1. `review_status` **必须原样展示**——未复核的条目不许被说成已复核（ADR-0033 / ADR-0025 第二节）；
 *  2. 医疗合规（docs/conventions.md）：详情必须带免责声明，红色风险必须建议就医；
 *  3. 四态齐全，且**需要登录**（未登录时不发请求，显示闸门而不是一串 40100）。
 *
 * 另外钉住「入口真的可达」：首页「快捷服务」里那条链接必须指向 `/knowledge`——
 * 否则页面存在但没人能走到，等于孤儿页。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import type { KnowledgeEntryView as KnowledgeEntry } from "@pet-health/shared";
import KnowledgeView from "../views/KnowledgeView.vue";
import KnowledgeEntryView from "../views/KnowledgeEntryView.vue";
import HomeView from "../views/HomeView.vue";
import { apiFailure, mountPage } from "./support";

const listKnowledgeEntries = vi.fn();
const getKnowledgeEntry = vi.fn();
// 首页要的那几个出口：验「入口可达」时页面会整体加载
const getHealthScore = vi.fn();
const getCheckInDay = vi.fn();
const getCheckInStreak = vi.fn();
const getMessageHighlights = vi.fn();
const getUnreadCount = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      listKnowledgeEntries: (...args: unknown[]) => listKnowledgeEntries(...args),
      getKnowledgeEntry: (...args: unknown[]) => getKnowledgeEntry(...args),
      getHealthScore: (...args: unknown[]) => getHealthScore(...args),
      getCheckInDay: (...args: unknown[]) => getCheckInDay(...args),
      getCheckInStreak: (...args: unknown[]) => getCheckInStreak(...args),
      getMessageHighlights: (...args: unknown[]) => getMessageHighlights(...args),
      getUnreadCount: (...args: unknown[]) => getUnreadCount(...args),
    },
  };
});

/** 待复核（多数条目的真实状态）：这是全篇最重要的一条 fixture。 */
const ENTRY_PENDING: KnowledgeEntry = {
  code: "K-0011",
  title: "犬瘟热",
  summary: "高热、眼鼻分泌物、后期可能出现神经症状。",
  body: "",
  category_code: "disease",
  category_name: "常见病",
  risk_level: 3,
  review_status: "pending_review",
  source_title: "公开兽医共识（2024）",
  source_url: null,
};

const ENTRY_VETTED: KnowledgeEntry = {
  ...ENTRY_PENDING,
  code: "K-0002",
  title: "犬用疫苗的接种周期",
  category_code: "vaccine",
  category_name: "疫苗",
  risk_level: null,
  review_status: "vetted",
};

function pageOf(list: KnowledgeEntry[]) {
  return { list, page: 1, page_size: 20, total: list.length, has_more: false };
}

beforeEach(() => {
  vi.clearAllMocks();
  listKnowledgeEntries.mockResolvedValue(pageOf([ENTRY_PENDING, ENTRY_VETTED]));
  getKnowledgeEntry.mockResolvedValue({ ...ENTRY_PENDING, body: "典型症状是双相热。\n请尽快就医。" });
  getHealthScore.mockResolvedValue({ total_score: null, dimensions: [], trend: [] });
  getCheckInDay.mockResolvedValue({
    date: "2026-09-30",
    items: [],
    completed_count: 0,
    total_count: 6,
    done: false,
    backfilled: false,
  });
  getCheckInStreak.mockResolvedValue({ streak_days: 0, checked_today: false });
  getMessageHighlights.mockResolvedValue([]);
  getUnreadCount.mockResolvedValue({ unread: 0, unread_reminders: 0 });
});

describe("知识库列表", () => {
  it("每条都标出复核状态：待复核的不许被说成已复核", async () => {
    const { wrapper } = await mountPage(KnowledgeView, "/knowledge");

    expect(wrapper.text()).toContain("犬瘟热");
    expect(wrapper.text()).toContain("待复核");
    expect(wrapper.text()).toContain("已复核");
    // 分类中文名由服务端给，前端不认编码（C 端不许自己拼分类表）
    expect(wrapper.text()).toContain("常见病");
    expect(listKnowledgeEntries).toHaveBeenCalledWith({ keyword: undefined, page: 1, pageSize: 20 }, expect.anything());
  });

  it("关键词提交时才发请求（不是逐字发）", async () => {
    const { wrapper } = await mountPage(KnowledgeView, "/knowledge");
    expect(listKnowledgeEntries).toHaveBeenCalledTimes(1);

    await wrapper.find('input[aria-label="搜索知识条目"]').setValue("犬瘟");
    expect(listKnowledgeEntries).toHaveBeenCalledTimes(1);   // 只是输入，还没搜

    await wrapper.find('input[aria-label="搜索知识条目"]').trigger("keyup.enter");
    await flushPromises();
    expect(listKnowledgeEntries).toHaveBeenLastCalledWith({ keyword: "犬瘟", page: 1, pageSize: 20 }, expect.anything());
  });

  it("错误态：显示后端那句话与请求 ID，并能重试", async () => {
    listKnowledgeEntries.mockRejectedValueOnce(apiFailure(50001, "服务暂时不可用", "req-kb-1"));
    const { wrapper } = await mountPage(KnowledgeView, "/knowledge");

    expect(wrapper.text()).toContain("服务暂时不可用");
    expect(wrapper.text()).toContain("req-kb-1");

    await wrapper.findAll("button").find((button) => button.text().includes("重新加载"))?.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("犬瘟热");
  });

  it("空态给出下一步（换个说法再搜），不是只写「暂无数据」", async () => {
    listKnowledgeEntries.mockResolvedValue(pageOf([]));
    const { wrapper } = await mountPage(KnowledgeView, "/knowledge");
    expect(wrapper.text()).toContain("知识库还在建设中");
  });

  it("未登录：套闸门、不发请求（契约要求登录，游客不该看到 40100 的错误条）", async () => {
    const { wrapper } = await mountPage(KnowledgeView, "/knowledge", "anonymous");
    expect(listKnowledgeEntries).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("知识库需要登录后查看");
  });

  it("首页「快捷服务」里有知识库入口，且指向 /knowledge（不是孤儿页）", async () => {
    const { wrapper } = await mountPage(HomeView, "/", "authenticated");
    expect(wrapper.text()).toContain("知识库");
    expect(wrapper.find('a[href="/knowledge"]').exists()).toBe(true);
  });
});

describe("知识条目详情", () => {
  it("待复核的条目：正文之前必须说清「还没有经过合作兽医复核」，并带免责声明", async () => {
    const { wrapper } = await mountPage(KnowledgeEntryView, "/knowledge/K-0011");

    expect(getKnowledgeEntry).toHaveBeenCalledWith("K-0011", expect.anything());
    expect(wrapper.text()).toContain("还没有经过合作兽医复核");
    expect(wrapper.text()).toContain("待复核");
    expect(wrapper.text()).toContain("不能替代兽医诊断");
    // 红色风险必须建议就医（宁严勿松）
    expect(wrapper.text()).toContain("尽快就医");
    expect(wrapper.text()).toContain("公开兽医共识（2024）");
  });

  it("已复核的条目不显示「未复核」提示", async () => {
    getKnowledgeEntry.mockResolvedValue({ ...ENTRY_VETTED, body: "首免 6-8 周龄。" });
    const { wrapper } = await mountPage(KnowledgeEntryView, "/knowledge/K-0002");

    expect(wrapper.text()).toContain("已复核");
    expect(wrapper.text()).not.toContain("还没有经过合作兽医复核");
  });

  it("40400（不存在 / 已删除 / 不可读同码）：说「这条不在了」，不给重试", async () => {
    getKnowledgeEntry.mockRejectedValueOnce(apiFailure(40400, "知识条目不存在", "req-kb-404"));
    const { wrapper } = await mountPage(KnowledgeEntryView, "/knowledge/K-9999");

    expect(wrapper.text()).toContain("这条知识条目不在了");
    expect(wrapper.findAll("button").some((button) => button.text().includes("重新加载"))).toBe(false);
  });

  it("未登录：闸门态、不发请求（详情也要登录，游客不该看到 40100 的错误条）", async () => {
    const { wrapper } = await mountPage(KnowledgeEntryView, "/knowledge/K-0011", "anonymous");

    expect(getKnowledgeEntry).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("知识库需要登录后查看");
  });
});
