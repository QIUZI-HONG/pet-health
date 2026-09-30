<script setup lang="ts">
/**
 * 目录外服务提案审批：通过（平台上新项目）/ 驳回。
 *
 * 契约给出的核心口径（contract/admin.yaml 的 `/catalog/item-requests/{id}/approve`）：
 * **最终区间必填，且不默认采纳服务者的建议值**——区间是平台的规则（F010），让申请方定义规则
 * 等于让区间校验自己批自己。所以通过表单里区间是空的，旁边给一个「沿用建议值」按钮：
 * 运营可以把建议值抄过来，但那是他的显式动作。
 *
 * `code` 可以不传（按分类前缀排下一个序号，如 HE-014）；传了就必须与分类前缀一致且未被占用
 * ——前端据此先判一次格式与前缀，省掉一次注定 40001 的往返。
 *
 * 驳回原因必填且会原样展示给服务者（同入驻审核）。
 */
import { ref } from "vue";
import { formatDateTime, toApiFailure } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import {
  adminApp,
  type CatalogItemProposalApproveRequest,
  type CategoryRow,
  type CatalogItemProposalView,
  type ProposalRow,
} from "../api/adminApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { centsToAmount, formatAmountRange, isRangeBoundValid, parseAmountToCents, reviewStatusLabel, reviewStatusTone } from "@pet-health/shared";
import { reviewActionLabel } from "../utils/labels";

const props = defineProps<{ categories: CategoryRow[] }>();
const emit = defineEmits<{ changed: [] }>();

const statusFilter = ref("0");

