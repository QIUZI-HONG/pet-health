/**
 * 评价的展示口径（订单详情页与门店详情页共用一份）。
 *
 * 为什么单独一个文件：**星星与分数是同一个数的两种画法**，两页都要用。各写一份就会出现
 * 「详情页显示 4 星、门店页显示 5 星」这种自相矛盾——而它们读的是同一条评价。
 */

/** 五颗星的画法：填满 rating 颗，其余空心。越界的值收口到 0–5，不画出一串奇怪的符号。 */
export function reviewStars(rating: number | null | undefined): string {
  if (rating === null || rating === undefined || !Number.isFinite(rating)) {
    return "☆☆☆☆☆";
  }
  const filled = Math.min(5, Math.max(0, Math.round(rating)));
  return "★".repeat(filled) + "☆".repeat(5 - filled);
}

/** 分数的说法：整数就是「4 分」，带小数的聚合分保留一位（平均分是聚合值，会带小数）。 */
export function reviewRatingText(rating: number | string | null | undefined): string {
  if (rating === null || rating === undefined || rating === "") {
    return "暂无评分";
  }
  const value = typeof rating === "string" ? Number(rating) : rating;
  if (!Number.isFinite(value)) {
    return "暂无评分";
  }
  return `${Number.isInteger(value) ? value : value.toFixed(1)} 分`;
}

/**
 * 只打分没写字的评价怎么显示。
 *
 * 契约里 `content` 为 `null` 是**合法**的（评分必填、文字可选），所以界面不许把它显示成空白块
 * ——用户会以为页面出了问题。这里给一句中性的说明。
 */
export function reviewContentText(content: string | null | undefined): string {
  return content && content.trim().length > 0 ? content : "（只打了分，没有留言）";
}
