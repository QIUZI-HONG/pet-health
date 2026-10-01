/**
 * 首页的三块视觉对齐（交付文档 4.16.2 的第 3、6、7 块）的组件测试。
 *
 * 这几块的性质是「把服务端已经有的事实摆到首页上」，所以测试盯的是**口径**而不是像素：
 *   - **分类 pill**：标签按服务端的 `MessageView.type` 给（色条说多紧急、pill 说哪一类）；
 *     认不出的类型要如实显示「提醒」而不是藏起来——提醒是要做事的东西；
 *   - **券提醒条**：张数按服务端的券包算、天数按**最近到期**那张算，只说实话；
 *   - **邀请入口条**：进度用服务端给的有效邀请数与下一档剩余，前端不算。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MessageView } from "@pet-health/shared";
import type { CouponView } from "../api/commerce";
import { mountPage } from "./support";
import HomeView from "../views/HomeView.vue";

const getHealthScore = vi.fn();
const getCheckInDay = vi.fn();
const getCheckInStreak = vi.fn();
const getMessageHighlights = vi.fn();
const getUnreadCount = vi.fn();
const listCoupons = vi.fn();
const getInviteCenter = vi.fn();

vi.mock("@pet-health/shared", async () => {
  const actual = await vi.importActual<typeof import("@pet-health/shared")>("@pet-health/shared");
  return {
    ...actual,
    cApp: {
      getHealthScore: (...args: unknown[]) => getHealthScore(...args),
      getCheckInDay: (...args: unknown[]) => getCheckInDay(...args),
      getCheckInStreak: (...args: unknown[]) => getCheckInStreak(...args),
      getMessageHighlights: (...args: unknown[]) => getMessageHighlights(...args),
      getUnreadCount: (...args: unknown[]) => getUnreadCount(...args),
    },
  };
});

vi.mock("../api/commerce", () => ({
  commerce: {
    listCoupons: (...args: unknown[]) => listCoupons(...args),
    getInviteCenter: (...args: unknown[]) => getInviteCenter(...args),
  },
}));

/** 一条「异常」提醒：pill 该显示「异常」并走 danger 色调。 */
const REMINDER_ABNORMAL: MessageView = {
  id: 1,
  kind: 1,
  type: 4,
  title: "饮水量比上周 +30%",
  content: "建议继续观察，必要时找医院。",
  risk_level: 3,
  remind_at: "2026-10-01 08:00:00",
  read: false,
  action_hint: "去记录",
  action_target: "/records",
  pet_id: 1,
  created_at: "2026-10-01 08:00:00",
} as MessageView;

/** 一条契约里**没有**的类型（11）：认不出时也不能藏起来。 */
const REMINDER_UNKNOWN: MessageView = { ...REMINDER_ABNORMAL, id: 2, type: 11, title: "新类型的提醒" };

function coupon(id: number, name: string, daysFromNow: number): CouponView {
  const until = new Date(Date.now() + daysFromNow * 86_400_000).toISOString().slice(0, 19).replace("T", " ");
  return {
    id,
    code: `C-${id}`,
    provider_id: null,
    provider_name: null,
    template_name: name,
    face_value: "20.00",
    min_amount: "0.00",
    source: 4,
    status: 1,
    valid_from: "2026-09-01 00:00:00",
    valid_until: until,
  } as CouponView;
}

beforeEach(() => {
  vi.clearAllMocks();
  getHealthScore.mockResolvedValue({ total_score: null, dimensions: [], trend: [] });
  getCheckInDay.mockResolvedValue({ items: [], checked_in: false });
  getCheckInStreak.mockResolvedValue({ streak_days: 0, checked_today: false });
  getMessageHighlights.mockResolvedValue([REMINDER_ABNORMAL, REMINDER_UNKNOWN]);
  getUnreadCount.mockResolvedValue({ unread: 2, unread_reminders: 2 });
  listCoupons.mockResolvedValue({ list: [], page: 1, page_size: 100, total: 0, has_more: false });
  getInviteCenter.mockResolvedValue({ effective_count: 0, next_threshold: null, next_remaining: null });
});

describe("首页：提醒卡的分类 pill", () => {
  it("按服务端的 type 给标签（异常），认不出的类型如实显示「提醒」而不是藏起来", async () => {
    const { wrapper } = await mountPage(HomeView, "/", "authenticated");

    const pills = wrapper.findAll(".ph-reminders__pill");
    expect(pills.map((pill) => pill.text())).toEqual(["异常", "提醒"]);
    expect(pills[0]!.classes()).toContain("ph-reminders__pill--danger");
    expect(wrapper.text()).toContain("饮水量比上周 +30%");
    expect(wrapper.text()).toContain("新类型的提醒");
  });
});

describe("首页：券提醒条", () => {
  it("有券才显示，张数与「最近到期」的天数都按服务端数据算", async () => {
    listCoupons.mockResolvedValue({
      list: [coupon(1, "洗护券 20 元", 7), coupon(2, "体检券", 30)],
      page: 1,
      page_size: 100,
      total: 2,
      has_more: false,
    });

    const { wrapper } = await mountPage(HomeView, "/", "authenticated");

    expect(wrapper.text()).toContain("您有 2 张券");
    expect(wrapper.text()).toContain("洗护券 20 元 7 天后过期");
    expect(wrapper.find('a[href="/coupons"]').exists()).toBe(true);
  });

  it("没有券就不显示那一块（首页不编一个「0 张券」的格子）", async () => {
    const { wrapper } = await mountPage(HomeView, "/", "authenticated");

    expect(wrapper.text()).not.toContain("您有 0 张券");
    expect(wrapper.find('a[href="/coupons"]').exists()).toBe(false);
  });
});

describe("首页：邀请入口条", () => {
  it("进度用服务端的有效邀请数与下一档剩余，前端不算", async () => {
    getInviteCenter.mockResolvedValue({ effective_count: 2, next_threshold: 3, next_remaining: 1 });

    const { wrapper } = await mountPage(HomeView, "/", "authenticated");

    expect(wrapper.text()).toContain("已有效邀请 2 人");
    expect(wrapper.text()).toContain("再邀 1 人到 3 人档");
    expect(wrapper.find('a[href="/invites"]').exists()).toBe(true);
  });

  it("未登录时不发这两个请求（首页对游客不显示券与邀请）", async () => {
    await mountPage(HomeView, "/", "anonymous");

    expect(listCoupons).not.toHaveBeenCalled();
    expect(getInviteCenter).not.toHaveBeenCalled();
  });
});
