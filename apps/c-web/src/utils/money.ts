/**
 * 金额展示：契约里的金额是**字符串两位小数**（`"128.00"`，ADR-0011 / docs/conventions.md），
 * 所以这里全程按字符串办，**不解析成数字**——一解析就进了浮点，`0.1 + 0.2` 那类尾差不会当场
 * 报错，只会在某个用户的券抵扣上差一分钱。
 *
 * 与两个后台的 `utils/money.ts` 同形：那边要参与区间比较，所以多了「分」的解析；
 * C 端只做展示（比较与计算都在服务端），所以这里只留一个格式化函数。
 */

/** 契约口径的金额：非负、最多两位小数。 */
const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/**
 * `"108"` → `"¥108.00"`；带 `¥` 前缀与两位小数（docs/conventions.md：金额展示带 ¥）。
 *
 * 空值给 `¥0.00`；对不上契约口径的串**原样带前缀展示**，不猜、也不算——
 * 金额宁可显示得难看，也不能显示一个我们算出来的数。
 */
export function formatAmount(amount: string | null | undefined): string {
  const text = (amount ?? "").trim();
  if (text.length === 0) return "¥0.00";
  if (!AMOUNT_PATTERN.test(text)) return `¥${text}`;
  const [yuan, fraction = ""] = text.split(".");
  return `¥${yuan}.${fraction.padEnd(2, "0")}`;
}

/**
 * 「满 ¥100.00 可用 / 无门槛」。
 *
 * 门槛也是两位小数字符串，`"0.00"` 表示无门槛（契约），所以这里是**字符串相等**判断而不是
 * 数值比较——不需要知道 0 的多种写法，因为契约只给这一种。
 */
export function thresholdText(minAmount: string | null | undefined): string {
  const text = (minAmount ?? "").trim();
  if (text === "" || text === "0.00" || text === "0" || text === "0.0") return "无门槛";
  return `满 ${formatAmount(text)} 可用`;
}
