<script setup lang="ts">
/**
 * 某个项目有哪些门店能做（按项目找店的落点）。
 *
 * 一行 = 一家店 + **这家店的定价** + 下单入口：`service_id` 由服务端给（就是下单要传的那个），
 * 所以这一页点「预约」能直接到下单一页，用户不必再回门店详情找项目。
 *
 * 三条与别处一致的口径：
 *   - **不需要登录**（ADR-0037 第一节）：能看能比价；只有「预约」这个动作要身份；
 *   - **40400 是「这个项目不对外」**（不存在 / 已停用 / 已软删），给回项目列表的出口；
 *     而「暂时没有门店做」是 200 + 空列表——两者必须分开，前者重试没有意义；
 *   - **区间价与门店报价要分清**：这一页显示的是门店自己的定价，不是平台区间。
 */
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ApiError, createLatestGuard, toApiFailure } from "@pet-health/shared";
import { catalog, type CatalogItemProviderView } from "../api/catalog";
import { useSessionStore } from "../stores/session";
import { formatAmount } from "../utils/money";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const PAGE_SIZE = 20;

const route = useRoute();
const session = useSessionStore();

const rows = ref<CatalogItemProviderView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 「这个项目不对外」（40400）：与加载失败分开——重试没有意义。 */
const unavailable = ref(false);
const page = ref(1);
const total = ref(0);

/** 并发守卫：在项目之间来回切时，先发的请求可能后回来（实现见 shared）。 */
const latest = createLatestGuard();

const itemCode = computed(() => String(route.params.code ?? ""));

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  unavailable.value = false;
  try {
    const result = await catalog.providersOfItem(itemCode.value, { page: targetPage, pageSize: PAGE_SIZE }, signal);
    if (!latest.isCurrent(token)) return;
    rows.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? rows.value.length;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    if (error instanceof ApiError && error.code === 40400) {
      unavailable.value = true;
      rows.value = [];
      return;
    }
    const failure = toApiFailure(error, "加载门店失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

watch(itemCode, () => void load(1), { immediate: true });

/**
 * 「预约」的目标：已登录带上门店与服务项进下单页；未登录先去登录并带回来处。
 * 与门店详情页同一套分流——两处不一致会让用户以为其中一个按钮坏了。
 */
function bookTarget(row: CatalogItemProviderView) {
  if (!session.isLoggedIn) {
    return { name: "login", query: { redirect: route.fullPath } };
  }
  return {
    name: "order-create",
    query: {
      provider_id: row.provider_id,
      service_id: row.service_id,
      provider_name: row.provider_name,
    },
  };
}
</script>

<template>
  <section>
    <p class="ph-text-weak">
      <RouterLink :to="{ name: 'catalog' }">← 返回按项目找服务</RouterLink>
    </p>
    <h2 class="ph-page-title">哪些门店能做</h2>
    <p class="ph-page-desc">价格是各家门店在平台区间内的定价，可以逐家比较；费用在门店直接付给服务者。</p>

    <StateLoading v-if="loading" :rows="4" />
    <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

    <article v-else-if="unavailable" class="ph-card">
      <StateEmpty
        icon="📋"
        title="这个项目暂不可浏览"
        description="它可能已下架或还在整理中。看看别的项目吧。"
      >
        <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'catalog' }">回项目列表</RouterLink>
      </StateEmpty>
    </article>

    <article v-else-if="rows.length === 0" class="ph-card">
      <StateEmpty
        icon="🏠"
        title="暂时没有门店做这个项目"
        description="平台正在接入服务者，过些时候再来看看。"
      >
        <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'catalog' }">换个项目</RouterLink>
      </StateEmpty>
    </article>

    <template v-else>
      <ul class="ph-catalog-item__list">
        <li v-for="row in rows" :key="row.service_id ?? `${row.provider_id}`" class="ph-card ph-catalog-item__row">
          <div class="ph-catalog-item__main">
            <div>
              <p class="ph-catalog-item__name">{{ row.provider_name ?? "门店" }}</p>
              <p class="ph-catalog-item__meta ph-text-sub">
                <span class="ph-catalog-item__tag">{{ row.type_name ?? "服务者" }}</span>
                <span>评分 {{ row.rating ?? "—" }}</span>
                <span>{{ row.address ?? "—" }}</span>
              </p>
            </div>
            <div class="ph-catalog-item__price">
              <span class="ph-catalog-item__amount">{{ formatAmount(row.price) }}</span>
              <span v-if="row.price_unit" class="ph-text-weak">/ {{ row.price_unit }}</span>
            </div>
          </div>

          <div class="ph-catalog-item__actions">
            <RouterLink
              class="ph-button ph-button--secondary"
              :to="{ name: 'provider-detail', params: { id: row.provider_id } }"
            >
              门店详情
            </RouterLink>
            <RouterLink class="ph-button ph-button--primary" :to="bookTarget(row)">预约</RouterLink>
          </div>
        </li>
      </ul>

      <div class="ph-catalog-item__pager">
        <span class="ph-text-weak">共 {{ total }} 家，第 {{ page }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="page <= 1 || loading"
          @click="load(page - 1)"
        >
          上一页
        </button>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="rows.length < PAGE_SIZE || loading"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ph-catalog-item__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-catalog-item__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-catalog-item__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ph-catalog-item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-catalog-item__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary);
}

.ph-catalog-item__price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.ph-catalog-item__amount {
  font-family: var(--ph-font-numeric);
  font-size: 20px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-catalog-item__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-catalog-item__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
