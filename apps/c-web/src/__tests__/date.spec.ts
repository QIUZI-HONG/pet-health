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
import { shiftDate, todayIso } from "@pet-health/shared";

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
