<script setup lang="ts">
/**
 * 订单详情（切片 #109；决策见 ADR-0038 第一、二节 / ADR-0036 / ADR-0040 第四节）。
 *
 * 这一页承载三件**只能在这里做**的事：
 *
 *  1. **6 位核销码**：到店凭证。可见性由服务端判（到预约时间、且订单在「已预约 / 履约中」），
 *     前端**不看系统时间自己算**——两处判就会有两次答案，而用户会按自己看到的那次跑一趟店；
 *  2. **三道照片墙的进度**：照片由门店上传，`reportable` 与 `missing_slots` 是服务端算好的结论；
 *  3. **取消入口**：一个按钮两种结果——待接单是直接取消，已预约是**发起取消申请**（等门店同意）；
 *     履约中 / 终态没有入口（见 utils/order-status.ts 的映射）。
 *
 * 还有一件事只在这一页能做：**评价晒单**。入口只对「已完成」的订单出现——未完成的订单里，
 * 用户还没拿到服务，没有什么可评的（服务端同一条规则，不是已完成 → 40900）。
 * 评过之后这里显示那条评价（`OrderView.review`，服务端给的），所以刷新页面不会再看到一次入口
 * ——一单一评且不可修改，重复提交是 40900。
 *
 * 「钱」的口径写在金额卡里：预估实付是展示值，**费用在门店直接付给服务者**。
 */
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ApiError, createLatestGuard, formatDate, formatDateTime, newIdempotencyKey, toApiFailure } from "@pet-health/shared";
import { commerce, type OrderReviewView, type OrderView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import CouponCard from "../components/CouponCard.vue";
import OrderAmountCard from "../components/OrderAmountCard.vue";
import OrderPhotoWallCard from "../components/OrderPhotoWallCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import {
  ORDER_STATUS,
  cancelActionLabel,
  cancelModeOf,
  cancelledByLabel,
  orderStatusView,
} from "../utils/order-status";
import { reviewContentText, reviewStars } from "../utils/review";
import { useSubmitAction } from "@pet-health/ui";

const route = useRoute();
const session = useSessionStore();
const order = ref<OrderView | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 订单不存在（40400）：重试没有意义，页面该说的是「这笔单不在了」而不是「加载失败」。 */
const notFound = ref(false);
const latest = createLatestGuard();

/** 取消理由（可空，契约里是可选的；但门店侧看起来更需要理由，所以界面引导填一句）。 */
const cancelReason = ref("");
const cancelFormOpen = ref(false);
const cancelAction = useSubmitAction("取消失败，请稍后重试");

/** 单号来自路由；不是正整数就不发请求（深链接可能被手改）。 */
const orderId = computed(() => Number(route.params.id));

const mode = computed(() => cancelModeOf(order.value?.status, order.value?.cancel_request_status));
const statusText = computed(() => orderStatusView(order.value?.status));

/**
 * 核销码不可见时的说明：**要说清「什么时候可见」**，别只留一个空框。
 * 判断只看服务端给的状态，不看本地时间——两处判就会有两个答案，而用户会按自己看到的那次跑一趟店。
 */
const redeemHint = computed(() => {
  switch (order.value?.status) {
    case ORDER_STATUS.PENDING_ACCEPT:
      return "门店接单后会生成核销码；到预约时间后才显示，避免凭证太早留在外面。";
    case ORDER_STATUS.COMPLETED:
      return "服务已完成，核销码不再显示。";
    case ORDER_STATUS.CANCELLED:
      return "订单已取消，核销码不再显示。";
    default:
      return "到店凭证：到预约时间后可查看。";
  }
});

