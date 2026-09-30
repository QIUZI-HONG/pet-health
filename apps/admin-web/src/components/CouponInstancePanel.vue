<script setup lang="ts">
/**
 * 券实例查询与排查（`GET /api/v1/admin/coupons`）。
 *
 * 这一段的用途是**排查**而不是运营日常：用户说「我那张券呢」、服务者说「这张券核销不了」、
 * 或者对账不平要找出是哪一张，都在这里按券码 / 用户 / 模板 / 状态收窄。所以：
 * - 每个筛选条件都对应一个用户能说出来的线索（券码在用户手上、用户 id 在工单里、
 *   模板与来源能定位到哪一批发出去的），没有「按运营习惯」发明的筛选项；
 * - 行里给全 `face_value / min_amount`（**发放时的快照**，模板后来改过面额也不影响它）
 *   与 `provider_name`（服务者成本券的核销门店；平台补贴券没有这一列——核销成本在平台）。
 */
import { ref } from "vue";
import {
  formatAmount,
  formatDateTime,
  couponSourceLabel as sourceLabel,
  couponStatusLabel as statusLabel,
} from "@pet-health/shared";
import { ConsoleListState, usePagedList } from "@pet-health/ui";
import { adminApp, type CouponRow } from "../api/adminApi";

const templateId = ref("");
const source = ref("");
const status = ref("");
const userId = ref("");

const coupons = usePagedList<CouponRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listCoupons(
      {
        templateId: templateId.value.trim() === "" ? undefined : Number(templateId.value.trim()),
        source: source.value === "" ? undefined : Number(source.value),
        status: status.value === "" ? undefined : Number(status.value),
        userId: userId.value.trim() === "" ? undefined : Number(userId.value.trim()),
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "券实例加载失败，请稍后重试" },
);

function reload(): void {
  void coupons.reload();
}

function statusTone(value: number): string {
  if (value === 3) return "ph-tag--success";
  if (value === 4) return "ph-tag--warning";
  if (value === 2) return "ph-tag--info";
  return "";
}

// 首屏自动拉一次全量（最有用的默认视角就是「最近发出去的券」）
void coupons.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-ci__filter">
        <span class="ph-field__label">模板 id</span>
        <input v-model="templateId" class="ph-input ph-ci__num" inputmode="numeric" placeholder="可选" @keyup.enter="reload" />
      </label>
      <label class="ph-field ph-ci__filter">
        <span class="ph-field__label">用户 id</span>
        <input v-model="userId" class="ph-input ph-ci__num" inputmode="numeric" placeholder="可选" @keyup.enter="reload" />
      </label>
      <label class="ph-field ph-ci__filter">
        <span class="ph-field__label">来源</span>
        <select v-model="source" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">邀请</option>
          <option value="2">打卡任务</option>
          <option value="3">积分兑换</option>
          <option value="4">平台补贴</option>
          <option value="5">月度阶梯</option>
        </select>
      </label>
      <label class="ph-field ph-ci__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="status" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">待使用</option>
          <option value="2">已锁定</option>
          <option value="3">已核销</option>
          <option value="4">已过期</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="coupons.loading.value" @click="reload">查询</button>
    </div>

    <ConsoleListState
      :loading="coupons.loading.value"
      :forbidden="coupons.forbidden.value"
      :error-message="coupons.errorMessage.value"
      :request-id="coupons.requestId.value"
      :is-empty="coupons.isEmpty.value"
      loading-title="正在加载券实例"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取券实例。"
      empty-title="没有匹配的券"
      empty-description="换个筛选条件看看；「全部」也空的话说明这个模板还没发过券。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>券码</th>
              <th>模板</th>
              <th>用户 id</th>
              <th>面额 / 门槛</th>
              <th>来源</th>
              <th>状态</th>
              <th>核销门店</th>
              <th>有效期至</th>
              <th>发放时间</th>
              <th>核销时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in coupons.items.value" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.template_code }} · {{ row.template_name }}</td>
              <td class="ph-table__num">{{ row.user_id }}</td>
              <td class="ph-table__num">{{ formatAmount(row.face_value) }} / 满 {{ formatAmount(row.min_amount) }}</td>
              <td>{{ sourceLabel(row.source) }}</td>
              <td><span class="ph-tag" :class="statusTone(row.status)">{{ statusLabel(row.status) }}</span></td>
              <td>{{ row.provider_name ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.valid_until) }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.issued_at) }}</td>
              <td class="ph-table__num">{{ row.redeemed_at ? formatDateTime(row.redeemed_at) : "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ coupons.total.value }} 张</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="coupons.page.value <= 1 || coupons.loading.value"
          @click="coupons.prevPage"
        >
          上一页
        </button>
        <span>第 {{ coupons.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!coupons.hasMore.value || coupons.loading.value"
          @click="coupons.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>
  </div>
</template>

<style scoped>
.ph-ci__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-ci__filter .ph-select {
  width: auto;
  min-width: 110px;
}

.ph-ci__num {
  width: 110px;
}
</style>
