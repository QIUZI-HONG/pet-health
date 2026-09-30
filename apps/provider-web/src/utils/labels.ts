/**
 * 服务者后台**独有**的字典：状态码 → 界面文案，但只有这个后台会用到。
 *
 * 契约里通用的那部分（服务者类型/状态、审核状态、服务项状态、资质类型与状态、适用宠物、
 * 营业时间）已收在 `@pet-health/shared` 的 `dict/provider.ts` —— 运营后台也要显示同一批码，
 * 各写一份会出现「运营看到冻结、服务者看到禁用」。本文件只留服务者这边的几族：
 *
 *   - **门店分类**（直接同业 / 直接异业 / 间接异业）：只有服务者自己看自己的分类；
 *   - **资质到期**（{@link expiryState} 一族）：服务者要提前 30 天知道材料快过期了，
 *     运营侧看的是「有效 / 已过期」两态（见运营后台的 `qualificationExpiryText`）；
 *   - **交易与券**（订单状态、取消申请、券与券贡献、考核等级）：接订单页那一波（2026-09-30）
 *     才出现的一批码，目前只有服务者后台在显示。**运营后台也要显示订单状态时，就该把它搬进
 *     `@pet-health/shared` 的 `dict/provider.ts`**（与上面那几族同一处），别在两端各留一份。
 *
 * 取值与文案以 `contract/provider.yaml` 的字段说明为准。
 */
import type { CouponContributionView, ProviderQualificationView } from "../api/providerApi";
import { centsToAmount, parseAmountToCents, shiftDate, todayIso, type TagClass,
  couponSourceLabel as sharedCouponSourceLabel, couponStatusLabel as sharedCouponStatusLabel } from "@pet-health/shared";

const PROVIDER_CATEGORIES: Record<number, string> = {
  1: "直接同业",
  2: "直接异业",
  3: "间接异业",
};

/** 门店分类：1 直接同业 / 2 直接异业 / 3 间接异业。 */
export function providerCategoryLabel(category?: number | null): string {
  return (category != null && PROVIDER_CATEGORIES[category]) || "—";
}

/** 资质到期提示：`已过期` / `30 天内到期` / `长期有效`。 */
export type ExpiryState = "none" | "expired" | "expiring" | "valid";

/**
 * 到期判定用「今天 + 30 天」的字符串比较（`YYYY-MM-DD` 可直接比大小，见 shared/format/date.ts）：
 * 一旦转成 Date 就会引入时区，东八区早上会整体差一天。
 */
export function expiryState(qualification: ProviderQualificationView): ExpiryState {
  if (!qualification.valid_until) return "none";
  const today = todayIso();
  if (qualification.valid_until < today) return "expired";
  return qualification.valid_until <= shiftDate(today, 30) ? "expiring" : "valid";
}

export function expiryText(qualification: ProviderQualificationView): string {
  switch (expiryState(qualification)) {
    case "expired":
      return "已过期";
    case "expiring":
      return "30 天内到期";
    case "valid":
      return "有效期内";
    default:
      return "长期有效";
  }
}

export function expiryTone(qualification: ProviderQualificationView): TagClass {
  switch (expiryState(qualification)) {
    case "expired":
      return "ph-tag--danger";
    case "expiring":
      return "ph-tag--warning";
    case "valid":
      return "ph-tag--success";
    default:
      return "";
  }
}

/** 这份材料算不算「有效资质」：**被驳回的不算**（运营已经否了它），过期的也不算——与后端
 *  `ProviderAccess.hasValidQualification` 同一口径。 */
export function countsAsValid(qualification: ProviderQualificationView): boolean {
  return qualification.status !== 2 && expiryState(qualification) !== "expired";
}

/** 一份有效材料都没有：此时不能新增选品、已上架的服务项也会被定时任务下架（40300），页面要提前说。 */
export function hasNoValidQualification(qualifications: ProviderQualificationView[] | null | undefined): boolean {
  if (!qualifications || qualifications.length === 0) return true;
  return !qualifications.some(countsAsValid);
}

