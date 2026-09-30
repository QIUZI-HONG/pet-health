<script setup lang="ts">
/**
 * 服务者列表与经营状态处置：解冻（1）/ 清退（冻结 3）。
 *
 * 按 ADR-0037 的权限矩阵，**清退属超级管理员**，运营不能做；解冻是同一个状态接口的复位动作
 * （矩阵里没有把它单独划走，且可逆），仍留给运营。
 *
 * 角色细分还没落成（后台账号体系未实现，见 ADR-0035「需要协调」），令牌里只有登录域、没有角色
 * ——**前端没有任何可信来源能判断「谁是超管」**。所以清退入口按 ADR-0037 的处理是
 * **不渲染**（`session.isSuperAdmin` 当前恒为 false），并在这里写明原因与补法；
 * 不摆一个「点了才提醒你越权」的按钮：界面假装拦住不算拦住，而清退是不可逆的。
 *
 * 契约里的三条边界（contract/admin.yaml 的 `/providers/{provider_id}/status`）：
 * - 只接受 `status` = 1（正常/解冻）或 3（冻结），别的值 40001；
 * - **被驳回（2）的服务者不能从这里恢复为正常**：那条路必须重新提交申请并审核通过；
 * - 本接口只覆盖「禁止新上架 + 禁止新接单」这一半：已上架的服务项不自动下架，
 *   已预约订单转取消归订单模块（#77）——页面把这个缺口写清楚，别让人以为点一下全干净了。
 */
import { ref } from "vue";
import { formatDateTime, toApiFailure } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { adminApp, type ProviderProfileView, type ProviderRow } from "../api/adminApi";
import { useAdminSession } from "../session";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { businessHoursText, providerStatusLabel, providerStatusTone, providerTypeLabel } from "@pet-health/shared";

/** 清退（冻结）入口只对超级管理员可见——当前角色未落地，这一位恒为 false，见文件头 */
const { isSuperAdmin } = useAdminSession();

const statusFilter = ref("");
const typeFilter = ref("");
const keyword = ref("");

const providers = usePagedList<ProviderRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listProviders(
      {
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        type: typeFilter.value === "" ? undefined : Number(typeFilter.value),
        keyword: keyword.value.trim() || undefined,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "服务者列表加载失败，请稍后重试" },
);

const detail = ref<ProviderProfileView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 正在处置/刚读失败的那一行：重试要重读**这一行**，而不是列表最新的那条 */
const detailTarget = ref<ProviderRow | null>(null);

const submit = useSubmitAction("状态处置失败，请稍后重试");
const reason = ref("");
/** 清退前的二次确认：展开一个说明区，让操作者读到代价后再点 */
const confirmingRetire = ref(false);

function reload(): void {
  void providers.reload();
}

