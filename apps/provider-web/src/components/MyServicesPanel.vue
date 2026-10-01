<script setup lang="ts">
/**
 * 我的服务项：列表（不默认过滤状态）、改价、上架 / 下架。
 *
 * 三个动作各有各的口径（contract/provider.yaml 的 `/services`）：
 * - **改价 = 重新审核**：`PUT /services/{id}` 让服务项回到「待审核」，已上架的会先从前台撤下——
 *   价格是对外承诺，改了要有人再看一眼。页面在提交前把这句话说清楚。
 * - **上架 / 下架只在审核通过之后**：待审核（0）与已驳回（3）是审核流程的结果，服务者设不了；
 *   已驳回的要先改价重新提交。按钮按状态禁用，并把原因写在按钮旁边，不做「点了才报错」。
 * - **区间是现取的**（不是下单时的快照）：平台收窄区间后，存量服务项不会自动下架（ADR-0034），
 *   所以这里对「当前定价已不在区间内」的服务项给一条提示，让服务者在改价时收敛。
 *
 * 驳回原因（`reject_reason`）必须显示出来——服务者要能自己回答「卡在哪」。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { providerApp, type ServiceRow } from "../api/providerApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatAmountRange, isPriceFormatValid, isWithinRange, priceRangeMessage, serviceStatusLabel, serviceStatusTone } from "@pet-health/shared";

const props = defineProps<{
  /** 能不能改价 / 上下架（要求入驻通过且有一份没过期的资质） */
  writable: boolean;
  blockReason: string;
}>();

const emit = defineEmits<{ changed: [] }>();

const statusFilter = ref("");

