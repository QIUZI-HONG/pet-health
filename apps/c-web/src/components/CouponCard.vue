<script setup lang="ts">
/**
 * 券卡片（券包 / 下单页选券 / 兑换结果共用）。
 *
 * 一张券要让人看懂三件事：**值多少**（面额）、**什么时候能用**（门槛 + 有效期）、
 * **在哪能用**（核销门店）。三条都来自契约的券实例：面额与门槛是**快照**——模板后来改了，
 * 已发的券不受影响（ADR-0044 第六节：券是平台对用户的承诺）。
 *
 * `selectable` 时整张卡片可点（下单页选券用）；否则只读，动作由 `actions` 插槽给。
 */
import { computed } from "vue";
import { formatDate } from "@pet-health/shared";
import type { CouponView } from "../api/commerce";
import { couponSourceLabel, couponStatusLabel, redeemPlaceText } from "../utils/coupon";
import { formatAmount, thresholdText } from "../utils/money";

const props = withDefaults(
  defineProps<{
    coupon: CouponView;
    selectable?: boolean;
    selected?: boolean;
    /** 被选中时右侧显示的那个词（下单页是「已选」，其它场景留空）。 */
    selectedText?: string;
  }>(),
  { selectable: false, selected: false, selectedText: "" },
);

const emit = defineEmits<{ select: [CouponView] }>();

/** 「无法使用」只按状态说（已核销 / 已过期 / 已锁定），不猜门槛与范围——那些服务端判。 */
const muted = computed(() => props.coupon.status === 3 || props.coupon.status === 4);

function pick(): void {
  if (props.selectable) emit("select", props.coupon);
}
</script>

<template>
  <article
    class="ph-coupon"
    :class="{
      'ph-coupon--muted': muted,
      'ph-coupon--selectable': selectable,
      'ph-coupon--selected': selected,
    }"
    @click="pick"
  >
    <div class="ph-coupon__face">
      <span class="ph-coupon__amount">{{ formatAmount(coupon.face_value) }}</span>
      <span class="ph-coupon__threshold">{{ thresholdText(coupon.min_amount) }}</span>
    </div>

    <div class="ph-coupon__body">
      <div class="ph-coupon__head">
        <span class="ph-coupon__name">{{ coupon.template_name ?? "券" }}</span>
        <span class="ph-coupon__status">{{ couponStatusLabel(coupon.status) }}</span>
        <span v-if="selected && selectedText" class="ph-coupon__picked">{{ selectedText }}</span>
      </div>
      <p class="ph-coupon__meta">
        {{ couponSourceLabel(coupon.source) }} · 核销门店：{{ redeemPlaceText(coupon) }}
      </p>
      <p class="ph-coupon__meta">
        有效期：{{ formatDate(coupon.valid_from) }} 至 {{ formatDate(coupon.valid_until) }}
        <span v-if="coupon.code" class="ph-text-weak">· 券码 {{ coupon.code }}</span>
      </p>
      <div v-if="$slots.actions" class="ph-coupon__actions">
        <!-- 卡片本身可点选时，动作区里的按钮不能再冒泡成「选中」 -->
        <span @click.stop><slot name="actions" /></span>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* 券的浅底用 --ph-color-orange-light（视觉稿里券/福利用的就是这一档） */
.ph-coupon {
  display: flex;
  gap: var(--ph-space-4);
  padding: var(--ph-space-4);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-card);
}

.ph-coupon--selectable {
  cursor: pointer;
}

.ph-coupon--selected {
  border-color: var(--ph-color-primary);
  background: var(--ph-color-primary-light);
}

.ph-coupon--muted {
  opacity: 0.6;
}

.ph-coupon__face {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ph-space-1);
  min-width: 104px;
  padding: var(--ph-space-3);
  background: var(--ph-color-orange-light);
  border-radius: var(--ph-radius-input);
}

.ph-coupon__amount {
  font-family: var(--ph-font-numeric);
  font-size: 22px;
  font-weight: 600;
  color: var(--ph-color-orange);
}

.ph-coupon__threshold {
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-coupon__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-coupon__head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-coupon__name {
  font-size: 15px;
  font-weight: 600;
}

.ph-coupon__status,
.ph-coupon__picked {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

.ph-coupon__picked {
  background: var(--ph-color-primary);
  color: var(--ph-color-surface);
}

.ph-coupon__meta {
  margin: 0;
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-coupon__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-2);
}
</style>