// ---------------------------------------------------------------------------
// 订单与履约（contract/provider.yaml 的 order 一组；状态机决策见 ADR-0038 第一节 / ADR-0048）
//
// 「能不能点某个按钮」的判据全部收在这里，顺序与服务端的条件更新一致：
// 接单 `WHERE status = 0`、核销 `WHERE status = 1`、取消 `WHERE status IN (0,1)`。
// 页面按它禁用按钮并写明原因——让用户点了才吃一个 40900，还得自己猜为什么。
// ---------------------------------------------------------------------------

const ORDER_STATUS: Record<number, string> = {
  0: "待接单",
  1: "已预约",
  2: "履约中",
  3: "已完成",
  4: "已取消",
};

/** 订单状态：0 待接单 / 1 已预约 / 2 履约中 / 3 已完成 / 4 已取消。 */
export function orderStatusLabel(status?: number | null): string {
  return (status != null && ORDER_STATUS[status]) || "—";
}

export function orderStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 3:
      return "ph-tag--success";
    case 0:
      return "ph-tag--warning";
    case 2:
      return "ph-tag--info";
    case 4:
      return "ph-tag--danger";
    default:
      return "";
  }
}

/** 接单：只有「待接单」能接（接单即承诺，之后用户取消要门店同意）。 */
export function canAcceptOrder(status?: number | null): boolean {
  return status === 0;
}

/** 核销：只有「已预约」能核销。**重复核销是 40900 不是幂等成功**（ADR-0038 第二节）。 */
export function canRedeemOrder(status?: number | null): boolean {
  return status === 1;
}

/** 门店取消：`0 / 1` 可以，**履约中（2）不可取消**（只能报工或由运营干预），终态也不行。 */
export function canCancelOrder(status?: number | null): boolean {
  return status === 0 || status === 1;
}

/** 不能取消的原因（写在按钮旁边，省掉一次注定失败的提交）。 */
export function cancelOrderHint(status?: number | null): string {
  switch (status) {
    case 2:
      return "履约中：不可取消，只能报工完成（或联系运营）";
    case 3:
      return "已完成：终态";
    case 4:
      return "已取消";
    default:
      return "";
  }
}

/** 取消申请：1 待门店处理 / 2 已同意 / 3 已拒绝；为空表示没有申请。 */
export function cancelRequestLabel(status?: number | null): string {
  switch (status) {
    case 1:
      return "用户申请取消：待处理";
    case 2:
      return "已同意取消";
    case 3:
      return "已拒绝取消";
    default:
      return "";
  }
}

/** 这一行的取消申请**要门店行动**（契约原话：「1 是要门店行动的」）——同意 / 拒绝两个动作的入口条件。 */
export function needsCancelDecision(status?: number | null): boolean {
  return status === 1;
}

/**
 * 照片槽位值 1 / 2 / 3 → 槽位名。
 *
 * <p>**只是兜底**：槽位名以服务端给的 `photo_wall.slots[].slot_name` 为准（ADR-0040 第四节：
 * 三道墙的名字是产品口径，不由前端定）。这里那份只在「服务端还没把这一道下发下来」
 * 时用一次——例如把 `missing_slots` 的码翻译成人话，而它对应的槽位正好不在 `slots` 里。
 */
const PHOTO_SLOT_NAMES: Record<number, string> = { 1: "接宠检查", 2: "服务防护", 3: "取宠对比" };

/** 槽位码 → 名字（先查服务端给的 `slot_name`，查不到才用本地那份）。 */
export function photoSlotName(slot: number, serverName?: string | null): string {
  return serverName || PHOTO_SLOT_NAMES[slot] || `第 ${slot} 道`;
}

/**
 * 40901 的前端文案（contract/common.yaml 的错误码表：「已终结不可改（报工后固化、修正须走运营干预）」）。
 *
 * <p>为什么要它：照片槽位是**报工后固化**的（ADR-0049 §一），而页面上的订单状态可能是几分钟前
 * 拉的——同事先报了工，这边点「保存本道」就会撞上 40901。后端那句 message 说的是同一条规则，
 * 但「怎么办」在码表里：**只有运营干预这一条路**，所以这里把出路写明，别让门店去试第二次。
 */
