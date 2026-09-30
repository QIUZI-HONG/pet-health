<script setup lang="ts">
/**
 * 服务者入驻审核：队列（先到先审）、申请详情、通过 / 驳回。
 *
 * 三条契约口径决定这一页的形态（contract/admin.yaml 的 `/provider-applications`）：
 * - **单人审核**：本切片不做初审 + 复审双人流程，所以通过按钮只有一个；
 * - **驳回原因必填**：会原样展示给服务者（用户故事 62：他要能「知道卡在哪」）；
 * - **通过时一并做三件事**：服务者转正常、材料转通过、申请人绑定为该服务者的管理员——
 *   审核员点一下就等于开了店，所以按钮旁写清代价，不做无提示的一键通过。
 *
 * 审核时要能看见材料：`file_url` 是材料图片（可能为空，材料上传还没接到服务者侧），
 * 有就给出可点开的链接——让审核员打开原图看一眼是这个页面的本职工作。
 */
import { computed, ref } from "vue";
import { formatDate, formatDateTime, todayIso, toApiFailure } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import {
  adminApp,
  type ApplicationRow,
  type OnboardingApplicationView,
} from "../api/adminApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { providerTypeLabel, qualificationStatusLabel, qualificationTypeLabel, reviewStatusLabel, reviewStatusTone } from "@pet-health/shared";
import { qualificationExpiryText, reviewActionLabel } from "../utils/labels";

const statusFilter = ref("0");