async function openDetail(row: ProviderRow): Promise<void> {
  detailTarget.value = row;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  confirmingRetire.value = false;
  reason.value = "";
  submit.clear();
  try {
    detail.value = await adminApp.getProvider(row.id);
  } catch (error) {
    const failure = toApiFailure(error, "服务者详情加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    detailLoading.value = false;
  }
}

async function changeStatus(providerId: number, next: 1 | 3, text: string): Promise<void> {
  const outcome = await submit.run(
    () => adminApp.updateProviderStatus(providerId, { status: next, reason: reason.value.trim() || null }),
    text,
  );
  if (!outcome.ok) return;
  detail.value = outcome.value;
  confirmingRetire.value = false;
  reason.value = "";
  reload();
}

function canFreeze(status?: number): boolean {
  return status === 1;
}

function canUnfreeze(status?: number): boolean {
  return status === 3;
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-prov__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="0">待审核</option>
          <option value="1">正常</option>
          <option value="2">已驳回</option>
          <option value="3">已冻结</option>
        </select>
      </label>
      <label class="ph-field ph-prov__filter">
        <span class="ph-field__label">类型</span>
        <select v-model="typeFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">医院</option>
          <option value="2">洗护</option>
          <option value="3">训犬</option>
          <option value="4">寄养上门</option>
          <option value="5">食品用品</option>
          <option value="6">间接服务</option>
        </select>
      </label>
      <input v-model="keyword" class="ph-input ph-prov__search" maxlength="64" placeholder="按门店名称搜索" @keyup.enter="reload" />
      <button type="button" class="ph-button ph-button--secondary" @click="reload">搜索</button>
    </div>
    <p class="ph-field__hint">关键字只搜名称：联系电话是密文（ADR-0013），字段级加密下做不了模糊搜索。</p>

    <ConsoleListState
      :loading="providers.loading.value"
      :forbidden="providers.forbidden.value"
      :error-message="providers.errorMessage.value"
      :request-id="providers.requestId.value"
      :is-empty="providers.isEmpty.value"
      loading-title="正在加载服务者列表"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取服务者列表。"
      empty-title="没有匹配的服务者"
      empty-description="换个筛选条件看看。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>类型</th>
              <th>地址</th>
              <th>状态</th>
              <th>评分 / 考核分</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in providers.items.value" :key="row.id">
              <td>{{ row.name }}</td>
              <td>{{ providerTypeLabel(row.type) }}</td>
              <td>{{ row.address }}</td>
              <td><span class="ph-tag" :class="providerStatusTone(row.status)">{{ providerStatusLabel(row.status) }}</span></td>
              <td class="ph-table__num">{{ row.rating ?? "—" }} / {{ row.monthly_score ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openDetail(row)">详情</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ providers.total.value }} 家</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="providers.page.value <= 1 || providers.loading.value"
          @click="providers.prevPage"
        >
          上一页
        </button>
        <span>第 {{ providers.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!providers.hasMore.value || providers.loading.value"
          @click="providers.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <div v-if="detailLoading" class="ph-card ph-prov__detail"><p class="ph-text-sub">正在加载服务者详情…</p></div>
    <div v-else-if="detailError" class="ph-card ph-prov__detail">
      <p class="ph-alert ph-alert--error">
        {{ detailError }}<span v-if="detailRequestId" class="ph-text-weak">（请求 ID：{{ detailRequestId }}）</span>
        <button
          type="button"
          class="ph-table__action ph-prov__retry"
          @click="detailTarget && openDetail(detailTarget)"
        >
          重新加载
        </button>
      </p>
    </div>
    <div v-else-if="detail" class="ph-card ph-prov__detail">
      <div class="ph-toolbar">
        <h4 class="ph-card__title">{{ detail.name }}</h4>
        <span class="ph-tag" :class="providerStatusTone(detail.status)">{{ providerStatusLabel(detail.status) }}</span>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" @click="detail = null">收起</button>
      </div>

      <dl class="ph-kv">
        <dt>类型</dt>
        <dd>{{ providerTypeLabel(detail.type) }}</dd>
        <dt>地址</dt>
        <dd>{{ detail.address }}</dd>
        <dt>联系电话</dt>
        <dd>{{ detail.phone }}（脱敏）</dd>
        <dt>营业时间</dt>
        <dd>{{ businessHoursText(detail.business_hours) }}</dd>
        <dt>简介</dt>
        <dd>{{ detail.intro ?? "—" }}</dd>
        <dt>通过时间</dt>
        <dd>{{ detail.approved_at ? formatDateTime(detail.approved_at) : "—" }}</dd>
      </dl>

      <label class="ph-field ph-prov__remark">
        <span class="ph-field__label">处置原因（可选，会进审核流水）</span>
        <input v-model="reason" class="ph-input" maxlength="255" placeholder="如：多次未按标准履约" />
      </label>

      <div class="ph-prov__actions">
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!canUnfreeze(detail.status) || submit.submitting.value"
          @click="detail.id && changeStatus(detail.id, 1, '已解冻，服务者恢复接单与上架')"
        >
          解冻
        </button>
        <!-- 清退入口按 ADR-0037 只对超级管理员可见：角色未落地时**不渲染**，并写明缺口 -->
        <button
          v-if="isSuperAdmin"
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!canFreeze(detail.status) || submit.submitting.value"
          @click="confirmingRetire = !confirmingRetire"
        >
          清退（冻结）
        </button>
      </div>

      <p v-if="!isSuperAdmin" class="ph-alert ph-alert--info">
        <strong>清退入口不在这里显示</strong>：ADR-0037 把「清退服务者」划给超级管理员（不可逆），
        而令牌目前只带登录域、不带角色（ADR-0035「需要协调」第 3 条），前端分不出运营与超管，
        所以这个动作不渲染——而不是渲染出来靠二次确认提醒「请自行确认有权」。
        角色声明进令牌后，接上 `session.isSuperAdmin` 即可。
      </p>

      <!-- 清退的二次确认：把代价讲清楚再让他点（ADR-0037：清退不可逆） -->
      <div v-if="isSuperAdmin && confirmingRetire" class="ph-alert ph-alert--warn">
        <p>
          <strong>清退 = 冻结</strong>（ADR-0037）：禁止新接单与新上架，**保留**历史订单与宠物档案。
          这个动作不可逆，按权限矩阵只归<strong>超级管理员</strong>。
        </p>
        <p class="ph-field__hint">
          本接口只覆盖「禁止新上架」这一半：已上架的服务项不会自动下架，已预约订单转取消归订单模块（#77），
          两件事都不在这里发生。
        </p>
        <button
          type="button"
          class="ph-button ph-button--primary"
          :disabled="submit.submitting.value"
          @click="detail.id && changeStatus(detail.id, 3, '已冻结（清退）：禁止新接单与新上架，历史订单与档案保留')"
        >
          {{ submit.submitting.value ? "提交中…" : "确认清退" }}
        </button>
      </div>

      <p v-if="detail.status === 2" class="ph-alert ph-alert--info">
        被驳回的服务者不能从这里恢复为正常：必须重新提交入驻申请并审核通过（否则一次驳回等于白拒）。
      </p>

      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info">{{ submit.doneMessage.value }}</p>
    </div>
  </div>
</template>

<style scoped>
.ph-prov__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-prov__filter .ph-select {
  width: auto;
  min-width: 110px;
}

.ph-prov__search {
  width: 200px;
}

.ph-prov__detail {
  margin-top: var(--ph-space-5);
}

.ph-prov__retry {
  margin-left: var(--ph-space-3);
}

.ph-prov__remark {
  max-width: 640px;
  margin: var(--ph-space-4) 0 var(--ph-space-3);
}

.ph-prov__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-bottom: var(--ph-space-4);
}
</style>
