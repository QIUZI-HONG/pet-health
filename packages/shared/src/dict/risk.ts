/**
 * 风险分级（`risk_level`）的**唯一判据**：什么算绿、什么算黄、什么算红。
 *
 * 为什么要有这个文件：三个页面各写过一次分档，写法还都不一样——
 * 有的写 `=== 3 / === 2 / 其余`，有的写 `!level || level <= 1`，于是 `0`、`null`、`undefined`
 * 这三种「没有分级」的输入在三个页面落到不同颜色。分档是领域规则（ADR-0021）而不是排版细节，
 * 应该只有一份：**分档在这里，配色/文案在用它的那一边**。
 *
 * 档位含义见 CONTEXT.md 的 RiskLevel：只说就医紧迫程度，**不说病名**。
 */
export type RiskTone = "green" | "yellow" | "red";

/**
 * 把接口给的分级码收敛成三档色调。
 *
 * `0 / null / undefined` 一律算绿：那是「没有风险信息」（例如业务通知）而不是「未知的危险」——
 * 给它上红色会让每一张业务通知都变成警报。
 */
export function riskTone(level: number | null | undefined): RiskTone {
  if (level === 3) return "red";
  if (level === 2) return "yellow";
  return "green";
}
