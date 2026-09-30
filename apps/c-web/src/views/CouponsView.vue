<script setup lang="ts">
/**
 * 我的券（切片 #110；决策见 ADR-0037 第三节 / ADR-0044）。
 *
 * 券包里只有三件事：**按状态与来源筛选**、看清面额 / 门槛 / 有效期、以及把「已锁定」的券释放掉。
 *
 * 两条刻意不做的事：
 *
 *  1. **没有抢券 / 领券入口**：券由平台定向发放（邀请 / 打卡任务 / 积分兑换 / 平台补贴），
 *     契约写死不做抢券（ADR-0037 第三节）；
 *  2. **待使用的券不给「锁定」按钮**：锁定是下单动作的占用，由订单持有、也由订单释放
 *     （ADR-0038 第一节）。单独锁一张而不下单，会留下一张没人释放的锁——契约自己也把
 *     「无主的锁怎么收场」标成待澄清。反过来，**已锁定**的券要能释放：锁若因下单中途失败而留下，
 *     用户得有办法自己解开。
 */
import { computed, ref, watch } from "vue";
import { createLatestGuard, formatDate, newIdempotencyKey, toApiFailure } from "@pet-health/shared";
import { commerce, type CouponView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import CouponCard from "../components/CouponCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { COUPON_SOURCE_OPTIONS, COUPON_STATUS, COUPON_STATUS_OPTIONS } from "../utils/coupon";
import { useSubmitAction } from "@pet-health/ui";

const PAGE_SIZE = 20;

const session = useSessionStore();
const coupons = ref<CouponView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 筛选：`null` = 全部（契约：不传该参数就是全部）。 */
const statusFilter = ref<number | null>(null);
const sourceFilter = ref<number | null>(null);
const page = ref(1);
const total = ref(0);

const releaseAction = useSubmitAction("释放失败，请稍后重试");
const latest = createLatestGuard();

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)));

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  releaseAction.clear();
  try {
    const result = await commerce.listCoupons(
      {
        status: statusFilter.value ?? undefined,
        source: sourceFilter.value ?? undefined,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!latest.isCurrent(token)) return;
    coupons.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? coupons.value.length;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载券失败，请稍后重试");
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
    if (loggedIn) void load(1);
  },
  { immediate: true },
);

function selectStatus(value: string): void {
  statusFilter.value = value === "" ? null : Number(value);
  void load(1);
}

function selectSource(value: string): void {
  sourceFilter.value = value === "" ? null : Number(value);
  void load(1);
}

/** 释放一张已锁定的券。券被未结束的订单持有时服务端给 40900——照实说「先取消那一单」。 */
async function release(coupon: CouponView): Promise<void> {
  const target = coupon.id;
  if (!target) return;
  const result = await releaseAction.run(() => commerce.releaseCoupon(target, newIdempotencyKey()));
  if (!result.ok) return;
  coupons.value = coupons.value.map((item) => (item.id === target ? result.value : item));
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">我的券</h2>
    <p class="ph-page-desc">
      券是到店抵扣凭证：面额与门槛只在门店结算时体现，平台不经手资金。服务者贡献的券只能在其本店核销。
    </p>

    <SessionGate forbidden-description="登录后查看你的券。">
      <div class="ph-coupons__filters">
        <label class="ph-field ph-field--inline">
          <span class="ph-field__label">状态</span>
          <select class="ph-field__input" :value="statusFilter ?? ''" @change="selectStatus(($event.target as HTMLSelectElement).value)">
            <option value="">全部</option>
            <option v-for="option in COUPON_STATUS_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="ph-field ph-field--inline">
          <span class="ph-field__label">来源</span>
          <select class="ph-field__input" :value="sourceFilter ?? ''" @change="selectSource(($event.target as HTMLSelectElement).value)">
            <option value="">全部</option>
            <option v-for="option in COUPON_SOURCE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
      </div>

      <StateLoading v-if="loading" :rows="3" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

      <article v-else-if="coupons.length === 0" class="ph-card">
        <StateEmpty
          icon="🎟️"
          title="这里还没有券"
          :description="
            statusFilter !== null || sourceFilter !== null
              ? '这个筛选组合下没有券，换个条件看看。'
              : '券由平台定向发放：邀请好友、完成打卡任务、用积分兑换都会得到券，没有抢券入口。'
          "
        >
          <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'invites' }">去邀请好友</RouterLink>
          <RouterLink class="ph-button ph-button--text" :to="{ name: 'points' }">用积分兑券</RouterLink>
        </StateEmpty>
      </article>

      <template v-else>
        <p v-if="releaseAction.errorMessage.value" class="ph-form__error">
          {{ releaseAction.errorMessage.value }}
          <span v-if="releaseAction.requestId.value" class="ph-text-weak">
            （请求 ID：{{ releaseAction.requestId.value }}）
          </span>
        </p>

        <ul class="ph-coupons__list">
          <li v-for="coupon in coupons" :key="coupon.id">
            <CouponCard :coupon="coupon">
              <template #actions>
                <button
                  v-if="coupon.status === COUPON_STATUS.LOCKED"
                  type="button"
                  class="ph-button ph-button--secondary"
                  :disabled="releaseAction.submitting.value"
                  @click="release(coupon)"
                >
                  {{ releaseAction.submitting.value ? "处理中…" : "释放这张券" }}
                </button>
              </template>
            </CouponCard>
            <p class="ph-text-weak ph-coupons__issued">发放于 {{ formatDate(coupon.issued_at) }}</p>
          </li>
        </ul>

        <div class="ph-coupons__pager">
          <span class="ph-text-weak">共 {{ total }} 张，第 {{ page }} / {{ totalPages }} 页</span>
          <button type="button" class="ph-button ph-button--secondary" :disabled="page <= 1 || loading" @click="load(page - 1)">
            上一页
          </button>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="page >= totalPages || loading"
            @click="load(page + 1)"
          >
            下一页
          </button>
        </div>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-coupons__filters {
  display: flex;
  gap: var(--ph-space-4);
  margin-bottom: var(--ph-space-4);
}

.ph-coupons__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-3);
}

.ph-coupons__issued {
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-coupons__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

@media (max-width: 1080px) {
  .ph-coupons__list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
