<script setup lang="ts">
/**
 * 下单页（切片 #109；决策见 ADR-0038 第一节 / ADR-0036 / ADR-0048）。
 *
 * 一页做六件事：选宠物、选服务项（由链接带入）、选预约时段（号源）、选券、填备注、提交。
 * 三条从契约来的硬约束写在这一页里：
 *
 *  1. **约满的时段照样列出来但不可选**（灰的「已满」）：让它从列表里消失，用户会以为门店那天
 *     根本不营业（ADR-0038 第一节）。并发抢同一时段由服务端兜住，后到者拿到 40900；
 *  2. **「预估实付」是预估**：券面额只用于展示与到店抵扣，**费用在门店直接付给服务者**
 *     （ADR-0036），所以这一页**没有「去支付」**，一个支付字段都不该出现；
 *  3. **服务项价格这一页拿不到**：契约里 C 端只有号源查询，没有「查服务项」的接口，
 *     所以提交前只能显示「已选券面额」与说明，**真实的预估实付在下单成功后由服务端给出**
 *     （见下方结果卡）。前端不替服务端算金额，也不伪造一个价。
 *
 * 幂等：提交带 `Idempotency-Key`（ADR-0028），用户连点时按钮另禁用 2 秒（docs/conventions.md）。
 */
import { computed, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { createLatestGuard, formatDate, newIdempotencyKey, todayIso, toApiFailure } from "@pet-health/shared";
import { commerce, type AppointmentSlotView, type CouponView, type OrderView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import CouponCard from "../components/CouponCard.vue";
import OrderAmountCard from "../components/OrderAmountCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { appliesToProvider } from "../utils/coupon";
import { formatAmount } from "../utils/money";
import { useSubmitAction } from "@pet-health/ui";

const route = useRoute();
const session = useSessionStore();

/**
 * 门店与服务项来自链接（服务页的门店目录还没上线，见 ADR-0047 的推进顺序）：
 * `provider_id` / `service_id` 是**下单依据**，`*_name` 只用于显示（它们取自上一笔订单）。
 */
function queryNumber(key: string): number {
  const raw = route.query[key];
  const value = Number(typeof raw === "string" ? raw : NaN);
  return Number.isInteger(value) && value > 0 ? value : 0;
}

/** `*_name` 只用于显示，取不到就给空串——下单依据是上面那两个数字 id，不是名字。 */
function queryText(key: string): string {
  const raw = route.query[key];
  return typeof raw === "string" ? raw.trim() : "";
}

const providerId = computed(() => queryNumber("provider_id"));
const serviceId = computed(() => queryNumber("service_id"));
const providerName = computed(() => queryText("provider_name"));
const serviceName = computed(() => queryText("service_name"));
const hasTarget = computed(() => providerId.value > 0 && serviceId.value > 0);

const form = reactive({
  petId: 0,
  date: todayIso(),
  startTime: "",
  couponId: null as number | null,
  remark: "",
});

const slots = ref<AppointmentSlotView[]>([]);
const coupons = ref<CouponView[]>([]);
const slotsLoading = ref(false);
const couponsLoading = ref(false);
const loadError = ref("");
const loadRequestId = ref("");
/** 下单成功后的那一单：金额三项与到店付声明都由它来（**服务端算的**，不是前端算的）。 */
const created = ref<OrderView | null>(null);

const submit = useSubmitAction("下单失败，请稍后重试");
const slotsLatest = createLatestGuard();

/** 可选的服务项选择范围：宠物来自会话（多宠家庭）。 */
const pets = computed(() => session.pets);

/**
 * 取号源：换日期会作废上一次请求（slotsLatest），并把已选但已不存在的时段清掉——
 * 否则会提交一个页面上看不见的时段。
 */
async function loadSlots(): Promise<void> {
  if (!hasTarget.value) return;
  const { token, signal } = slotsLatest.claim();
  slotsLoading.value = true;
  loadError.value = "";
  loadRequestId.value = "";
  try {
    const result = await commerce.listAppointmentSlots(providerId.value, serviceId.value, form.date, signal);
    if (!slotsLatest.isCurrent(token)) return;
    slots.value = result ?? [];
    // 换日期后原来选的时段多半不存在了，清掉，免得提交一个页面上看不到的时段
    if (!slots.value.some((slot) => slot.start_time === form.startTime && !slot.full)) {
      form.startTime = "";
    }
  } catch (error) {
    if (!slotsLatest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载可约时段失败，请稍后重试");
    loadError.value = failure.message;
    loadRequestId.value = failure.requestId;
  } finally {
    if (slotsLatest.isCurrent(token)) {
      slotsLoading.value = false;
    }
  }
}

/** 只取「待使用」的券，再按门店过滤：契约没有按门店查的入参，服务者贡献券只能在本店核销。 */
async function loadCoupons(): Promise<void> {
  couponsLoading.value = true;
  try {
    // 只取「待使用」的券：已锁定 / 已核销 / 已过期的券不能再用（契约的 status 筛选）
    const result = await commerce.listCoupons({ status: 1, pageSize: 100 });
    coupons.value = (result.list ?? []).filter((coupon) => appliesToProvider(coupon, providerId.value));
  } catch (error) {
    const failure = toApiFailure(error, "加载可用券失败，请稍后重试");
    loadError.value = failure.message;
    loadRequestId.value = failure.requestId;
  } finally {
    couponsLoading.value = false;
  }
}

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (!loggedIn || !hasTarget.value) return;
    form.petId = session.activePet?.id ?? session.pets[0]?.id ?? 0;
    void loadCoupons();
    void loadSlots();
  },
  { immediate: true },
);

/** 换日期 → 重新取号源（切日期与切筛选同形，所以同样要守卫：旧响应不许盖新状态）。 */
watch(
  () => form.date,
  () => {
    if (session.isLoggedIn && hasTarget.value) void loadSlots();
  },
);

const selectedCoupon = computed(() => coupons.value.find((coupon) => coupon.id === form.couponId) ?? null);

const canSubmit = computed(
  () => hasTarget.value && form.petId > 0 && form.startTime !== "" && !submit.submitting.value,
);

/**
 * 提交预约：幂等键在请求真正发出时生成（ADR-0028），被 2 秒闸门拦下的连点根本不会走到这里，
 * 所以连点不会多造键、也不会多下一单；成功后金额三项只收服务端的返回值，前端一个字都不算。
 */
async function placeOrder(): Promise<void> {
  if (!canSubmit.value) return;
  const result = await submit.run(
    () =>
      commerce.createOrder(
        {
          pet_id: form.petId,
          provider_id: providerId.value,
          service_id: serviceId.value,
          appointment_date: form.date,
          start_time: form.startTime,
          coupon_id: form.couponId,
          remark: form.remark.trim() || null,
        },
        newIdempotencyKey(),
      ),
    "下单成功",
  );
  if (result.ok) {
    created.value = result.value;
  }
}

/** 再下一单：把结果卡收起来，保留门店与服务项（用户多半是给另一只宠物或另一个时段再约一次）。 */
function orderAgain(): void {
  created.value = null;
  submit.clear();
  form.startTime = "";
  form.couponId = null;
  form.remark = "";
  void loadSlots();
}

/** 时段文案（起止拼一行）；能不能选不在这里判——模板按 slot.full 独立标「已满」并禁用。 */
function slotText(slot: AppointmentSlotView): string {
  return `${slot.start_time ?? ""}–${slot.end_time ?? ""}`;
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">预约下单</h2>
    <p class="ph-page-desc">
      选好宠物、时段与券就能预约。费用在门店直接付给服务者，平台不经手资金，这里不收款。
    </p>

    <SessionGate forbidden-description="下单需要登录账号，并绑定宠物档案。">
      <!-- 没有门店 / 服务项时不下单：这两个 id 是下单依据，编不出来 -->
      <article v-if="!hasTarget" class="ph-card">
        <StateEmpty
          icon="🩺"
          title="先选好门店与服务项"
          description="下单需要门店与服务项。门店目录与服务项目录还没上线，所以现在可以从「我的订单」里已完成 /
            已取消的订单用「再次预约」进来。"
        >
          <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'orders' }">去我的订单</RouterLink>
          <RouterLink class="ph-button ph-button--text" :to="{ name: 'services' }">看服务页</RouterLink>
        </StateEmpty>
      </article>

      <template v-else>
        <!-- 下单成功：金额三项由服务端给出，这里**只展示**，一个字都不算 -->
        <article v-if="created" class="ph-card ph-order-form__done">
          <h3 class="ph-card__title">下单成功</h3>
          <p class="ph-text-sub">
            {{ created.service_name ?? serviceName ?? "服务项" }} ·
            {{ created.provider_name ?? providerName ?? "门店" }} ·
            {{ formatDate(created.appointment_date) }} {{ created.start_time ?? "" }}–{{ created.end_time ?? "" }}
          </p>
          <OrderAmountCard
            :total-amount="created.total_amount"
            :coupon-discount="created.coupon_discount"
            :estimated-pay-amount="created.estimated_pay_amount"
            :coupon="created.coupon"
          />
          <p class="ph-text-sub">
            门店接单后下单完成。到预约时间可以在订单详情里看到 6 位核销码，到店出示给门店核销。
          </p>
          <div class="ph-form__actions">
            <RouterLink class="ph-button ph-button--primary" :to="{ name: 'order-detail', params: { id: created.id } }">
              查看订单详情
            </RouterLink>
            <button type="button" class="ph-button ph-button--secondary" @click="orderAgain">再约一单</button>
          </div>
        </article>

        <StateError v-else-if="loadError" :message="loadError" :request-id="loadRequestId" @retry="loadCoupons(); loadSlots()" />

        <div v-else class="ph-columns">
          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">预约哪只宠物</h3>
              <p v-if="pets.length === 0" class="ph-text-sub">
                还没有宠物档案。先去「我的」建一份，再来预约。
              </p>
              <ul v-else class="ph-order-form__pets">
                <li v-for="pet in pets" :key="pet.id">
                  <button
                    type="button"
                    class="ph-order-form__pet"
                    :class="{ 'ph-order-form__pet--active': form.petId === pet.id }"
                    @click="form.petId = pet.id"
                  >
                    {{ pet.name }}
                  </button>
                </li>
              </ul>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">预约时段</h3>
              <label class="ph-field ph-order-form__date">
                <span class="ph-field__label">预约日期</span>
                <input v-model="form.date" class="ph-field__input" type="date" :min="todayIso()" />
              </label>

              <StateLoading v-if="slotsLoading" :rows="2" />
              <StateEmpty
                v-else-if="slots.length === 0"
                icon="🕘"
                title="这天没有可约时段"
                description="门店可能那天不营业，或者时段都已经过去了。换一天看看。"
              />
              <template v-else>
                <ul class="ph-order-form__slots">
                  <li v-for="slot in slots" :key="slot.start_time">
                    <button
                      type="button"
                      class="ph-order-form__slot"
                      :class="{ 'ph-order-form__slot--active': form.startTime === slot.start_time }"
                      :disabled="slot.full"
                      @click="form.startTime = slot.start_time ?? ''"
                    >
                      <span>{{ slotText(slot) }}</span>
                      <span class="ph-order-form__slot-state">
                        {{ slot.full ? "已满" : `余 ${slot.available_count ?? 0}` }}
                      </span>
                    </button>
                  </li>
                </ul>
                <p class="ph-text-weak ph-order-form__note">
                  约满的时段会照常列出（灰的「已满」）——直接隐藏的话，你会以为门店那天不营业。
                </p>
              </template>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">备注给门店</h3>
              <textarea
                v-model="form.remark"
                class="ph-field__input ph-order-form__textarea"
                maxlength="255"
                rows="3"
                placeholder="宠物的特殊注意事项，例如怕吹风机、皮肤敏感"
              />
            </article>
          </div>

          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">用哪张券</h3>
              <StateLoading v-if="couponsLoading" :rows="2" />
              <p v-else-if="coupons.length === 0" class="ph-text-sub">
                没有可用于这家门店的券。券由平台定向发放（邀请 / 打卡任务 / 积分兑换 / 平台补贴），没有抢券入口。
              </p>
              <ul v-else class="ph-order-form__coupons">
                <li>
                  <button
                    type="button"
                    class="ph-order-form__no-coupon"
                    :class="{ 'ph-order-form__no-coupon--active': form.couponId === null }"
                    @click="form.couponId = null"
                  >
                    不用券
                  </button>
                </li>
                <li v-for="coupon in coupons" :key="coupon.id">
                  <CouponCard
                    :coupon="coupon"
                    selectable
                    :selected="form.couponId === coupon.id"
                    selected-text="已选"
                    @select="form.couponId = coupon.id ?? null"
                  />
                </li>
              </ul>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">费用</h3>
              <dl class="ph-order-form__estimate">
                <div class="ph-order-form__row">
                  <dt>已选券抵扣</dt>
                  <dd>−{{ formatAmount(selectedCoupon?.face_value ?? "0.00") }}</dd>
                </div>
                <div class="ph-order-form__row">
                  <dt>预估实付</dt>
                  <dd class="ph-text-sub">下单后按门店定价展示</dd>
                </div>
              </dl>
              <p class="ph-order-form__notice">
                预估实付 = 服务总额 − 券面额，它只是预估，不是账；<strong>服务项价格由门店定价</strong>，
                本页在你提交前拿不到（C 端还没有查服务项的接口），下单成功后这一单的预估实付会显示出来。
                <strong>费用在门店直接付给服务者</strong>，平台不经手资金，也不提供线上支付。
              </p>
            </article>

            <article class="ph-card">
              <p v-if="!form.startTime" class="ph-text-weak ph-order-form__note">先选一个预约时段。</p>
              <p v-if="submit.errorMessage.value" class="ph-form__error">
                {{ submit.errorMessage.value }}
                <span v-if="submit.requestId.value" class="ph-text-weak">
                  （请求 ID：{{ submit.requestId.value }}）
                </span>
              </p>
              <button
                type="button"
                class="ph-button ph-button--primary ph-order-form__submit"
                :disabled="!canSubmit"
                @click="placeOrder"
              >
                {{ submit.submitting.value ? "提交中…" : "提交预约" }}
              </button>
              <p class="ph-text-weak ph-order-form__note">
                提交即占用该时段；用券会同时锁定券（取消订单时释放）。
              </p>
            </article>
          </div>
        </div>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-order-form__pets,
.ph-order-form__slots,
.ph-order-form__coupons {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
}

.ph-order-form__coupons {
  flex-direction: column;
}

.ph-order-form__pet,
.ph-order-form__no-coupon {
  height: 32px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-button);
  font-family: inherit;
  font-size: 13px;
  color: var(--ph-color-text);
  cursor: pointer;
}