async function load(): Promise<void> {
  const id = orderId.value;
  if (!Number.isInteger(id) || id <= 0) {
    loading.value = false;
    notFound.value = true;
    return;
  }
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  notFound.value = false;
  try {
    const result = await commerce.getOrder(id, signal);
    if (!latest.isCurrent(token)) return;
    order.value = result;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    if (error instanceof ApiError && error.code === 40400) {
      // 别人的订单与不存在的订单**同码**（docs/conventions.md：越权一律 40400），
      // 所以这里不能猜「是不是你的」——照契约的答复说「订单不存在」即可
      notFound.value = true;
      return;
    }
    const failure = toApiFailure(error, "加载订单失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load();
  },
  { immediate: true },
);

async function submitCancel(): Promise<void> {
  const target = order.value?.id;
  if (!target) return;
  const result = await cancelAction.run(() =>
    commerce.cancelOrder(target, cancelReason.value, newIdempotencyKey()),
  );
  if (!result.ok) return;
  order.value = result.value;
  cancelFormOpen.value = false;
  cancelReason.value = "";
}

/**
 * 评价入口的可见性：**只有「已完成」的订单**（服务真的发生过，用户才有的可说）。
 *
 * 与取消入口同一个做法：服务端才是规则的执行者（不是已完成 → 40900），这里只是不去显示
 * 一个点了必然失败的按钮。**不看本地时间**、不猜状态。
 */
const canReview = computed(
  () => order.value?.status === ORDER_STATUS.COMPLETED && !order.value?.review,
);

/** 评价表单：星级（必填）+ 一句话（可选，≤500 字）。 */
const rating = ref(0);
const content = ref("");
const reviewAction = useSubmitAction("评价提交失败，请稍后重试");

/** 没选星级就不让提交：评分的必填性在服务端也是一道（40001），界面这一道只省一次往返。 */
const canSubmitReview = computed(() => rating.value >= 1);

/** 已提交的评价（`order.review`）。刷新页面后也在——服务端把它放在订单详情里就是为这件事。 */
const submittedReview = computed<OrderReviewView | null>(() => order.value?.review ?? null);

async function submitReview(): Promise<void> {
  const target = order.value?.id;
  if (!target || !canSubmitReview.value) return;
  const result = await reviewAction.run(
    () =>
      commerce.reviewOrder(
        target,
        { rating: rating.value, content: content.value.trim() || null },
        newIdempotencyKey(),
      ),
    "评价已提交，谢谢你的反馈",
  );
  if (!result.ok) return;
  // 原地换成「已评价」：把返回的那条评价挂到订单上，模板随即只显示评价内容
  order.value = { ...order.value!, review: result.value };
  content.value = "";
}

/** 申请被门店拒绝后还能再申请一次（契约只在「已有待处理申请」时拒绝）。 */
const rejectedReason = computed(() => order.value?.cancel_rejected_reason ?? "");

/** 「再次预约」：带着本单的门店与服务项回到下单页（下单依据是这两个 id，名字只用于展示）。 */
const rebookTarget = computed(() => ({
  name: "order-create",
  query: {
    provider_id: order.value?.provider_id ?? "",
    service_id: order.value?.service_id ?? "",
    service_name: order.value?.service_name ?? "",
    provider_name: order.value?.provider_name ?? "",
  },
}));

</script>

<template>
  <section>
    <h2 class="ph-page-title">订单详情</h2>
    <p class="ph-page-desc">核销码、照片墙进度与取消入口都在这里。</p>

    <SessionGate forbidden-description="登录后查看你的订单。">
      <StateLoading v-if="loading" :rows="5" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />
      <article v-else-if="notFound || !order" class="ph-card">
        <StateEmpty icon="🧾" title="订单不存在" description="它可能不属于当前账号，或者链接里的单号不对。">
          <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'orders' }">回到我的订单</RouterLink>
        </StateEmpty>
      </article>

      <div v-else class="ph-stack">
        <!-- 状态与核销码：这两块是「到店要用的」，放在最上面 -->
        <article class="ph-card">
          <div class="ph-order__head">
            <span class="ph-order__status" :class="`ph-order__status--${statusText.tone}`">{{ statusText.label }}</span>
            <span class="ph-order__no">订单号 {{ order.order_no ?? "—" }}</span>
            <RouterLink class="ph-button ph-button--text" :to="{ name: 'orders' }">全部订单</RouterLink>
            <RouterLink class="ph-button ph-button--text" :to="rebookTarget">再次预约</RouterLink>
          </div>
          <p class="ph-order__hint ph-text-sub">{{ statusText.hint }}</p>

          <div class="ph-order__redeem">
            <template v-if="order.redeem_code">
              <span class="ph-text-sub">到店核销码（出示给门店）</span>
              <span class="ph-order__code">{{ order.redeem_code }}</span>
            </template>
            <template v-else>
              <span class="ph-text-sub">{{ redeemHint }}</span>
            </template>
            <p class="ph-text-weak ph-order__redeem-note">
              核销只是确认这次预约已被使用，<strong>与收款无关</strong>：费用在门店直接付给服务者。
            </p>
          </div>
        </article>

        <!-- 门店、宠物、时间与备注 -->
        <article class="ph-card">
          <h3 class="ph-card__title">本次预约</h3>
          <dl class="ph-order__rows">
            <div class="ph-order__row"><dt>门店</dt><dd>{{ order.provider_name ?? "—" }}</dd></div>
            <div class="ph-order__row"><dt>服务项</dt><dd>{{ order.service_name ?? "—" }}</dd></div>
            <div class="ph-order__row"><dt>宠物</dt><dd>{{ order.pet_name ?? "—" }}</dd></div>
            <div class="ph-order__row">
              <dt>预约时间</dt>
              <dd>{{ formatDate(order.appointment_date) }} {{ order.start_time ?? "" }}–{{ order.end_time ?? "" }}</dd>
            </div>
            <div v-if="order.remark" class="ph-order__row"><dt>我的备注</dt><dd>{{ order.remark }}</dd></div>
            <div v-if="order.accepted_at" class="ph-order__row">
              <dt>门店接单</dt>
              <dd>{{ formatDateTime(order.accepted_at) }}</dd>
            </div>
            <div v-if="order.redeemed_at" class="ph-order__row">
              <dt>到店核销</dt>
              <dd>{{ formatDateTime(order.redeemed_at) }}</dd>
            </div>
            <div v-if="order.reported_at" class="ph-order__row">
              <dt>完成报工</dt>
              <dd>{{ formatDateTime(order.reported_at) }}</dd>
            </div>
            <div v-if="order.report_remark" class="ph-order__row">
              <dt>门店说明</dt>
              <dd>{{ order.report_remark }}</dd>
            </div>
            <div v-if="order.cancelled_at" class="ph-order__row">
              <dt>取消时间</dt>
              <dd>{{ formatDateTime(order.cancelled_at) }} {{ cancelledByLabel(order.cancelled_by) }}</dd>
            </div>
          </dl>
        </article>

        <!-- 金额：预估实付 + 到店付声明 -->
        <article class="ph-card">
          <h3 class="ph-card__title">费用</h3>
          <OrderAmountCard
            :total-amount="order.total_amount"
            :coupon-discount="order.coupon_discount"
            :estimated-pay-amount="order.estimated_pay_amount"
            :coupon="order.coupon"
          />
        </article>

        <article v-if="order.coupon" class="ph-card">
          <h3 class="ph-card__title">本单使用的券</h3>
          <p class="ph-card__note ph-text-sub">下单时锁定，核销时转「已核销」，取消订单会释放回「待使用」。</p>
          <CouponCard :coupon="order.coupon" />
        </article>

        <!-- 三道照片墙（只读） -->
        <article class="ph-card">
          <h3 class="ph-card__title">三道照片墙</h3>
          <OrderPhotoWallCard :photo-wall="order.photo_wall" />
        </article>

        <!-- 评价晒单：只有「已完成」的订单有入口；评过之后这里就是那条评价 -->
        <article v-if="order.status === ORDER_STATUS.COMPLETED" class="ph-card">
          <h3 class="ph-card__title">评价晒单</h3>

          <template v-if="submittedReview">
            <p class="ph-order__review-stars" :aria-label="`${submittedReview.rating} 星`">
              {{ reviewStars(submittedReview.rating) }}
              <span class="ph-text-sub">{{ submittedReview.rating }} 分</span>
            </p>
            <p class="ph-order__review-content">{{ reviewContentText(submittedReview.content) }}</p>
            <p class="ph-text-weak">{{ formatDateTime(submittedReview.created_at) }} · 一单一评，不能修改或追评</p>
          </template>

          <template v-else-if="canReview">
            <p class="ph-text-sub">
              这次服务怎么样？打个分（必填），有想说的再写一句（可选，最多 500 字）。
            </p>

            <div class="ph-order__stars" role="radiogroup" aria-label="评分">
              <button
                v-for="star in [1, 2, 3, 4, 5]"
                :key="star"
                type="button"
                class="ph-order__star"
                :class="{ 'ph-order__star--on': star <= rating }"
                role="radio"
                :aria-checked="star === rating"
                :aria-label="`${star} 星`"
                @click="rating = star"
              >
                {{ star }}★
              </button>
            </div>

            <label class="ph-field">
              <span class="ph-field__label">一句话评价（可选）</span>
              <textarea
                v-model="content"
                class="ph-field__input ph-order__textarea"
                maxlength="500"
                rows="3"
                placeholder="例如：技师很有耐心，洗完还拍了对比照"
              />
            </label>

            <div class="ph-form__actions">
              <button
                type="button"
                class="ph-button ph-button--primary"
                :disabled="!canSubmitReview || reviewAction.submitting.value"
                @click="submitReview"
              >
                {{ reviewAction.submitting.value ? "提交中…" : "提交评价" }}
              </button>
              <span v-if="!canSubmitReview" class="ph-text-weak">先选星级再提交</span>
              <span v-else-if="reviewAction.doneMessage.value" class="ph-text-weak">
                {{ reviewAction.doneMessage.value }}
              </span>
            </div>

            <p v-if="reviewAction.errorMessage.value" class="ph-form__error">
              {{ reviewAction.errorMessage.value }}
              <span v-if="reviewAction.requestId.value" class="ph-text-weak">
                （请求 ID：{{ reviewAction.requestId.value }}）
              </span>
            </p>
          </template>
        </article>

        <!-- 取消入口：按状态给一种结果，不给「看起来能点其实不行」的按钮 -->
        <article class="ph-card">
          <h3 class="ph-card__title">取消</h3>

          <p v-if="mode === 'pending'" class="ph-text-sub">
            取消申请已提交（{{ formatDateTime(order.cancel_requested_at) }}），等门店处理。
            门店同意后订单转「已取消」并释放号源与券；拒绝的话订单保持「已预约」。
          </p>
          <p v-else-if="mode === 'none' && order.status === ORDER_STATUS.IN_SERVICE" class="ph-text-sub">
            「履约中」不能取消：服务已经开始，只能等服务完成（或由平台介入）。
          </p>
          <p v-else-if="mode === 'none'" class="ph-text-sub">
            {{ statusText.label }}是终态，没有取消动作。
          </p>

          <p v-if="order.cancel_reason" class="ph-text-sub">我填写的取消理由：{{ order.cancel_reason }}</p>
          <p v-if="rejectedReason" class="ph-order__rejected">
            门店拒绝了上一次取消申请，理由：{{ rejectedReason }}
          </p>

          <template v-if="mode === 'cancel' || mode === 'request' || mode === 'rejected'">
            <button
              v-if="!cancelFormOpen"
              type="button"
              class="ph-button"
              :class="mode === 'cancel' ? 'ph-button--secondary' : 'ph-button--primary'"
              @click="cancelFormOpen = true"
            >
              {{ cancelActionLabel(mode) }}
            </button>

            <div v-else class="ph-order__cancel-form">
              <label class="ph-field">
                <span class="ph-field__label">
                  {{ mode === "cancel" ? "取消理由（可选，会记录在订单上）" : "取消理由（门店会看到，建议说明原因）" }}
                </span>
                <textarea
                  v-model="cancelReason"
                  class="ph-field__input ph-order__textarea"
                  maxlength="255"
                  rows="3"
                  placeholder="例如：时间冲突，改约下周"
                />
              </label>

              <p v-if="mode === 'request'" class="ph-text-weak">
                提交后订单<strong>仍然是「已预约」</strong>，要等门店同意；门店拒绝时会给出理由。
              </p>

              <div class="ph-form__actions">
                <button
                  type="button"
                  class="ph-button ph-button--primary"
                  :disabled="cancelAction.submitting.value"
                  @click="submitCancel"
                >
                  {{ cancelAction.submitting.value ? "提交中…" : `确认${cancelActionLabel(mode)}` }}
                </button>
                <button type="button" class="ph-button ph-button--secondary" @click="cancelFormOpen = false">
                  再想想
                </button>
              </div>
            </div>

            <p v-if="cancelAction.errorMessage.value" class="ph-form__error">
              {{ cancelAction.errorMessage.value }}
              <span v-if="cancelAction.requestId.value" class="ph-text-weak">
                （请求 ID：{{ cancelAction.requestId.value }}）
              </span>
            </p>
          </template>
        </article>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-order__head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  flex-wrap: wrap;
}

