<script setup lang="ts">
/**
 * 服务上架审核：服务者提交的选品定价逐条过审。
 *
 * 契约把「判断依据」和每一行放在了一起：列表里的**名称与区间是现取的**（不是提交时的快照），
 * 所以「这个价是不是还在区间内」不用再打开另一个页面去对。这一页据此做了两件事：
 * 1. 行内直接标出「当前定价 vs 当前区间」，越界的行打红标（区间可能被平台收窄过）；
 * 2. 通过 / 驳回都要求待审核状态（其余状态后端 40900），驳回原因必填且会展示给服务者。
 *
 * 通过即上架（服务者提交审核的意图就是上架，交付文档 7.2 的默认值也是上架），
 * 所以按钮文案写「通过并上架」，不让人误以为还要再点一次。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { adminApp, type ListingRow } from "../api/adminApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatAmountRange, isWithinRange, serviceStatusLabel, serviceStatusTone } from "@pet-health/shared";

const statusFilter = ref("0");
/** 按服务者筛（从服务者列表点了「看它家的服务」跳过来时用） */
const providerIdFilter = ref("");

const listings = usePagedList<ListingRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listServiceListings(
      {
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        providerId: providerIdFilter.value.trim() === "" ? undefined : Number(providerIdFilter.value.trim()),
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "上架审核队列加载失败，请稍后重试" },
);

const submit = useSubmitAction("审核失败，请稍后重试");
/** 正在填写驳回原因的那一条 */
const rejecting = ref<ListingRow | null>(null);
const rejectReason = ref("");

function reload(): void {
  void listings.reload();
}

async function approve(row: ListingRow): Promise<void> {
  const outcome = await submit.run(() => adminApp.approveServiceListing(row.id), "已通过并上架");
  if (outcome.ok) reload();
}

function openReject(row: ListingRow): void {
  submit.clear();
  rejecting.value = row;
  rejectReason.value = "";
}

async function confirmReject(): Promise<void> {
  const row = rejecting.value;
  if (!row) return;
  if (rejectReason.value.trim() === "") {
    submit.errorMessage.value = "驳回原因必填：它会展示给服务者，改价后可以重新提交";
    return;
  }
  const outcome = await submit.run(() => adminApp.rejectServiceListing(row.id, rejectReason.value.trim()), "已驳回");
  if (!outcome.ok) return;
  rejecting.value = null;
  reload();
}

/** 当前定价是否已经不在目录给的最新区间内（区间收窄过时会出现，ADR-0034 不会自动下架） */
function outOfRange(row: ListingRow): boolean {
  return isWithinRange(row.price, row.price_min, row.price_max) === false;
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-listing__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="0">待审核</option>
          <option value="1">已上架</option>
          <option value="2">已下架</option>
          <option value="3">已驳回</option>
          <option value="">全部</option>
        </select>
      </label>
      <label class="ph-field ph-listing__filter">
        <span class="ph-field__label">服务者 id</span>
        <input v-model="providerIdFilter" class="ph-input ph-listing__id" placeholder="可选：只看某一家" @keyup.enter="reload" />
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="listings.loading.value" @click="reload">
        刷新
      </button>
      <span class="ph-text-weak">区间是现取的，不是提交时的快照</span>
    </div>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-listing__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-listing__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="listings.loading.value"
      :forbidden="listings.forbidden.value"
      :error-message="listings.errorMessage.value"
      :request-id="listings.requestId.value"
      :is-empty="listings.isEmpty.value"
      loading-title="正在加载上架审核队列"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取上架审核队列。"
      empty-title="没有待审的服务项"
      empty-description="换个状态筛选看看。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>服务项</th>
              <th>服务者</th>
              <th>分类</th>
              <th>定价</th>
              <th>当前区间</th>
              <th>状态</th>
              <th>提交时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in listings.items.value" :key="row.id">
              <td>
                {{ row.service_name ?? row.service_code }}
                <span class="ph-text-weak ph-listing__code">{{ row.service_code }}</span>
              </td>
              <td>{{ row.provider_name ?? `#${row.provider_id ?? "—"}` }}</td>
              <td>{{ row.category_name ?? row.category_code ?? "—" }}</td>
              <td class="ph-table__num">{{ formatAmount(row.price) }}</td>
              <td class="ph-table__num">
                {{ formatAmountRange(row.price_min, row.price_max) }}
                <span v-if="outOfRange(row)" class="ph-tag ph-tag--danger">已超出区间</span>
              </td>
              <td><span class="ph-tag" :class="serviceStatusTone(row.status)">{{ serviceStatusLabel(row.status) }}</span></td>
              <td class="ph-table__num">{{ row.submitted_at ? formatDateTime(row.submitted_at) : "—" }}</td>
              <td>
                <div class="ph-table__actions">
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="row.status !== 0 || submit.submitting.value"
                    :title="row.status === 0 ? '' : '只有待审核的服务项能改结论'"
                    @click="approve(row)"
                  >
                    通过并上架
                  </button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="row.status !== 0 || submit.submitting.value"
                    @click="openReject(row)"
                  >
                    驳回
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ listings.total.value }} 条</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="listings.page.value <= 1 || listings.loading.value"
          @click="listings.prevPage"
        >
          上一页
        </button>
        <span>第 {{ listings.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!listings.hasMore.value || listings.loading.value"
          @click="listings.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="rejecting" class="ph-card ph-listing__form" @submit.prevent="confirmReject">
      <h4 class="ph-card__title">驳回：{{ rejecting.service_name ?? rejecting.service_code }}</h4>
      <p class="ph-field__hint">
        定价 {{ formatAmount(rejecting.price) }}，区间 {{ formatAmountRange(rejecting.price_min, rejecting.price_max) }}。
        原因会展示给服务者，他改价后可重新提交审核。
      </p>
      <label class="ph-field">
        <span class="ph-field__label">驳回原因（必填）</span>
        <textarea v-model="rejectReason" class="ph-textarea" maxlength="255" placeholder="如：定价与同类服务差异过大，请调整到区间内偏低的位置" />
      </label>
      <div class="ph-listing__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "确认驳回" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="rejecting = null">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-listing__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-listing__filter .ph-select {
  width: auto;
  min-width: 110px;
}

.ph-listing__id {
  width: 160px;
}

.ph-listing__code {
  display: block;
  font-size: 12px;
}

.ph-listing__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-listing__form {
  margin-top: var(--ph-space-5);
  max-width: 720px;
}

.ph-listing__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