.ph-order-form__pet--active,
.ph-order-form__no-coupon--active {
  background: var(--ph-color-primary-light);
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-order-form__slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 108px;
  padding: var(--ph-space-2) var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-button);
  font-family: inherit;
  font-size: 13px;
  color: var(--ph-color-text);
  cursor: pointer;
}

.ph-order-form__slot--active {
  background: var(--ph-color-primary-light);
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-order-form__slot:disabled {
  background: var(--ph-color-bg);
  color: var(--ph-color-text-weak);
  cursor: not-allowed;
}

.ph-order-form__slot-state {
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-order-form__date {
  margin-bottom: var(--ph-space-3);
}

.ph-order-form__note {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ph-order-form__textarea {
  height: auto;
  padding: var(--ph-space-2) var(--ph-space-3);
  resize: vertical;
}

.ph-order-form__estimate {
  margin: 0 0 var(--ph-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-order-form__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-order-form__row dt {
  color: var(--ph-color-text-sub);
  font-size: 13px;
}

.ph-order-form__row dd {
  margin: 0;
  font-family: var(--ph-font-numeric);
}

.ph-order-form__notice {
  margin: 0;
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ph-color-text-sub);
}

.ph-order-form__submit {
  width: 100%;
}

.ph-order-form__done {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}
</style>