export function finalizedHint(message: string): string {
  return `${message}（40901 已终结不可改：照片与备注在报工后固化，修正只能走运营干预）`;
}

/** 这一行的照片墙入口什么时候给：**履约中**可写，**已完成**只读回看（固化后不再有写入口）。 */
export function canOpenWall(status?: number | null): boolean {
  return status === 2 || status === 3;
}

// ---------------------------------------------------------------------------
// 券与券贡献（contract/provider.yaml 的 coupon 一组；决策见 ADR-0037 第三节 / ADR-0044）
//
// 「券的钱语义」在这里只是文案，不影响任何计算：券是到店抵扣凭证，没有资金流（ADR-0036）。
// ---------------------------------------------------------------------------

/** 券贡献状态：1 生效中 / 2 已停止发放。 */
export function contributionStatusLabel(status?: number | null): string {
  switch (status) {
    case 1:
      return "生效中";
    case 2:
      return "已停止发放";
    default:
      return "—";
  }
}

export function contributionStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 1:
      return "ph-tag--success";
    case 2:
      return "ph-tag--warning";
    default:
      return "";
  }
}

/** 额度流水动作：1 承诺 / 2 调整额度 / 3 撤回 / 4 过期释放。 */
export function contributionLogActionLabel(action?: number | null): string {
  switch (action) {
    case 1:
      return "承诺";
    case 2:
      return "调整额度";
    case 3:
      return "撤回";
    case 4:
      return "过期释放";
    default:
      return "—";
  }
}

/**
 * 调整额度的**下限**：已核销 + 占用中（契约：调不下去的部分不能收回——已经发到用户手里的券
 * 不能被服务者反悔作废）。表单用它做前置校验，后端仍会再判一次（40001）。
 */
export function contributionFloor(row: CouponContributionView): number {
  return (row.redeemed_count ?? 0) + (row.reserved_count ?? 0);
}

/**
 * 对账恒等式：`issued_count = redeemed_count + reserved_count + expired_count`（契约给的恒等式）。
 *
 * 返回 `null` 表示后端缺字段（判不了），不是「平衡」——把「不知道」和「对上了」分成两种结果，
 * 界面上才敢按它显示「已核对 / 对不上 / 数据不全」。
 */
export function contributionBalanced(row: CouponContributionView): boolean | null {
  const issued = row.issued_count;
  const parts = [row.redeemed_count, row.reserved_count, row.expired_count];
  if (issued === undefined || parts.some((part) => part === undefined)) return null;
  return issued === (parts[0] ?? 0) + (parts[1] ?? 0) + (parts[2] ?? 0);
}

/**
 * 券状态：1 待使用 / 2 已锁定（下单占用）/ 3 已核销 / 4 已过期。
 *
 * 字典本体在 `@pet-health/shared` 的 `dict/coupon.ts`（三个端共用一份）；这里只把本端的
 * 默认回退固定成「—」——表格单元格里要干净，不写「未知状态」那种整句。
 */
export function couponStatusLabel(status?: number | null): string {
  return sharedCouponStatusLabel(status);
}

export function couponStatusTone(status?: number | null): TagClass {
  switch (status) {
    case 3:
      return "ph-tag--success";
    case 2:
      return "ph-tag--info";
    case 4:
      return "ph-tag--warning";
    default:
      return "";
  }
}

/**
 * 完成率（`completion_rate`）的展示：契约给的是**两位小数的比率字符串**（0.00–1.00），
 * 页面要的是百分数（「60%」），所以这里换算一次。
 *
 * 换算**不经过浮点**（`0.60 * 100` 那类乘法的尾差会让对账页出现 98.99999%）：按小数位做整数运算。
 * 形状不认识时**原样返回**——不猜，也不补 0（后端换了口径的话，这里多出的那句中文会比一个错的百分数好查）。
 */
export function completionRateText(rate?: string | null): string {
  if (!rate) return "—";
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(rate.trim());
  if (!match) return rate;
  const tenths = Number(match[1]) * 1000 + Number((match[2] ?? "").padEnd(2, "0")) * 10;
  return tenths % 10 === 0 ? `${tenths / 10}%` : `${Math.floor(tenths / 10)}.${tenths % 10}%`;
}

