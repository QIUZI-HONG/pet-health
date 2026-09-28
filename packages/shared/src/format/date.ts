/**
 * 展示用的日期格式化（docs/conventions.md：日期展示 `YYYY年MM月DD日`，时间 `HH:mm`）。
 *
 * 为什么收在一处：接口给的是 `2026-09-27` 与 `2026-09-27 10:00:00` 这种可直接比较的格式，
 * 展示格式是另一回事。各页面自己写 `slice(0, 10)` 或 `replace(/-/g, 年)` 迟早会分叉出三四种写法。
 *
 * **接口里的格式不要改**——那是契约定的（能直接比较、能直接解析）；只在展示时转。
 */

function parse(value: string): { year: string; month: string; day: string; time: string } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}:\d{2}))?/.exec(value);
  if (!match) return null;
  return { year: match[1], month: match[2], day: match[3], time: match[4] ?? "" };
}

/** `2026-09-27` → `2026年09月27日`。解析不出来时原样返回，不吞掉信息。 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  const parts = parse(value);
  return parts ? `${parts.year}年${parts.month}月${parts.day}日` : value;
}

/** `2026-09-27 10:00:00` → `2026年09月27日 10:00`。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "";
  const parts = parse(value);
  if (!parts) return value;
  const date = `${parts.year}年${parts.month}月${parts.day}日`;
  return parts.time ? `${date} ${parts.time}` : date;
}

/**
 * 日期加减若干天，返回 `YYYY-MM-DD`。**只按年月日做算术，不经过本地时区**。
 *
 * 为什么不用 `new Date("2026-09-28T00:00:00")` 再 `toISOString()`：前者按**本地时区**构造，
 * 后者按 UTC 输出，东八区会整体少一天——首页「和昨天一样」会取到**前天**的值再按今天提交，
 * 打卡与评分一并写错（实测 `2026-09-28` → `2026-09-26`）。接口给的是可直接比较的字符串
 * （契约口径），按 UTC 做算术既准确又不带时区。
 */
export function shiftDate(date: string, days: number): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return date;
  const stamp = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return new Date(stamp + days * 86_400_000).toISOString().slice(0, 10);
}

/**
 * 今天的 `YYYY-MM-DD`（按**浏览器本地时区**取年月日再拼串）。
 *
 * 用途是给日期输入框当 `max`（生日、接种日期不得晚于今天）。这里必须用本地年月日：
 * 先转 UTC 再取日期的话，东八区当天 00:00–08:00 会得到「昨天」，把合法输入判成非法。
 */
export function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}
