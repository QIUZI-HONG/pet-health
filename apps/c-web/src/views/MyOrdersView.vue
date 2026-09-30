<script setup lang="ts">
/**
 * 我的订单（切片 #109；决策见 ADR-0038 第一节 / ADR-0048）。
 *
 * 一页只做两件事：**分页浏览**与**按状态筛选**。取消入口在详情页——取消按阶段分
 * （待接单直接取消、已预约只是申请、履约中不能取消），塞进列表行里既放不下理由输入，
 * 也容易与详情页的说法分叉。
 *
 * **列表里没有核销码**（契约写死）：那是到店凭证，只出现在详情、且到预约时间才可见——
 * 放进列表等于把凭证挂在外面（ADR-0038 第二节）。
 */
import { computed, ref, watch } from "vue";
import type { RouteLocationRaw } from "vue-router";
import { createLatestGuard, formatDate, formatDateTime, toApiFailure } from "@pet-health/shared";
import { commerce, type OrderSummaryView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { ORDER_STATUS, orderStatusView } from "../utils/order-status";
import { formatAmount } from "../utils/money";

const PAGE_SIZE = 20;

/** 状态筛选：`null` 表示全部（契约：不传 status 就是全部）。 */
const STATUS_FILTERS: Array<{ value: number | null; label: string }> = [
  { value: null, label: "全部" },
  { value: ORDER_STATUS.PENDING_ACCEPT, label: "待接单" },
  { value: ORDER_STATUS.BOOKED, label: "已预约" },
  { value: ORDER_STATUS.IN_SERVICE, label: "履约中" },
  { value: ORDER_STATUS.COMPLETED, label: "已完成" },
  { value: ORDER_STATUS.CANCELLED, label: "已取消" },
];

const session = useSessionStore();
const orders = ref<OrderSummaryView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const statusFilter = ref<number | null>(null);
const page = ref(1);
const total = ref(0);

/** 并发守卫：连点状态筛选或翻页时，先发的请求可能后回来，用旧结果盖掉新筛选（实现见 shared）。 */
const latest = createLatestGuard();

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)));

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const result = await commerce.listOrders(
      { status: statusFilter.value ?? undefined, page: targetPage, pageSize: PAGE_SIZE },
      signal,
    );
    if (!latest.isCurrent(token)) return;
    orders.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? orders.value.length;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载订单失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

function selectStatus(value: number | null): void {
  if (statusFilter.value === value) return;
  statusFilter.value = value;
  void load(1);
}

/** 会话就绪后再拉数据：未登录时直接发请求只会拿回 40100，把「登录后查看」变成一条报错。 */
watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load(1);
  },
  { immediate: true },
);

/** 已完成 / 已取消的单可以照原样再约一次——这是目前唯一能带着服务项进入下单页的入口。 */
function canRebook(status: number | null | undefined): boolean {
  return status === ORDER_STATUS.COMPLETED || status === ORDER_STATUS.CANCELLED;
}

