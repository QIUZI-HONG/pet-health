/**
 * 金额工具：**全程按字符串办，不碰浮点**。
 *
 * 契约里金额就是字符串（`"128.00"`，见 ADR-0011 与 docs/conventions.md 的「金额精度 decimal(10,2)、
 * 禁止浮点」），所以比较大小、判断是否在区间内也必须在整数域做：先把字符串解析成「分」
 * （整数），比完再转回去。用 `Number("128.00")` 做比较在两位小数上看似没事，但金额一旦参与
 * 累加/比较就会留下 0.1+0.2 那类尾差，而这类错误不会当场报错，只会在某个客户的账上出现。
 *
 * **为什么在 shared 而不是各端一份**：这段逻辑原先在服务者后台与运营后台各有一份逐字相同的拷贝
 * （88 行 × 2），改一处漏一处；而它的每一条规则都来自契约与后端值对象（{@link PriceRange}），
 * 不是某一端的偏好。C 端只展示金额、不参与区间比较，所以它另有 `utils/money.ts`
 * ——那边空值给 `¥0.00`（券面额处不能出现破折号），**两种口径不能合并**。
 */

/** 解析成「分」；格式不合法（负数、超过两位小数、非数字）返回 null。 */
export function parseAmountToCents(amount: string | undefined | null): number | null {
  if (amount === undefined || amount === null) return null;
  const text = amount.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null;
  const [yuan, fraction = ""] = text.split(".");
  const cents = fraction.padEnd(2, "0");
  // 用字符串拼接再取整：`Number(yuan) * 100` 对超大值会有精度问题，这里不需要那个上限
  return Number(`${yuan}${cents}`);
}

/** 分 → 两位小数字符串（不四舍五入，只能传已经合法的分值）。 */
export function centsToAmount(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(Math.trunc(cents));
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/** 展示用：补上 `¥` 与两位小数（已是字符串的金额原样兜底，不解析）。 */
export function formatAmount(amount: string | undefined | null): string {
  if (!amount) return "—";
  const cents = parseAmountToCents(amount);
  return cents === null ? `¥${amount}` : `¥${centsToAmount(cents)}`;
}

/** 展示用：`¥128.00–¥256.00`；任一端缺失时给「—」。 */
export function formatAmountRange(min: string | undefined | null, max: string | undefined | null): string {
  if (!min || !max) return "—";
  return `${formatAmount(min)}–${formatAmount(max)}`;
}

/**
 * 价格是否落在目录项给的区间内（闭区间）。
 *
 * 区间缺失时返回 `null`——**不确定**，不是「不在区间内」：目录项被删掉时服务项的
 * `price_min` / `price_max` 就是空的，那种情况该由后端判，前端不能替它说「越界」。
 */
export function isWithinRange(price: string, min: string | undefined | null, max: string | undefined | null): boolean | null {
  const priceCents = parseAmountToCents(price);
  const minCents = parseAmountToCents(min);
  const maxCents = parseAmountToCents(max);
  if (priceCents === null || minCents === null || maxCents === null) return null;
  return priceCents >= minCents && priceCents <= maxCents;
}

/** 价格格式校验：正数、最多两位小数（契约：服务者上架价必须大于 0，免费体验用券表达）。 */
export function isPriceFormatValid(price: string): boolean {
  const cents = parseAmountToCents(price);
  return cents !== null && cents > 0;
}

/** decimal(10,2) 的上限（99999999.99 元 = 9999999999 分），与后端 `Price.MAX` 一致。 */
const MAX_CENTS = 9_999_999_999;

/**
 * 区间边界是否合法：非负、最多两位小数、不超过 decimal(10,2)。
 *
 * **0 是合法的边界**：后端区间边界用的 `Price.parse` 允许 0，`0.00–50.00` 的试吃装是合法区间
 * （下限为 0 表示「从免费起」）。用 {@link isPriceFormatValid} 判边界会把这种区间挡在提交之前，
 * 运营会以为是自己填错了。
 */
export function isRangeBoundValid(amount: string): boolean {
  const text = amount.trim();
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(text)) return false;
  const cents = parseAmountToCents(text);
  return cents !== null && cents <= MAX_CENTS;
}

/**
 * 越界提示：**与后端 `PriceRange.outOfRangeMessage()` 逐字一致**——
 * `价格须在¥88.00-¥260.00之间`（ADR-0034 决定 5：服务端与前端用同一句文案）。
 *
 * 前端这一层只是让用户少等一个往返（后端 90001 仍会再判一次）。两边各拼一遍的话，
 * 同一个规则会长出两种说法（空格、连字符不同），用户会以为是两条不同的限制。
 */
export function priceRangeMessage(min: string | undefined | null, max: string | undefined | null): string {
  if (!min || !max) return "价格不在平台给定的区间内";
  return `价格须在${formatAmount(min)}-${formatAmount(max)}之间`;
}