/** 券来源：1 邀请 / 2 打卡任务 / 3 积分兑换 / 4 平台补贴 / 5 月度阶梯（字典在 shared）。 */
export function couponSourceLabel(source?: number | null): string {
  return sharedCouponSourceLabel(source);
}

// ---------------------------------------------------------------------------
// 月度考核（F022；决策见 ADR-0039 第三节 / ADR-0050 第四节 / ADR-0052）
// ---------------------------------------------------------------------------

/**
 * 等级名：**优先用服务端给的 `level_name`**（档位阈值在运营后台配置，改了要立刻反映到服务者这边），
 * 它缺席时按契约的 1/2/3 兜底，别让界面空一格。
 */
export function assessmentLevelLabel(view: { level?: number | null; level_name?: string | null }): string {
  if (view.level_name) return view.level_name;
  switch (view.level) {
    case 1:
      return "基础";
    case 2:
      return "优选";
    case 3:
      return "战略合作";
    default:
      return "—";
  }
}

export function assessmentLevelTone(level?: number | null): TagClass {
  switch (level) {
    case 3:
      return "ph-tag--success";
    case 2:
      return "ph-tag--info";
    default:
      return "";
  }
}

/** AI 推荐优先级：1 最高 / 2 较高 / 3 普通（只存映射结果，不改推荐逻辑）。 */
export function recommendPriorityLabel(priority?: number | null): string {
  switch (priority) {
    case 1:
      return "最高";
    case 2:
      return "较高";
    case 3:
      return "普通";
    default:
      return "—";
  }
}

/**
 * 考核分数的展示：契约里 `total_score` / `score` 是**两位小数的字符串**（0.00–100.00，`decimal` 的
 * 精度纪律，ADR-0011），但**它不是金额**——所以不走 `formatAmount`（那个会加 ¥，钱的口径用在分数上
 * 就是错的），只借「分」做一次规范化：`"95"` → `"95.00"`；形状不认识时原样返回（不猜、不补 0）。
 */
export function scoreText(score?: string | null): string {
  const cents = parseAmountToCents(score);
  return cents === null ? (score ?? "—") : centsToAmount(cents);
}

/** 考核明细里的项编码 → 人话；运营侧也要用同一套，所以按契约的枚举写全（含未参与的项）。 */
const ASSESSMENT_ITEM_NAMES: Record<string, string> = {
  INVITE: "拉新",
  COUPON: "券",
  PROCESS: "过程",
  PROCESS_RESPONSE: "接单响应",
  PROCESS_REDEEM_RATE: "核销率",
  PROCESS_REPORT_RATE: "报工完整率",
  PROCESS_REVIEW: "评价分",
  PROCESS_CANCEL_RATE: "服务者取消率",
};

/** 考核项名：服务端给了 `item_name` 就用它，缺席时按编码兜底（编码是契约定的枚举）。 */
export function assessmentItemLabel(itemCode?: string | null, itemName?: string | null): string {
  return itemName || (itemCode != null && ASSESSMENT_ITEM_NAMES[itemCode]) || (itemCode ?? "—");
}

/** 参与计分的权重合计：100 = 三项全参与；缺项按 ADR-0050 第四节重算后会小于 100。 */
export function participatedWeightText(weight?: number | null): string {
  if (weight == null) return "—";
  return weight === 100 ? "三项全部参与计分" : `参与项权重合计 ${weight}%（有缺项，权重按参与项重算）`;
}

/**
 * 总分那一行下面的等级说明。**等级决定流量（AI 推荐优先级）与区域保护**，所以要写出来；
 * 但它不是「服务者之间排名的名次」——契约只给了等级与推荐优先级两个字段。
 */
export function assessmentRankText(view: AssessmentSummaryViewLike): string {
  return `${assessmentLevelLabel(view)} · AI 推荐优先级：${recommendPriorityLabel(view.recommend_priority)}`;
}

/** `assessmentRankText` 的形状：两个端点（列表 / 明细）的字段子集，不为了一个函数去引整个 schema。 */
interface AssessmentSummaryViewLike {
  level?: number | null;
  level_name?: string | null;
  recommend_priority?: number | null;
}