const applications = usePagedList<ApplicationRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listApplications(
      { status: statusFilter.value === "" ? undefined : Number(statusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "入驻审核队列加载失败，请稍后重试" },
);

const detail = ref<OnboardingApplicationView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 正在审/刚读失败的那一行：重试要重读**这一行**，而不是列表最新的那条 */
const detailTarget = ref<ApplicationRow | null>(null);

const approveSubmit = useSubmitAction("审核通过失败，请稍后重试");
const rejectSubmit = useSubmitAction("驳回失败，请稍后重试");
/** 通过时可以留一句审核备注（可空）；驳回时是必填的原因 */
const remark = ref("");
const rejectReason = ref("");

const today = todayIso();

/** 列表重载：状态筛选与刷新按钮共用（loader 每次现读 statusFilter，所以切筛选只需调它）。 */
function reload(): void {
  void applications.reload();
}

/** 打开详情：先记下审的是哪一行（错误态要重读它），并清掉上一次的备注、驳回原因与两个提交态。 */
async function openDetail(row: ApplicationRow): Promise<void> {
  detailTarget.value = row;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  remark.value = "";
  rejectReason.value = "";
  approveSubmit.clear();
  rejectSubmit.clear();
  try {
    detail.value = await adminApp.getApplication(row.id);
  } catch (error) {
    const failure = toApiFailure(error, "申请详情加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    detailLoading.value = false;
  }
}

/** 只有待审核的申请能审（其余状态后端回 40900） */
const reviewable = computed(() => detail.value?.status === 0);

/** 通过：一步做三件事（服务者转正常、材料转通过、申请人成为管理员），成功后用返回详情替换本地。 */
async function approve(): Promise<void> {
  const current = detail.value;
  if (!current?.id) return;
  const outcome = await approveSubmit.run(
    () => adminApp.approveApplication(current.id!, remark.value),
    "已通过：服务者转为正常、材料转为通过、申请人成为该服务者的管理员",
  );
  if (!outcome.ok) return;
  detail.value = outcome.value;
  reload();
}

/** 驳回：原因必填且会原样展示给服务者；成功后用返回的详情替换本地（状态一变结论区就收起）。 */
async function reject(): Promise<void> {
  const current = detail.value;
  if (!current?.id) return;
  if (rejectReason.value.trim() === "") {
    rejectSubmit.errorMessage.value = "驳回原因必填：它会原样展示给服务者";
    return;
  }
  const outcome = await rejectSubmit.run(
    () => adminApp.rejectApplication(current.id!, rejectReason.value.trim()),
    "已驳回：服务者可在同一份申请单上改完重提",
  );
  if (!outcome.ok) return;
  detail.value = outcome.value;
  reload();
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-review__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="0">待审核</option>
          <option value="1">已通过</option>
          <option value="2">已驳回</option>
          <option value="">全部</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="applications.loading.value" @click="reload">
        刷新
      </button>
      <span class="ph-text-weak">先到先审（按提交时间升序）</span>
    </div>

    <ConsoleListState
      :loading="applications.loading.value"
      :forbidden="applications.forbidden.value"
      :error-message="applications.errorMessage.value"
      :request-id="applications.requestId.value"
      :is-empty="applications.isEmpty.value"
      loading-title="正在加载入驻审核队列"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取入驻审核队列。"
      empty-title="没有待处理的申请"
      empty-description="换个状态筛选看看，或者等新的入驻申请进来。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>类型</th>
              <th>申请人</th>
              <th>联系电话</th>
              <th>提交时间</th>
              <th>提交次数</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in applications.items.value" :key="row.id">
              <td>{{ row.provider_name ?? "—" }}</td>
              <td>{{ providerTypeLabel(row.provider_type) }}</td>
              <td>{{ row.applicant_name ?? "—" }}</td>
              <td class="ph-table__num">{{ row.contact_phone ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.submitted_at) }}</td>
              <td class="ph-table__num">第 {{ row.submit_count ?? 1 }} 次</td>
              <td>
                <span class="ph-tag" :class="reviewStatusTone(row.status)">{{ reviewStatusLabel(row.status) }}</span>
              </td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openDetail(row)">
                    {{ row.status === 0 ? "审核" : "查看" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ applications.total.value }} 份</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="applications.page.value <= 1 || applications.loading.value"
          @click="applications.prevPage"
        >
          上一页
        </button>
        <span>第 {{ applications.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!applications.hasMore.value || applications.loading.value"
          @click="applications.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <div v-if="detailLoading" class="ph-card ph-review__detail"><p class="ph-text-sub">正在加载申请详情…</p></div>
    <div v-else-if="detailError" class="ph-card ph-review__detail">
      <p class="ph-alert ph-alert--error">
        {{ detailError }}<span v-if="detailRequestId" class="ph-text-weak">（请求 ID：{{ detailRequestId }}）</span>
        <button
          type="button"
          class="ph-table__action ph-review__retry"
          @click="detailTarget && openDetail(detailTarget)"
        >
          重新加载
        </button>
      </p>
    </div>
    <div v-else-if="detail" class="ph-card ph-review__detail">
      <div class="ph-toolbar">
        <h4 class="ph-card__title">申请详情 #{{ detail.id }}</h4>
        <span class="ph-tag" :class="reviewStatusTone(detail.status)">{{ reviewStatusLabel(detail.status) }}</span>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" @click="detail = null">收起</button>
      </div>

      <p v-if="detail.reject_reason" class="ph-alert ph-alert--error">上次驳回原因：{{ detail.reject_reason }}</p>

      <dl class="ph-kv">
        <dt>门店名称</dt>
        <dd>{{ detail.provider?.name ?? "—" }}</dd>
        <dt>门店类型</dt>
        <dd>{{ providerTypeLabel(detail.provider?.type) }}</dd>
        <dt>门店地址</dt>
        <dd>{{ detail.provider?.address ?? "—" }}</dd>
        <dt>门店简介</dt>
        <dd>{{ detail.provider?.intro ?? "—" }}</dd>
        <dt>联系电话</dt>
        <dd>{{ detail.contact_phone ?? "—" }}（脱敏）</dd>
        <dt>申请人</dt>
        <dd>{{ detail.applicant_name ?? "—" }}</dd>
        <dt>提交 / 审核时间</dt>
        <dd>
          {{ formatDateTime(detail.submitted_at) }}（第 {{ detail.submit_count ?? 1 }} 次提交）
          <template v-if="detail.reviewed_at"> · 审核于 {{ formatDateTime(detail.reviewed_at) }}</template>
        </dd>
      </dl>

      <h4 class="ph-card__title ph-review__sub">资质材料</h4>
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>名称</th>
              <th>证件号</th>
              <th>有效期</th>
              <th>有效期状态</th>
              <th>材料</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.qualifications ?? []" :key="item.id ?? item.name">
              <td>{{ qualificationTypeLabel(item.type) }}</td>
              <td>{{ item.name ?? "—" }}</td>
              <td>{{ item.cert_no ?? "—" }}</td>
              <td class="ph-table__num">
                {{ item.valid_from ? formatDate(item.valid_from) : "—" }} ~
                {{ item.valid_until ? formatDate(item.valid_until) : "长期有效" }}
              </td>
              <td>
                <span class="ph-tag" :class="item.valid_until && item.valid_until < today ? 'ph-tag--danger' : ''">
                  {{ qualificationExpiryText(item, today) }}
                </span>
                <span class="ph-text-weak">（{{ qualificationStatusLabel(item.status) }}）</span>
              </td>
              <td>
                <!-- 审核时直接看得到图，不必逐个点开；点图仍然可以看原尺寸（ADR-0053） -->
                <a v-if="item.file_url" :href="item.file_url" target="_blank" rel="noreferrer">
                  <img :src="item.file_url" class="ph-review__thumb" alt="材料图片" />
                </a>
                <span v-else class="ph-text-weak">未上传</span>
              </td>
            </tr>
            <tr v-if="(detail.qualifications ?? []).length === 0">
              <td colspan="6">没有材料记录</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4 class="ph-card__title ph-review__sub">审核流水</h4>
      <ul class="ph-review__logs">
        <li v-for="log in detail.review_logs ?? []" :key="log.id ?? log.created_at">
          <span class="ph-text-weak">{{ formatDateTime(log.created_at) }}</span>
          <span class="ph-review__log-action">{{ reviewActionLabel(log.action) }}</span>
          <span>{{ log.remark ?? "" }}</span>
        </li>
      </ul>

      <template v-if="reviewable">
        <h4 class="ph-card__title ph-review__sub">审核结论</h4>
        <p class="ph-field__hint">
          通过即生效：服务者转为「正常」、材料转为「通过」、申请人成为该服务者的管理员，随后即可选品上架。
        </p>
        <label class="ph-field ph-review__remark">
          <span class="ph-field__label">审核备注（可选）</span>
          <input v-model="remark" class="ph-input" maxlength="255" placeholder="通过时通常留空" />
        </label>
        <div class="ph-review__actions">
          <button type="button" class="ph-button ph-button--primary" :disabled="approveSubmit.submitting.value" @click="approve">
            {{ approveSubmit.submitting.value ? "提交中…" : "审核通过" }}
          </button>
        </div>

        <label class="ph-field ph-review__remark">
          <span class="ph-field__label">驳回原因（必填，会原样展示给服务者）</span>
          <textarea v-model="rejectReason" class="ph-textarea" maxlength="255" placeholder="如：动物诊疗许可证已过期，请补交有效期内的证件" />
        </label>
        <div class="ph-review__actions">
          <button type="button" class="ph-button ph-button--secondary" :disabled="rejectSubmit.submitting.value" @click="reject">
            {{ rejectSubmit.submitting.value ? "提交中…" : "驳回" }}
          </button>
        </div>

        <p v-if="approveSubmit.errorMessage.value" class="ph-alert ph-alert--error">
          {{ approveSubmit.errorMessage.value }}
          <span v-if="approveSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ approveSubmit.requestId.value }}）</span>
        </p>
        <p v-if="rejectSubmit.errorMessage.value" class="ph-alert ph-alert--error">
          {{ rejectSubmit.errorMessage.value }}
          <span v-if="rejectSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ rejectSubmit.requestId.value }}）</span>
        </p>
      </template>
      <p v-else class="ph-alert ph-alert--info">这份申请已经审过（状态：{{ reviewStatusLabel(detail.status) }}），不能再改结论。</p>

      <p v-if="approveSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ approveSubmit.doneMessage.value }}</p>
      <p v-if="rejectSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ rejectSubmit.doneMessage.value }}</p>
    </div>
  </div>
</template>

<style scoped>
.ph-review__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-review__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-review__detail {
  margin-top: var(--ph-space-5);
}

/* 材料图缩略图：与照片墙、服务者侧保持同一个尺寸（96×96 裁切） */
.ph-review__thumb {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border-radius: var(--ph-radius-input);
  border: 1px solid var(--ph-color-border);
  background: var(--ph-color-surface);
}

.ph-review__retry {
  margin-left: var(--ph-space-3);
}

.ph-review__sub {
  margin: var(--ph-space-5) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-review__logs {
  margin: 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 2;
}

.ph-review__log-action {
  margin: 0 var(--ph-space-2);
  font-weight: 600;
}

.ph-review__remark {
  max-width: 640px;
  margin-bottom: var(--ph-space-3);
}

.ph-review__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-bottom: var(--ph-space-4);
}
</style>
