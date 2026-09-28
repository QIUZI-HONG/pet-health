/**
 * 日期工具的测试（`packages/shared/src/format/date.ts`）。
 *
 * 守的是一个会写脏业务数据的缺陷：首页「和昨天一样」原先用
 * `new Date("2026-09-28T00:00:00")` + `toISOString()` 算昨天——前者按本地时区构造、后者按 UTC 输出，
 * 在东八区（UTC+8）会**整体少一天**，于是复制的是**前天**的值，再按今天提交，打卡与评分一起错。
 *
 * `shiftDate` 只按年月日做算术，因此在任何时区下都是同一个结果；下面这些断言在 TZ=Asia/Shanghai
 * 与 TZ=UTC 下都必须通过（用旧实现时前者会红）。
 */
import { describe, expect, it } from "vitest";
import { createLatestGuard, shiftDate, todayIso } from "@pet-health/shared";

describe("日期工具", () => {
  it("shiftDate 只按年月日算，不受本机时区影响", () => {
    expect(shiftDate("2026-09-28", -1)).toBe("2026-09-27");
    expect(shiftDate("2026-10-01", -1)).toBe("2026-09-30"); // 跨月
    expect(shiftDate("2027-01-01", -1)).toBe("2026-12-31"); // 跨年
    expect(shiftDate("2026-03-01", -1)).toBe("2026-02-28"); // 平年二月
    expect(shiftDate("2024-03-01", -1)).toBe("2024-02-29"); // 闰年二月
    expect(shiftDate("2026-09-28", 7)).toBe("2026-10-05");
  });

  it("不是 YYYY-MM-DD 的输入原样返回：不抛错，也不替调用方猜", () => {
    expect(shiftDate("", -1)).toBe("");
    expect(shiftDate("2026-9-1", -1)).toBe("2026-9-1");
    expect(shiftDate("2026-09-28 10:00:00", -1)).toBe("2026-09-28 10:00:00");
  });

  it("todayIso 取的是本地年月日（UTC 转换会让东八区凌晨少一天）", () => {
    const now = new Date();
    const month = String(now.getMonth() + 1).padStart(2, "0");
    const day = String(now.getDate()).padStart(2, "0");
    expect(todayIso()).toBe(`${now.getFullYear()}-${month}-${day}`);
  });
});

/**
 * 「只认最新响应」的守卫（测试报告 D16）。
 *
 * 真实场景：切宠物/切筛选时先发的请求后回来，把新数据盖成旧的——首页那种地方会
 * 让用户看到**另一只宠物**的健康数据。这里把一个「乱序返回」的时序钉住：
 * 守卫只放行最后一次加载的结果。
 */
describe("并发守卫", () => {
  it("先发的请求后回来时，它的结果要被丢弃", () => {
    const latest = createLatestGuard();

    const first = latest.claim();     // 切到宠物 A
    const second = latest.claim();    // 立刻又切到宠物 B

    expect(latest.isCurrent(first)).toBe(false);   // A 的响应回来了：丢掉
    expect(latest.isCurrent(second)).toBe(true);   // B 的响应回来了：落地
  });

  it("只有一次加载时结果照常落地（别把正常路径也拦掉）", () => {
    const latest = createLatestGuard();
    const only = latest.claim();
    expect(latest.isCurrent(only)).toBe(true);
  });
});
