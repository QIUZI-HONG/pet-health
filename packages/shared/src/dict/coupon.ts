/**
 * 券域的状态码字典：契约里那些整数码 → 界面上的一句话。
 *
 * 为什么必须收在一处：券的状态（1–4）与来源（1–5）在**至少五个地方**要显示——C 端的券包与
 * 下单页、服务者后台的券管理与结算、运营后台的券模板 / 发放 / 券池总览。原先这些地方各写一份
 * `switch`/`find`（C 端与服务者各一份函数、运营后台三个面板各一份），五份**取值逐字相同**，
 * 差别只在认不出码时回什么（C 端回「未知来源」，两个后台回「—」）。
 *
 * 五份拷贝的代价不是行数，而是**同一张券可能有两种说法**：将来加一个来源码（比如退款补偿），
 * 改了三处漏两处，用户在券包里看到的来源就会和运营在后台看到的对不上。所以字典只留一份，
 * 「认不出回什么」由调用方传（`fallback`）——那是展示口径，不是编码口径。
 *
 * 取值与文案以 `contract/app.yaml` 的 `CouponView` 为准
 * （状态 `1 待使用 / 2 已锁定 / 3 已核销 / 4 已过期`、来源 `1 邀请 / 2 打卡任务 / 3 积分兑换 /
 * 4 平台补贴 / 5 月度阶梯`）。钱的提醒：券只是**到店抵扣凭证**，面额与门槛都不产生资金流（ADR-0036）。
 */

/** 券状态码：1 待使用 / 2 已锁定（下单占用）/ 3 已核销 / 4 已过期。 */
export const COUPON_STATUS = { USABLE: 1, LOCKED: 2, REDEEMED: 3, EXPIRED: 4 } as const;

/** 券来源码：1 邀请 / 2 打卡任务 / 3 积分兑换 / 4 平台补贴 / 5 月度阶梯。 */
export const COUPON_SOURCE = {
  INVITE: 1,
  CHECK_IN_TASK: 2,
  POINTS_EXCHANGE: 3,
  PLATFORM_SUBSIDY: 4,
  MONTHLY_LADDER: 5,
} as const;

/** 券状态选项（顺序与契约的状态码一致，`value` 直接用契约的码；C 端的券包筛选用它）。 */
export const COUPON_STATUS_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
  { value: COUPON_STATUS.USABLE, label: "待使用" },
  { value: COUPON_STATUS.LOCKED, label: "已锁定" },
  { value: COUPON_STATUS.REDEEMED, label: "已核销" },
  { value: COUPON_STATUS.EXPIRED, label: "已过期" },
];

/** 券来源选项（同上，供筛选用）。 */
export const COUPON_SOURCE_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
  { value: COUPON_SOURCE.INVITE, label: "邀请" },
  { value: COUPON_SOURCE.CHECK_IN_TASK, label: "打卡任务" },
  { value: COUPON_SOURCE.POINTS_EXCHANGE, label: "积分兑换" },
  { value: COUPON_SOURCE.PLATFORM_SUBSIDY, label: "平台补贴" },
  { value: COUPON_SOURCE.MONTHLY_LADDER, label: "月度阶梯" },
];

/** 认不出的码回什么，由调用方定：C 端说「未知状态」（用户在等一个解释），后台说「—」（表格里要干净）。 */
export const COUPON_UNKNOWN = "—";

/** 状态码 → 中文名。 */
export function couponStatusLabel(status?: number | null, fallback: string = COUPON_UNKNOWN): string {
  return COUPON_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? fallback;
}

/** 来源码 → 中文名。 */
export function couponSourceLabel(source?: number | null, fallback: string = COUPON_UNKNOWN): string {
  return COUPON_SOURCE_OPTIONS.find((option) => option.value === source)?.label ?? fallback;
}
