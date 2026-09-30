<script setup lang="ts">
/**
 * 金额区块：总额 / 券抵扣 / **预估实付**（下单结果与订单详情共用）。
 *
 * 三条展示纪律，都是 ADR-0036 与 ADR-0038 第二节点名的：
 *
 *  1. 金额是契约给的**字符串两位小数**，只经 `formatAmount` 加 `¥` 前缀，**不做任何算术**
 *     （浮点在金额上是错的，ADR-0011）；
 *  2. 大字那个数是**预估实付**，标签必须带「预估」二字——它不是账、不参与对账、不产生资金流；
 *  3. 页面必须写明**费用在门店直接付给服务者**，并说清核销只是履约确认、与收款无关。
 *     这一句不是装饰：不写，用户会以为平台收了钱（验收文档 5.7 的「实付大字」被 ADR-0036 改写了）。
 */
import type { CouponView } from "../api/commerce";
import { formatAmount } from "../utils/money";

withDefaults(
  defineProps<{
    totalAmount?: string | null;
    couponDiscount?: string | null;
    estimatedPayAmount?: string | null;
    /** 本单用的券；`null` 表示没用券。 */
    coupon?: CouponView | null;
  }>(),
  { totalAmount: null, couponDiscount: null, estimatedPayAmount: null, coupon: null },
);
</script>

<template>
  <section class="ph-amount">
    <dl class="ph-amount__rows">
      <div class="ph-amount__row">
        <dt>服务总额</dt>
        <dd>{{ formatAmount(totalAmount) }}</dd>
      </div>
      <div class="ph-amount__row">
        <dt>券抵扣{{ coupon?.template_name ? `（${coupon.template_name}）` : "" }}</dt>
        <dd>−{{ formatAmount(couponDiscount) }}</dd>
      </div>
      <div class="ph-amount__row ph-amount__row--pay">
        <dt>预估实付</dt>
        <dd class="ph-amount__pay">{{ formatAmount(estimatedPayAmount) }}</dd>
      </div>
    </dl>

    <p class="ph-amount__notice">
      <strong>预估实付只是预估</strong>：它等于「服务总额 − 券面额」，用来让你看清这张券抵了多少。
      <strong>费用在门店直接付给服务者</strong>，平台不经手资金，也不提供线上支付；
      门店核销只是确认这次预约已被使用，与收款无关。
    </p>
  </section>
</template>

<style scoped>
.ph-amount {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-amount__rows {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-amount__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-amount__row dt {
  color: var(--ph-color-text-sub);
  font-size: 13px;
}

.ph-amount__row dd {
  margin: 0;
  font-family: var(--ph-font-numeric);
}

.ph-amount__row--pay dt {
  color: var(--ph-color-text);
  font-size: 14px;
}

.ph-amount__pay {
  font-size: 24px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-amount__notice {
  margin: 0;
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ph-color-text-sub);
}
</style>
