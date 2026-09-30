/**
 * 金额展示（docs/conventions.md：金额两位小数、展示带 `¥`；ADR-0011：禁止浮点）。
 *
 * 这一层刻意**只做字符串处理**：契约里的金额是 `"128.00"` 这样的字符串，
 * 一解析就进了浮点，而浮点在金额上错得很安静（不报错，只是差一分钱）。
 * 所以这里既验展示格式，也验「不认得的输入不会被算成数字」。
 */
import { describe, expect, it } from "vitest";
import { formatAmount, thresholdText } from "../utils/money";

describe("formatAmount：只加前缀与补零，不解析", () => {
  it("两位小数的契约值原样展示", () => {
    expect(formatAmount("128.00")).toBe("¥128.00");
    expect(formatAmount("0.00")).toBe("¥0.00");
  });

  it("少一位小数就补零（展示口径要一致）", () => {
    expect(formatAmount("128.5")).toBe("¥128.50");
    expect(formatAmount("128")).toBe("¥128.00");
  });

  it("空值给 ¥0.00，不显示 undefined", () => {
    expect(formatAmount(null)).toBe("¥0.00");
    expect(formatAmount(undefined)).toBe("¥0.00");
    expect(formatAmount("")).toBe("¥0.00");
  });

  it("对不上契约口径的串原样带前缀展示：宁可难看，也不显示一个算出来的数", () => {
    expect(formatAmount("1,280")).toBe("¥1,280");
    expect(formatAmount("待定")).toBe("¥待定");
    expect(formatAmount("-20.00")).toBe("¥-20.00");
  });
});

describe("thresholdText：门槛的三种说法", () => {
  it("0.00 是「无门槛」（契约只给这一种写法）", () => {
    expect(thresholdText("0.00")).toBe("无门槛");
    expect(thresholdText("0")).toBe("无门槛");
    expect(thresholdText("")).toBe("无门槛");
  });

  it("有门槛时说「满 ¥X 可用」", () => {
    expect(thresholdText("100.00")).toBe("满 ¥100.00 可用");
  });
});