const proposals = usePagedList<ProposalRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listProposals(
      { status: statusFilter.value === "" ? undefined : Number(statusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "提案队列加载失败，请稍后重试" },
);

const detail = ref<CatalogItemProposalView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 正在审/刚读失败的那一行：重试要重读**这一行**，而不是列表最新的那条 */
const detailTarget = ref<ProposalRow | null>(null);

const approveSubmit = useSubmitAction("通过失败，请稍后重试");
const rejectSubmit = useSubmitAction("驳回失败，请稍后重试");

/** 通过表单：区间为空（不默认采纳建议值），编码与名称留空表示按平台规则生成 */
const approveForm = ref({ priceMin: "", priceMax: "", priceUnit: "", name: "", code: "", remark: "" });
const rejectReason = ref("");
const localError = ref("");

/** 列表重载：状态筛选、刷新按钮、列表错误态的重试都走它（loader 每次现读 statusFilter）。 */
function reload(): void {
  void proposals.reload();
}

/**
 * 打开详情：清掉上一次的两个提交态、本地错误与驳回原因；通过表单只预填建议单位，
 * 区间与编码故意留空（不默认采纳建议值，见文件头）。
 */
async function openDetail(row: ProposalRow): Promise<void> {
  detailTarget.value = row;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  approveSubmit.clear();
  rejectSubmit.clear();
  localError.value = "";
  rejectReason.value = "";
  try {
    const view = await adminApp.getProposal(row.id);
    detail.value = view;
    approveForm.value = {
      priceMin: "",
      priceMax: "",
      priceUnit: view.suggested_price_unit ?? "次",
      name: "",
      code: "",
      remark: "",
    };
  } catch (error) {
    const failure = toApiFailure(error, "提案详情加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    detailLoading.value = false;
  }
}

/** 「沿用建议值」：把提案里的建议抄进最终区间——这是运营的显式动作，不是默认值 */
function useSuggestion(): void {
  const current = detail.value;
  if (!current) return;
  approveForm.value.priceMin = current.suggested_price_min ?? "";
  approveForm.value.priceMax = current.suggested_price_max ?? "";
}

/** 通过前先本地判一次（区间格式 + 编码前缀），省掉注定 40001 的往返；边界允许 0，与后端同口径。 */
function validateApprove(): boolean {
  const current = detail.value;
  if (!current) return false;
  // 最终区间是**区间**：边界允许 0（与后端 Price.parse 同口径），只要求非负、两位小数、下限 ≤ 上限
  if (!isRangeBoundValid(approveForm.value.priceMin) || !isRangeBoundValid(approveForm.value.priceMax)) {
    localError.value = "最终区间必填：两个值都要是 0 或正数、最多两位小数";
    return false;
  }
  const min = parseAmountToCents(approveForm.value.priceMin);
  const max = parseAmountToCents(approveForm.value.priceMax);
  if (min !== null && max !== null && min > max) {
    localError.value = "区间下限不能高于上限";
    return false;
  }
  const code = approveForm.value.code.trim();
  if (code !== "") {
    if (!/^[A-Z]{2}-\d{3}$/.test(code)) {
      localError.value = "项目编码要形如 HE-001（两位大写字母 + 三位数字）";
      return false;
    }
    const prefix = props.categories.find((category) => category.code === current.category_code)?.item_code_prefix;
    if (prefix && !code.startsWith(`${prefix}-`)) {
      localError.value = `编码前缀要与分类一致：本分类的编码应以 ${prefix}- 开头（留空则自动排号）`;
      return false;
    }
  }
  localError.value = "";
  return true;
}

/** 通过＝平台上新项目：成功后用返回的详情替换本地（状态一变结论区就收起），并通知外面刷目录。 */
async function approve(): Promise<void> {
  const current = detail.value;
  if (!current || !validateApprove()) return;
  const body: CatalogItemProposalApproveRequest = {
    price_min: centsToAmount(parseAmountToCents(approveForm.value.priceMin) ?? 0),
    price_max: centsToAmount(parseAmountToCents(approveForm.value.priceMax) ?? 0),
    price_unit: approveForm.value.priceUnit.trim() || undefined,
    name: approveForm.value.name.trim() || undefined,
    code: approveForm.value.code.trim() || undefined,
    remark: approveForm.value.remark.trim() || null,
  };
  const outcome = await approveSubmit.run(
    () => adminApp.approveProposal(current.id!, body),
    "已通过：平台上多了这个正式目录项，服务者可以去勾选定价了",
  );
  if (!outcome.ok) return;
  detail.value = outcome.value;
  reload();
  emit("changed");
}

/** 驳回不动目录，所以只重载列表、不 emit changed——通过才需要通知外面。 */
async function reject(): Promise<void> {
  const current = detail.value;
  if (!current) return;
  if (rejectReason.value.trim() === "") {
    rejectSubmit.errorMessage.value = "驳回原因必填：它会原样展示给服务者";
    return;
  }
  const outcome = await rejectSubmit.run(() => adminApp.rejectProposal(current.id!, { reason: rejectReason.value.trim() }), "已驳回");
  if (!outcome.ok) return;
  detail.value = outcome.value;
  reload();
}
// 首屏加载一次：这两个面板是「打开就该有内容」的列表，等用户改筛选才发请求等于空页
void proposals.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-prop__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="0">待审核</option>
          <option value="1">已通过</option>
          <option value="2">已驳回</option>
          <option value="">全部</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="proposals.loading.value" @click="reload">
        刷新
      </button>
      <span class="ph-text-weak">先到先审</span>
    </div>

    <ConsoleListState
      :loading="proposals.loading.value"
      :forbidden="proposals.forbidden.value"
      :error-message="proposals.errorMessage.value"
      :request-id="proposals.requestId.value"
      :is-empty="proposals.isEmpty.value"
      loading-title="正在加载提案队列"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取提案队列。"
      empty-title="没有待处理的提案"
      empty-description="换个状态筛选看看。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>项目</th>
              <th>服务者</th>
              <th>分类</th>
              <th>建议区间</th>
              <th>状态</th>
              <th>正式编码</th>
              <th>提交时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in proposals.items.value" :key="row.id">
              <td>{{ row.name }}</td>
              <td>{{ row.provider_name }}</td>
              <td>{{ row.category_name ?? row.category_code }}</td>
              <td class="ph-table__num">{{ formatAmountRange(row.suggested_price_min, row.suggested_price_max) }}</td>
              <td><span class="ph-tag" :class="reviewStatusTone(row.status)">{{ reviewStatusLabel(row.status) }}</span></td>
              <td class="ph-table__num">{{ row.item_code ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.submitted_at) }}</td>
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
        <span>共 {{ proposals.total.value }} 条</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="proposals.page.value <= 1 || proposals.loading.value"
          @click="proposals.prevPage"
        >
          上一页
        </button>
        <span>第 {{ proposals.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!proposals.hasMore.value || proposals.loading.value"
          @click="proposals.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <div v-if="detailLoading" class="ph-card ph-prop__detail"><p class="ph-text-sub">正在加载提案详情…</p></div>
    <div v-else-if="detailError" class="ph-card ph-prop__detail">
      <p class="ph-alert ph-alert--error">
        {{ detailError }}<span v-if="detailRequestId" class="ph-text-weak">（请求 ID：{{ detailRequestId }}）</span>
        <button
          type="button"
          class="ph-table__action ph-prop__retry"
          @click="detailTarget && openDetail(detailTarget)"
        >
          重新加载
        </button>
      </p>
    </div>
    <div v-else-if="detail" class="ph-card ph-prop__detail">
      <div class="ph-toolbar">
        <h4 class="ph-card__title">提案 #{{ detail.id }}：{{ detail.name }}</h4>
        <span class="ph-tag" :class="reviewStatusTone(detail.status)">{{ reviewStatusLabel(detail.status) }}</span>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" @click="detail = null">收起</button>
      </div>

      <dl class="ph-kv">
        <dt>服务者</dt>
        <dd>{{ detail.provider_name }}（#{{ detail.provider_id }}）</dd>
        <dt>分类</dt>
        <dd>{{ detail.category_name ?? detail.category_code }}</dd>
        <dt>建议区间</dt>
        <dd class="ph-table__num">{{ formatAmountRange(detail.suggested_price_min, detail.suggested_price_max) }} / {{ detail.suggested_price_unit }}</dd>
        <dt>说明</dt>
        <dd>{{ detail.description ?? "—" }}</dd>
        <dt>提交时间</dt>
        <dd>{{ formatDateTime(detail.submitted_at) }}</dd>
        <dt v-if="detail.item_code">正式编码</dt>
        <dd v-if="detail.item_code" class="ph-table__num">{{ detail.item_code }}</dd>
      </dl>

      <h4 class="ph-card__title ph-prop__sub">审核流水</h4>
      <ul class="ph-prop__logs">
        <li v-for="log in detail.review_logs ?? []" :key="log.id ?? log.created_at">
          <span class="ph-text-weak">{{ formatDateTime(log.created_at) }}</span>
          <span class="ph-prop__log-action">{{ reviewActionLabel(log.action) }}</span>
          <span>{{ log.remark ?? "" }}</span>
        </li>
        <li v-if="(detail.review_logs ?? []).length === 0" class="ph-text-weak">暂无流水</li>
      </ul>

      <template v-if="detail.status === 0">
        <h4 class="ph-card__title ph-prop__sub">通过（平台上新项目）</h4>
        <p class="ph-field__hint">
          最终区间必填，且<strong>不默认采纳建议值</strong>——区间是平台的规则。要沿用建议值请点右边那个按钮，
          那是你的显式动作。
        </p>
        <div class="ph-toolbar">
          <button type="button" class="ph-button ph-button--secondary" @click="useSuggestion">沿用建议值</button>
          <span class="ph-text-weak">建议：{{ formatAmountRange(detail.suggested_price_min, detail.suggested_price_max) }}</span>
        </div>
        <div class="ph-form-grid">
          <label class="ph-field">
            <span class="ph-field__label">最终区间下限（元）</span>
            <input v-model="approveForm.priceMin" class="ph-input" inputmode="decimal" placeholder="必填" />
          </label>
          <label class="ph-field">
            <span class="ph-field__label">最终区间上限（元）</span>
            <input v-model="approveForm.priceMax" class="ph-input" inputmode="decimal" placeholder="必填" />
          </label>
          <label class="ph-field">
            <span class="ph-field__label">计价单位</span>
            <input v-model="approveForm.priceUnit" class="ph-input" maxlength="16" placeholder="不填沿用建议单位" />
          </label>
          <label class="ph-field">
            <span class="ph-field__label">项目名称（可选）</span>
            <input v-model="approveForm.name" class="ph-input" maxlength="128" placeholder="不填沿用提案名称" />
          </label>
          <label class="ph-field">
            <span class="ph-field__label">项目编码（可选）</span>
            <input v-model="approveForm.code" class="ph-input" maxlength="16" placeholder="留空自动排号，如 HE-014" />
          </label>
          <label class="ph-field">
            <span class="ph-field__label">审核备注（可选）</span>
            <input v-model="approveForm.remark" class="ph-input" maxlength="255" />
          </label>
        </div>

        <p v-if="localError" class="ph-alert ph-alert--error ph-prop__alert">{{ localError }}</p>
        <p v-if="approveSubmit.errorMessage.value" class="ph-alert ph-alert--error ph-prop__alert">
          {{ approveSubmit.errorMessage.value }}
          <span v-if="approveSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ approveSubmit.requestId.value }}）</span>
        </p>
        <div class="ph-prop__actions">
          <button type="button" class="ph-button ph-button--primary" :disabled="approveSubmit.submitting.value" @click="approve">
            {{ approveSubmit.submitting.value ? "提交中…" : "通过并建项" }}
          </button>
        </div>

        <h4 class="ph-card__title ph-prop__sub">驳回</h4>
        <label class="ph-field ph-prop__reason">
          <span class="ph-field__label">驳回原因（必填，会原样展示给服务者）</span>
          <textarea v-model="rejectReason" class="ph-textarea" maxlength="255" placeholder="如：与目录里已有的「犬只基础体检」重复，建议直接选品" />
        </label>
        <p v-if="rejectSubmit.errorMessage.value" class="ph-alert ph-alert--error ph-prop__alert">
          {{ rejectSubmit.errorMessage.value }}
          <span v-if="rejectSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ rejectSubmit.requestId.value }}）</span>
        </p>
        <div class="ph-prop__actions">
          <button type="button" class="ph-button ph-button--secondary" :disabled="rejectSubmit.submitting.value" @click="reject">
            {{ rejectSubmit.submitting.value ? "提交中…" : "驳回" }}
          </button>
        </div>
      </template>
      <p v-else class="ph-alert ph-alert--info">这条提案已经处理过（状态：{{ reviewStatusLabel(detail.status) }}），不能再改结论。</p>

      <p v-if="approveSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ approveSubmit.doneMessage.value }}</p>
      <p v-if="rejectSubmit.doneMessage.value" class="ph-alert ph-alert--info">{{ rejectSubmit.doneMessage.value }}</p>
    </div>
  </div>
</template>

<style scoped>
.ph-prop__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-prop__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-prop__detail {
  margin-top: var(--ph-space-5);
}

.ph-prop__retry {
  margin-left: var(--ph-space-3);
}

.ph-prop__sub {
  margin: var(--ph-space-5) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-prop__logs {
  margin: 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 2;
}

.ph-prop__log-action {
  margin: 0 var(--ph-space-2);
  font-weight: 600;
}

.ph-prop__reason {
  max-width: 640px;
  margin-bottom: var(--ph-space-3);
}

.ph-prop__alert {
  margin-top: var(--ph-space-3);
}

.ph-prop__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-3);
}
</style>
