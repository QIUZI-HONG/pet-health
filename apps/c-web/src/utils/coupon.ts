/**
 * 券的口径与筛选（「这张券能不能用在本单」），以及 C 端对共享字典的**展示口径**。
 *
 * 状态与来源码 → 展示词的那份字典在 `@pet-health/shared` 的 `dict/coupon.ts`
 * （三个端共用一份，原来是五份逐字相同的 `switch`）。这里只留两件事：
 * ① 转出筛选器要用的选项；② 定下 C 端的**认不出码时说什么**——C 端面对的是普通用户，
 * 说「未知状态」比表格里的「—」更有用（用户能判断这是异常而不是空值）。
 *
 * 钱的提醒：券只是**到店抵扣凭证**，它的面额与门槛都不产生资金流（ADR-0036）。
 */
import type { CouponView } from "../api/commerce";
import {
  COUPON_SOURCE,
  COUPON_SOURCE_OPTIONS,
  COUPON_STATUS,
  COUPON_STATUS_OPTIONS,
  couponSourceLabel as sharedSourceLabel,
  couponStatusLabel as sharedStatusLabel,
} from "@pet-health/shared";

export { COUPON_SOURCE, COUPON_SOURCE_OPTIONS, COUPON_STATUS, COUPON_STATUS_OPTIONS };

/** C 端认不出状态码时的说法（与订单状态页的「未知状态」同一口径）。 */
const UNKNOWN_STATUS = "未知状态";

/** C 端认不出来源码时的说法。 */
const UNKNOWN_SOURCE = "未知来源";

export function couponStatusLabel(status: number | null | undefined): string {
  return sharedStatusLabel(status, UNKNOWN_STATUS);
}

export function couponSourceLabel(source: number | null | undefined): string {
  return sharedSourceLabel(source, UNKNOWN_SOURCE);
}

/**
 * 这张券是否可能用在这家门店。
 *
 * 两条判据来自契约：服务者贡献券**只能在其本店核销**（`provider_id` 为空的是平台补贴券，
 * 按模板适用范围核销，所以对任何门店都放行）。**门槛不在这里判**——门槛比的是订单总额，
 * 而下单前我们并不知道总额，硬判会把能用的券藏起来；服务端仍会按 80001 复核一次。
 */
export function appliesToProvider(coupon: CouponView, providerId: number): boolean {
  return coupon.provider_id === null || coupon.provider_id === undefined || coupon.provider_id === providerId;
}

/** 核销门店的展示文字：平台补贴券没有门店，要说清它按适用范围核销，而不是留空。 */
export function redeemPlaceText(coupon: CouponView): string {
  return coupon.provider_name ?? "平台补贴券（按适用范围核销）";
}
