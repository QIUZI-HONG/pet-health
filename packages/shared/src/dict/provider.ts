/**
 * 服务者域的状态码字典：契约里那些整数码 → 界面上的一句话。
 *
 * 为什么必须收在一处：这些码（服务者状态 0/1/2/3、审核状态 0/1/2、资质类型 1–6…）在**十几个面板**
 * 里都要显示，各写一份 `switch` 就会出现同一状态在两个页面上叫法不同——运营看到「冻结」、
 * 服务者看到「禁用」，沟通成本从这里开始。
 *
 * 原先服务者后台与运营后台各有一份 **12 个函数逐字相同** 的拷贝，本文件就是那一份；
 * 两个后台各自 `utils/labels.ts` 只留自己独有的那部分（如服务者的「资质到期」一族、
 * 运营的「审核流水动作」）。
 *
 * 取值与文案以 `contract/provider.yaml` 的字段说明为准
 * （服务者 `status: 0 待审核 / 1 正常 / 2 驳回 / 3 冻结`、服务项 `0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回`、
 * 审核与提案 `status: 0 待审核 / 1 通过 / 2 驳回`）。
 */
import type { components } from "../api/provider";

/** 营业时段（契约 schema，前端不手写这个形状）。 */
type BusinessHour = components["schemas"]["BusinessHour"];

/** 标签的配色类名（packages/ui 的 console.css）。空串表示中性——用 `.ph-tag` 的默认样式。 */
export type TagClass = "" | "ph-tag--success" | "ph-tag--warning" | "ph-tag--danger" | "ph-tag--info";

const PROVIDER_TYPES: Record<number, string> = {
  1: "医院",
  2: "洗护",
  3: "训犬",
  4: "寄养上门",
  5: "食品用品",
  6: "间接服务",
};

const QUALIFICATION_TYPES: Record<number, string> = {
  1: "营业执照",
  2: "执业许可证",
  3: "法人身份证",
  4: "训犬师认证",
  5: "健康证",
  6: "其他",
};

/** 服务者类型：1 医院 / 2 洗护 / 3 训犬 / 4 寄养上门 / 5 食品用品 / 6 间接服务。 */
export function providerTypeLabel(type?: number | null): string {
  return (type != null && PROVIDER_TYPES[type]) || "—";
}

/** 资质材料类型：1 营业执照 / 2 执业许可证 / 3 法人身份证 / 4 训犬师认证 / 5 健康证 / 6 其他。 */
export function qualificationTypeLabel(type?: number | null): string {
  return (type != null && QUALIFICATION_TYPES[type]) || "—";
}

/** 服务者经营状态：0 待审核 / 1 正常 / 2 驳回 / 3 冻结。 */
export function providerStatusLabel(status?: number | null): string {
  switch (status) {
    case 0:
      return "待审核";
    case 1:
      return "正常";
    case 2:
      return "已驳回";
    case 3:
      return "已冻结";
    default:
      return "—";
  }
}

export function providerStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 1:
      return "ph-tag--success";
    case 0:
      return "ph-tag--warning";
    case 2:
    case 3:
      return "ph-tag--danger";
    default:
      return "";
  }
}

/** 审核状态（入驻申请、目录外提案共用）：0 待审核 / 1 通过 / 2 驳回。 */
export function reviewStatusLabel(status?: number | null): string {
  switch (status) {
    case 0:
      return "待审核";
    case 1:
      return "已通过";
    case 2:
      return "已驳回";
    default:
      return "—";
  }
}

export function reviewStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 1:
      return "ph-tag--success";
    case 0:
      return "ph-tag--warning";
    case 2:
      return "ph-tag--danger";
    default:
      return "";
  }
}

/** 服务项状态：0 待审核 / 1 已上架 / 2 已下架 / 3 已驳回。 */
export function serviceStatusLabel(status?: number | null): string {
  switch (status) {
    case 0:
      return "待审核";
    case 1:
      return "已上架";
    case 2:
      return "已下架";
    case 3:
      return "已驳回";
    default:
      return "—";
  }
}

export function serviceStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 1:
      return "ph-tag--success";
    case 0:
      return "ph-tag--warning";
    case 3:
      return "ph-tag--danger";
    default:
      return "";
  }
}

/** 资质材料自身的审核状态：0 待审 / 1 通过 / 2 驳回。 */
export function qualificationStatusLabel(status?: number | null): string {
  switch (status) {
    case 0:
      return "待审";
    case 1:
      return "已通过";
    case 2:
      return "已驳回";
    default:
      return "—";
  }
}

/** 适用宠物：1 犬 / 2 猫 / 3 犬猫（目录项字段）。 */
export function applicablePetsLabel(value?: number | null): string {
  switch (value) {
    case 1:
      return "犬";
    case 2:
      return "猫";
    case 3:
      return "犬猫";
    default:
      return "—";
  }
}

const DAY_NAMES = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

export function dayOfWeekLabel(day?: number | null): string {
  return (day != null && DAY_NAMES[day - 1]) || "—";
}

/** 营业时间的展示：`周一 09:00–19:00；周六 10:00–16:00`。数组里没有的星期几就是休息。 */
export function businessHoursText(hours?: BusinessHour[] | null): string {
  if (!hours || hours.length === 0) return "整周休息";
  return hours
    .map((hour) => `${dayOfWeekLabel(hour.day_of_week)} ${hour.open_time ?? ""}–${hour.close_time ?? ""}`.trim())
    .join("；");
}
