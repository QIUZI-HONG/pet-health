/**
 * 打卡补录（F005 的第二半）的测试。
 *
 * 分两层，钉住的是**规则**：
 *  1. 组件层（`CheckInCard`）：日期选择器只放行可补录窗口（今天 + 过去 7 天）。窗口外的日期
 *     **不发请求**、给出提示、把输入框拨回去——后端也会拒（40001），但让用户提交完才被拒是白跑一趟；
 *  2. 页面层（`HomeView`）：换日期真的按那一天拉打卡状态，提交也带那一天（否则「补录」等于没做）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import type { CheckInDay } from "@pet-health/shared";
import CheckInCard from "../components/CheckInCard.vue";
import HomeView from "../views/HomeView.vue";
import { mountPage } from "./support";

const getHealthScore = vi.fn();
const getCheckInDay = vi.fn();
const getCheckInStreak = vi.fn();
const getMessageHighlights = vi.fn();
const getUnreadCount = vi.fn();
const submitCheckIn = vi.fn();

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
      submitCheckIn: (...args: unknown[]) => submitCheckIn(...args),
    },
  };
});

const NAMES = ["体重", "饮食", "排泄", "行为", "情绪", "卫生"];

function makeDay(overrides: Partial<CheckInDay> = {}): CheckInDay {
  return {
    date: "2026-09-30",
    items: NAMES.map((name, index) => ({
      category: (index + 1) as 1 | 2 | 3 | 4 | 5 | 6,
      name,
      filled: false,
      abnormal: false,
      value: undefined,
      note: undefined,
      backfilled: false,
    })),
    completed_count: 0,
    total_count: 6,
    done: false,
    backfilled: false,
    ...overrides,
  } as CheckInDay;
}

/** 窗口：今天 2026-09-30，最早 2026-09-23（与后端 BACKFILL_WINDOW_DAYS=7 同一口径）。 */
const WINDOW = { minDate: "2026-09-23", maxDate: "2026-09-30" };

async function pickDate(wrapper: ReturnType<typeof mount>, value: string): Promise<void> {
  const input = wrapper.find('input[aria-label="打卡日期"]');
  (input.element as HTMLInputElement).value = value;
  await input.trigger("change");
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  getHealthScore.mockResolvedValue({ total_score: null, dimensions: [], trend: [] });
  // 服务端按传进来的日期返回那一天（`date` 省略时给业务日期「今天」）——与真实接口同一形状
  getCheckInDay.mockImplementation((_petId: number, date?: string) =>
    Promise.resolve(makeDay({ date: date ?? "2026-09-30", backfilled: Boolean(date && date !== "2026-09-30") })),
  );
  getCheckInStreak.mockResolvedValue({ streak_days: 3, checked_today: false });
  getMessageHighlights.mockResolvedValue([]);
  getUnreadCount.mockResolvedValue({ unread: 0, unread_reminders: 0 });
  submitCheckIn.mockResolvedValue(makeDay({ backfilled: true }));
});

describe("打卡卡：补录窗口", () => {
  it("窗口内的日期：发一次 changeDate（页面据此重拉那一天）", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0, ...WINDOW } });

    await pickDate(wrapper, "2026-09-28");

    expect(wrapper.emitted("changeDate")?.[0]).toEqual(["2026-09-28"]);
    expect(wrapper.text()).not.toContain("只能补录");
  });

  it("早于窗口：拦下来不发请求，并说明只能补最近 7 天", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0, ...WINDOW } });

    await pickDate(wrapper, "2026-09-01");

    expect(wrapper.emitted("changeDate")).toBeUndefined();
    expect(wrapper.text()).toContain("只能补录最近 7 天");
    // 输入框要拨回当前这一天：停在一个不会被后端接受的日期上会误导用户
    expect((wrapper.find('input[aria-label="打卡日期"]').element as HTMLInputElement).value).toBe("2026-09-30");
  });

  it("将来的日期：同样拦下来（不能给将来打卡）", async () => {
    const wrapper = mount(CheckInCard, { props: { day: makeDay(), streakDays: 0, ...WINDOW } });

    await pickDate(wrapper, "2026-10-05");

    expect(wrapper.emitted("changeDate")).toBeUndefined();
    expect(wrapper.text()).toContain("不能给将来的日期打卡");
  });

  it("补录别的日期时：说清在看哪一天，「和昨天一样」改成「和前一天一样」", async () => {
    const day = makeDay({ date: "2026-09-28", backfilled: true });
    const wrapper = mount(CheckInCard, {
      props: { day, streakDays: 0, yesterdayValues: { 1: { value: "12.50" } }, ...WINDOW },
    });

    expect(wrapper.text()).toContain("正在补录");
    expect(wrapper.text()).toContain("和前一天一样");
    expect(wrapper.text()).toContain("回到今天");
  });

  it("看今天时：不出现补录提示，按钮还是「和昨天一样」", async () => {
    const wrapper = mount(CheckInCard, {
      props: { day: makeDay(), streakDays: 0, yesterdayValues: { 1: { value: "12.50" } }, ...WINDOW },
    });

    expect(wrapper.text()).not.toContain("正在补录");
    expect(wrapper.text()).toContain("和昨天一样");
  });
});

describe("首页：补录接线", () => {
  it("换日期 → 按那一天拉打卡状态；提交也带那一天", async () => {
    const { wrapper } = await mountPage(HomeView, "/", "authenticated");
    // 首屏：不带 date（由服务端按业务时区定今天）
    expect(getCheckInDay).toHaveBeenCalledWith(7, undefined, expect.anything());

    await pickDate(wrapper, "2026-09-28");

    // 按选中的那一天重拉（之后还会拉一次「前一天」给「和前一天一样」用，所以断言用 CalledWith）
    expect(getCheckInDay).toHaveBeenCalledWith(7, "2026-09-28", expect.anything());
    expect(wrapper.text()).toContain("正在补录");

    const submitAll = wrapper.findAll("button").find((button) => button.text() === "全部正常");
    await submitAll?.trigger("click");
    await flushPromises();

    expect(submitCheckIn).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ date: "2026-09-28" }),
    );
  });
});
