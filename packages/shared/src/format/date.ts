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
