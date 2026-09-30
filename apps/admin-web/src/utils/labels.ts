/**
 * 运营后台**独有**的字典：状态码 → 界面文案，但只有这个后台会用到。
 *
 * 契约里通用的那部分（服务者类型/状态、审核状态、服务项状态、资质类型与状态、适用宠物、
 * 营业时间）已收在 `@pet-health/shared` 的 `dict/provider.ts` —— 服务者后台也要显示同一批码，
 * 各写一份会出现「运营看到冻结、服务者看到禁用」。本文件只留运营这边的两件：
 *
 *   - **审核流水的动作**（提交 / 重提 / 通过 / 驳回 / 上架 / 下架 / 冻结 / 解冻）：
 *     服务者看不到这条流水；
 *   - **材料过期与否的两态说法**：运营在审核时要一眼看出「这份材料已经过期了」，
 *     不需要服务者那种「30 天内到期」的提前量。
 *
 * 取值与文案以 `contract/admin.yaml` 的字段说明为准。
 */
import type { ProviderQualificationView } from "../api/adminApi";

/** 审核流水的动作：1 提交 / 2 重提 / 3 通过 / 4 驳回 / 5 上架 / 6 下架 / 7 冻结 / 8 解冻。 */
export function reviewActionLabel(action?: number | null): string {
  switch (action) {
    case 1:
      return "提交";
    case 2:
      return "重新提交";
    case 3:
      return "通过";
    case 4:
      return "驳回";
    case 5:
      return "上架";
    case 6:
      return "下架";
    case 7:
      return "冻结";
    case 8:
      return "解冻";
    default:
      return "—";
  }
}

/** 材料过期与否：审核时要能一眼看出「这份材料已经过期了」（过期不算有效资质）。 */
export function qualificationExpiryText(qualification: ProviderQualificationView, today: string): string {
  if (!qualification.valid_until) return "长期有效";
  return qualification.valid_until < today ? "已过期" : "有效";
}