.ph-order__status {
  padding: 2px var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  font-size: 13px;
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

.ph-order__status--waiting {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-order__status--active {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-order__status--done {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary-dark);
}

.ph-order__no {
  font-family: var(--ph-font-numeric);
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-order__hint {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
}

.ph-order__redeem {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
  margin-top: var(--ph-space-4);
  padding: var(--ph-space-4);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
}

.ph-order__code {
  font-family: var(--ph-font-numeric);
  font-size: 32px;
  font-weight: 600;
  letter-spacing: 6px;
  color: var(--ph-color-primary-dark);
}

.ph-order__redeem-note {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ph-order__rows {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-order__row {
  display: flex;
  gap: var(--ph-space-4);
  align-items: baseline;
}

.ph-order__row dt {
  min-width: 88px;
  color: var(--ph-color-text-sub);
  font-size: 13px;
}

.ph-order__row dd {
  margin: 0;
}

.ph-order__rejected {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  color: var(--ph-color-danger);
}

.ph-order__cancel-form {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-3);
}

/* 评价：星级选择与已评价的展示（同一处样式，两处形态） */
.ph-order__stars {
  display: flex;
  gap: var(--ph-space-2);
  margin: var(--ph-space-3) 0;
}

.ph-order__star {
  padding: var(--ph-space-1) var(--ph-space-2);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  background: transparent;
  color: var(--ph-color-text-weak);
  font-family: var(--ph-font-numeric);
  font-size: 14px;
  cursor: pointer;
}

.ph-order__star--on {
  border-color: var(--ph-color-orange);
  color: var(--ph-color-orange);
}

.ph-order__review-stars {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-2);
  color: var(--ph-color-orange);
  font-size: 18px;
  letter-spacing: 2px;
}

.ph-order__review-content {
  margin: var(--ph-space-3) 0 var(--ph-space-2);
  line-height: 1.7;
}

.ph-order__textarea {
  height: auto;
  padding: var(--ph-space-2) var(--ph-space-3);
  resize: vertical;
}
</style>