function rebookTarget(order: OrderSummaryView): RouteLocationRaw {
  return {
    name: "order-create",
    query: {
      provider_id: order.provider_id,
      service_id: order.service_id,
      // 仅用于展示的名字（取自订单）；下单依据是上面两个 id，页面会写明这一点
      service_name: order.service_name,
      provider_name: order.provider_name,
    },
  };
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">我的订单</h2>
    <p class="ph-page-desc">
      预约、履约与核销都在这里。费用在门店直接付给服务者，平台不经手资金，也没有线上支付。
    </p>

    <SessionGate forbidden-description="登录后查看你的预约与订单。">
      <div class="ph-orders__filters">
        <button
          v-for="filter in STATUS_FILTERS"
          :key="String(filter.value)"
          type="button"
          class="ph-orders__filter"
          :class="{ 'ph-orders__filter--active': statusFilter === filter.value }"
          @click="selectStatus(filter.value)"
        >
          {{ filter.label }}
        </button>
      </div>

      <StateLoading v-if="loading" :rows="4" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

      <article v-else-if="orders.length === 0" class="ph-card">
        <StateEmpty
          icon="🧾"
          title="还没有订单"
          :description="
            statusFilter === null
              ? '从服务页选好门店与服务项后就能预约。'
              : '这个状态下没有订单，换个筛选看看。'
          "
        >
          <RouterLink v-if="statusFilter !== null" class="ph-button ph-button--secondary" :to="{ name: 'orders' }">
            查看全部
          </RouterLink>
          <RouterLink v-else class="ph-button ph-button--secondary" :to="{ name: 'services' }">去服务页</RouterLink>
        </StateEmpty>
      </article>

      <template v-else>
        <ul class="ph-orders__list">
          <li v-for="order in orders" :key="order.id" class="ph-card ph-orders__item">
            <div class="ph-orders__head">
              <span class="ph-orders__status" :class="`ph-orders__status--${orderStatusView(order.status).tone}`">
                {{ orderStatusView(order.status).label }}
              </span>
              <span class="ph-orders__no">订单号 {{ order.order_no ?? "—" }}</span>
              <span class="ph-text-weak">下单于 {{ formatDateTime(order.created_at) }}</span>
            </div>

            <div class="ph-orders__main">
              <div class="ph-orders__info">
                <p class="ph-orders__title">
                  {{ order.service_name ?? "服务项" }}
                  <span class="ph-text-sub">· {{ order.provider_name ?? "门店" }}</span>
                </p>
                <p class="ph-orders__meta">
                  宠物：{{ order.pet_name ?? "—" }} ·
                  预约：{{ formatDate(order.appointment_date) }} {{ order.start_time ?? "" }}–{{ order.end_time ?? "" }}
                </p>
                <p class="ph-orders__hint ph-text-sub">{{ orderStatusView(order.status).hint }}</p>
              </div>

              <div class="ph-orders__money">
                <span class="ph-text-weak">预估实付</span>
                <span class="ph-orders__pay">{{ formatAmount(order.estimated_pay_amount) }}</span>
                <span class="ph-text-weak">
                  总额 {{ formatAmount(order.total_amount) }} · 券抵扣 {{ formatAmount(order.coupon_discount) }}
                </span>
              </div>
            </div>

            <div class="ph-orders__actions">
              <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'order-detail', params: { id: order.id } }">
                查看详情
              </RouterLink>
              <RouterLink v-if="canRebook(order.status)" class="ph-button ph-button--text" :to="rebookTarget(order)">
                再次预约
              </RouterLink>
            </div>
          </li>
        </ul>

        <div class="ph-orders__pager">
          <span class="ph-text-weak">共 {{ total }} 条，第 {{ page }} / {{ totalPages }} 页</span>
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
.ph-orders__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-4);
}

.ph-orders__filter {
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

.ph-orders__filter--active {
  background: var(--ph-color-primary-light);
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-orders__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-orders__head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  flex-wrap: wrap;
  font-size: 12px;
}

.ph-orders__status {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

.ph-orders__status--waiting {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-orders__status--active {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-orders__status--done {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary-dark);
}

.ph-orders__no {
  font-family: var(--ph-font-numeric);
  color: var(--ph-color-text-sub);
}

.ph-orders__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-4);
  margin-top: var(--ph-space-3);
}

.ph-orders__title {
  margin: 0 0 var(--ph-space-1);
  font-size: 15px;
  font-weight: 600;
}

.ph-orders__meta,
.ph-orders__hint {
  margin: 0;
  font-size: 13px;
}

.ph-orders__hint {
  margin-top: var(--ph-space-1);
  font-size: 12px;
}

.ph-orders__money {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--ph-space-1);
  font-size: 12px;
  text-align: right;
}

.ph-orders__pay {
  font-family: var(--ph-font-numeric);
  font-size: 20px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-orders__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-orders__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