const services = usePagedList<ServiceRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listServices(
      { status: statusFilter.value === "" ? undefined : Number(statusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "服务项加载失败，请稍后重试" },
);

const submit = useSubmitAction("操作失败，请稍后重试");
/** 正在改价的那一行 */
const editing = ref<ServiceRow | null>(null);
const newPrice = ref("");

function reload(): void {
  void services.reload();
}

function openPriceEditor(row: ServiceRow): void {
  submit.clear();
  editing.value = row;
  newPrice.value = row.price;
}

/** 改价输入框上的提示：格式与区间一起判，越界直接给出与后端 90001 逐字一致的那句（ADR-0034） */
const editHint = ref("");
function onPriceInput(): void {
  const row = editing.value;
  if (!row) return;
  if (!isPriceFormatValid(newPrice.value)) {
    editHint.value = "价格要大于 0，最多两位小数";
    return;
  }
  if (isWithinRange(newPrice.value, row.price_min, row.price_max) === false) {
    editHint.value = priceRangeMessage(row.price_min, row.price_max);
    return;
  }
  editHint.value = "";
}

async function confirmPrice(): Promise<void> {
  const row = editing.value;
  if (!row) return;
  onPriceInput();
  if (editHint.value !== "") {
    submit.errorMessage.value = editHint.value;
    return;
  }
  const outcome = await submit.run(
    () => providerApp.updateServicePrice(row.id, newPrice.value.trim()),
    "已提交改价：回到待审核，通过前不在前台展示",
  );
  if (!outcome.ok) return;
  editing.value = null;
  emit("changed");
  reload();
}

/**
 * 上架（1）/ 下架（2）。按钮能不能点由状态决定：
 * - 待审核（0）：两个都不行——审核中不能动；
 * - 已驳回（3）：只能改价重提，不能直接上架；
 * - 已上架（1）：只能下架；已下架（2）：只能上架。
 */
function canGoOnline(status?: number): boolean {
  return status === 2;
}

function canGoOffline(status?: number): boolean {
  return status === 1;
}

function statusHint(status?: number): string {
  switch (status) {
    case 0:
      return "审核中：通过后才能上架";
    case 3:
      return "已被驳回：改价后可重新提交审核";
    default:
      return "";
  }
}

async function toggleStatus(row: ServiceRow, next: 1 | 2): Promise<void> {
  const outcome = await submit.run(
    () => providerApp.updateServiceStatus(row.id, next),
    next === 1 ? "已上架" : "已下架",
  );
  if (!outcome.ok) return;
  emit("changed");
  reload();
}

/** 当前定价已经不在目录给的最新区间内（平台收窄过区间，ADR-0034：不会自动下架） */
function outOfRange(row: ServiceRow): boolean {
  return isWithinRange(row.price, row.price_min, row.price_max) === false;
}

// 首屏加载一次：我的服务列表「打开就该有内容」，等用户点刷新等于空页
void services.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-svc__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="0">待审核</option>
          <option value="1">已上架</option>
          <option value="2">已下架</option>
          <option value="3">已驳回</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="services.loading.value" @click="reload">
        刷新
      </button>
    </div>

    <p v-if="!props.writable && props.blockReason" class="ph-alert ph-alert--warn ph-svc__alert">
      {{ props.blockReason }}
    </p>
    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-svc__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-svc__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="services.loading.value"
      :forbidden="services.forbidden.value"
      :error-message="services.errorMessage.value"
      :request-id="services.requestId.value"
      :is-empty="services.isEmpty.value"
      loading-title="正在加载服务项"
      forbidden-title="暂无权限"
      forbidden-description="这个账号的令牌不能读取服务项列表。"
      empty-title="还没有选品"
      empty-description="去「标准目录」里勾一个项目、填上区间内的价格，就会出现在这里。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>项目</th>
              <th>分类</th>
              <th>定价</th>
              <th>平台区间</th>
              <th>状态</th>
              <th>驳回原因</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in services.items.value" :key="row.id">
              <td>
                {{ row.service_name ?? row.service_code }}
                <span class="ph-text-weak ph-svc__code">{{ row.service_code }}</span>
              </td>
              <td>{{ row.category_name ?? "—" }}</td>
              <td class="ph-table__num">
                {{ formatAmount(row.price) }}
                <span v-if="outOfRange(row)" class="ph-tag ph-tag--warning">已超出区间</span>
              </td>
              <td class="ph-table__num">{{ formatAmountRange(row.price_min, row.price_max) }}</td>
              <td><span class="ph-tag" :class="serviceStatusTone(row.status)">{{ serviceStatusLabel(row.status) }}</span></td>
              <td>{{ row.reject_reason ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="!props.writable"
                    :title="props.writable ? '' : props.blockReason"
                    @click="openPriceEditor(row)"
                  >
                    改价
                  </button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="!props.writable || !canGoOnline(row.status)"
                    :title="statusHint(row.status)"
                    @click="toggleStatus(row, 1)"
                  >
                    上架
                  </button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="!props.writable || !canGoOffline(row.status)"
                    @click="toggleStatus(row, 2)"
                  >
                    下架
                  </button>
                </div>
                <span v-if="statusHint(row.status)" class="ph-text-weak ph-svc__hint">{{ statusHint(row.status) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ services.total.value }} 项</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="services.page.value <= 1 || services.loading.value"
          @click="services.prevPage"
        >
          上一页
        </button>
        <span>第 {{ services.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!services.hasMore.value || services.loading.value"
          @click="services.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="editing" class="ph-card ph-svc__form" @submit.prevent="confirmPrice">
      <h4 class="ph-card__title">改价：{{ editing.service_name ?? editing.service_code }}</h4>
      <p class="ph-alert ph-alert--warn">
        改价后回到待审核，通过前不在前台展示（价格是对外承诺，改了要有人再看一眼）。
      </p>
      <label class="ph-field ph-svc__price">
        <span class="ph-field__label">新定价（元）</span>
        <input
          v-model="newPrice"
          class="ph-input"
          :class="{ 'ph-input--invalid': editHint !== '' }"
          inputmode="decimal"
          @input="onPriceInput"
        />
        <span class="ph-field__hint" :class="{ 'ph-field__hint--error': editHint !== '' }">
          {{ editHint || priceRangeMessage(editing.price_min, editing.price_max) }}
        </span>
      </label>
      <div class="ph-svc__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "提交改价" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="editing = null">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-svc__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-svc__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-svc__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-svc__code,
.ph-svc__hint {
  display: block;
  font-size: 12px;
}

.ph-svc__form {
  margin-top: var(--ph-space-5);
}

.ph-svc__price {
  max-width: 320px;
  margin-top: var(--ph-space-4);
}

.ph-svc__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
