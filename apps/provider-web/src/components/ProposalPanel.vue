<script setup lang="ts">
/**
 * 目录外服务提案：提交新项目申请、看审核结果与审核流水。
 *
 * 为什么只能提案：目录是平台的资产（交付文档 F010），**不放开自由建项**——所以这里没有
 * 「定价」也没有「上架」，只有「申请」；运营审核通过后由平台建正式目录项并给出**最终区间**，
 * 服务者随后去「标准目录」里勾选、定价。
 *
 * 表单里的区间是**建议值**（契约 `suggested_price_min/max`）：审核通过时运营可以改，
 * 所以页面上要写清「以平台给出的最终区间为准」，免得服务者以为填多少就是多少。
 */
import { computed, ref } from "vue";
import { formatDateTime, toApiFailure } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import {
  providerApp,
  type CatalogItemProposalRequest,
  type CatalogItemProposalView,
  type ProposalRow,
  type ServiceCategoryView,
} from "../api/providerApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { centsToAmount, formatAmountRange, isRangeBoundValid, parseAmountToCents, reviewStatusLabel, reviewStatusTone } from "@pet-health/shared";

const props = defineProps<{
  categories: ServiceCategoryView[];
  /** 能不能提交提案：入驻审核通过且未冻结（契约里写明 40300） */
  writable: boolean;
  blockReason: string;
}>();

const statusFilter = ref("");

const proposals = usePagedList<ProposalRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listProposals(
      { status: statusFilter.value === "" ? undefined : Number(statusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "提案列表加载失败，请稍后重试" },
);

const detail = ref<CatalogItemProposalView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 正在看/刚读失败的那一行：重试要重读**这一行**，而不是列表最新的那条 */
const detailTarget = ref<ProposalRow | null>(null);

const formVisible = ref(false);
const submit = useSubmitAction("提交失败，请稍后重试");
const form = ref({ categoryCode: "", name: "", priceMin: "", priceMax: "", priceUnit: "次", description: "" });

/** 列表重载：状态筛选与刷新共用（loader 每次现读 statusFilter，所以切筛选只需调它）。 */
function reload(): void {
  void proposals.reload();
}

/** 打开详情：先记下看的是哪一行——错误态的「重新加载」要重读它，而不是列表最新的一条。 */
async function openDetail(row: ProposalRow): Promise<void> {
  detailTarget.value = row;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  try {
    detail.value = await providerApp.getProposal(row.id);
  } catch (error) {
    const failure = toApiFailure(error, "提案详情加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    detailLoading.value = false;
  }
}

/** 打开提案表单：先 clear() 上一次的提交态，分类预选第一个（目录是平台资产，这里只能提案）。 */
function openForm(): void {
  submit.clear();
  form.value = {
    categoryCode: props.categories[0]?.code ?? "",
    name: "",
    priceMin: "",
    priceMax: "",
    priceUnit: "次",
    description: "",
  };
  formVisible.value = true;
}

const localError = computed(() => {
  if (form.value.categoryCode === "") return "请选择分类";
  if (form.value.name.trim() === "") return "请填写服务项目名称";
  // 建议区间是**区间**：下限可以是 0（0.00–50.00 的试吃装合法），与服务者上架价（必须 > 0）不同口径
  if (!isRangeBoundValid(form.value.priceMin)) return "建议区间下限填 0 或正数，最多两位小数";
  if (!isRangeBoundValid(form.value.priceMax)) return "建议区间上限填 0 或正数，最多两位小数";
  const min = parseAmountToCents(form.value.priceMin);
  const max = parseAmountToCents(form.value.priceMax);
  if (min !== null && max !== null && min > max) return "建议区间下限不能高于上限";
  return "";
});

/** 提交提案：金额先规范成两位小数字符串（不经过浮点）；建议区间只是参考值，最终区间由平台给出。 */
async function confirmSubmit(): Promise<void> {
  if (localError.value !== "") {
    submit.errorMessage.value = localError.value;
    return;
  }
  const body: CatalogItemProposalRequest = {
    category_code: form.value.categoryCode,
    name: form.value.name.trim(),
    description: form.value.description.trim() === "" ? null : form.value.description.trim(),
    // 金额按字符串两位小数提交：本地先把用户输入规范成两位（不经过浮点运算）
    price_min: centsToAmount(parseAmountToCents(form.value.priceMin) ?? 0),
    price_max: centsToAmount(parseAmountToCents(form.value.priceMax) ?? 0),
    price_unit: form.value.priceUnit.trim() === "" ? "次" : form.value.priceUnit.trim(),
  };
  const outcome = await submit.run(() => providerApp.submitProposal(body), "提案已提交，等待平台审核");
  if (!outcome.ok) return;
  formVisible.value = false;
  reload();
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-prop__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="0">待审核</option>
          <option value="1">已通过</option>
          <option value="2">已驳回</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="proposals.loading.value" @click="reload">
        刷新
      </button>
      <span class="ph-toolbar__spacer" />
      <button
        type="button"
        class="ph-button ph-button--primary"
        :disabled="!props.writable"
        :title="props.writable ? '' : props.blockReason"
        @click="openForm"
      >
        提交新项目申请
      </button>
    </div>

    <p v-if="!props.writable && props.blockReason" class="ph-alert ph-alert--warn ph-prop__alert">
      {{ props.blockReason }}
    </p>

    <ConsoleListState
      :loading="proposals.loading.value"
      :forbidden="proposals.forbidden.value"
      :error-message="proposals.errorMessage.value"
      :request-id="proposals.requestId.value"
      :is-empty="proposals.isEmpty.value"
      loading-title="正在加载提案"
      forbidden-title="暂无权限"
      forbidden-description="这个账号的令牌不能读取目录外提案。"
      empty-title="还没有提交过提案"
      empty-description="标准目录里没有的服务，可以在这里申请新增；平台审核通过后会建正式目录项。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>项目</th>
              <th>分类</th>
              <th>建议区间</th>
              <th>单位</th>
              <th>状态</th>
              <th>驳回原因</th>
              <th>正式编码</th>
              <th>提交时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in proposals.items.value" :key="row.id">
              <td>{{ row.name }}</td>
              <td>{{ row.category_name ?? row.category_code }}</td>
              <td class="ph-table__num">{{ formatAmountRange(row.suggested_price_min, row.suggested_price_max) }}</td>
              <td>{{ row.suggested_price_unit ?? "—" }}</td>
              <td><span class="ph-tag" :class="reviewStatusTone(row.status)">{{ reviewStatusLabel(row.status) }}</span></td>
              <td>{{ row.reject_reason ?? "—" }}</td>
              <td class="ph-table__num">{{ row.item_code ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.submitted_at) }}</td>
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
        <h4 class="ph-card__title">提案详情 #{{ detail.id }}</h4>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" @click="detail = null">收起</button>
      </div>
      <p v-if="detail.reject_reason" class="ph-alert ph-alert--error">驳回原因：{{ detail.reject_reason }}</p>
      <p v-else-if="detail.status === 1" class="ph-alert ph-alert--info">
        已通过：正式目录项编码 <span class="ph-table__num">{{ detail.item_code ?? "—" }}</span>，去「标准目录」里勾选并定价。
      </p>
      <dl class="ph-kv">
        <dt>项目名称</dt>
        <dd>{{ detail.name }}</dd>
        <dt>分类</dt>
        <dd>{{ detail.category_name ?? detail.category_code }}</dd>
        <dt>建议区间</dt>
        <dd class="ph-table__num">
          {{ formatAmountRange(detail.suggested_price_min, detail.suggested_price_max) }}（仅供参考，最终区间由平台给出）
        </dd>
        <dt>说明</dt>
        <dd>{{ detail.description ?? "—" }}</dd>
        <dt>提交时间</dt>
        <dd>{{ formatDateTime(detail.submitted_at) }}</dd>
        <dt>审核时间</dt>
        <dd>{{ detail.reviewed_at ? formatDateTime(detail.reviewed_at) : "—" }}</dd>
      </dl>

      <h4 class="ph-card__title ph-prop__sub">审核流水</h4>
      <ul class="ph-prop__logs">
        <li v-for="log in detail.review_logs ?? []" :key="log.id ?? log.created_at">
          <span class="ph-text-weak">{{ formatDateTime(log.created_at) }}</span>
          <span class="ph-prop__log-action">
            {{ log.action === 3 ? "通过" : log.action === 4 ? "驳回" : log.action === 2 ? "重新提交" : "提交" }}
          </span>
          <span>{{ log.remark ?? "" }}</span>
        </li>
        <li v-if="(detail.review_logs ?? []).length === 0" class="ph-text-weak">暂无流水</li>
      </ul>
    </div>

    <form v-if="formVisible" class="ph-card ph-prop__form" @submit.prevent="confirmSubmit">
      <h4 class="ph-card__title">提交新项目申请</h4>
      <p class="ph-field__hint">
        目录是平台的资产：服务者只能提案。建议区间是<strong>参考值</strong>，审核通过时由运营给出最终区间。
      </p>

      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">分类</span>
          <select v-model="form.categoryCode" class="ph-select">
            <option v-for="category in props.categories" :key="category.code" :value="category.code">
              {{ category.name }}
            </option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">项目名称</span>
          <input v-model="form.name" class="ph-input" maxlength="128" placeholder="如：宠物中医理疗" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">建议区间下限（元）</span>
          <input v-model="form.priceMin" class="ph-input" inputmode="decimal" placeholder="如 100.00" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">建议区间上限（元）</span>
          <input v-model="form.priceMax" class="ph-input" inputmode="decimal" placeholder="如 300.00" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">计价单位</span>
          <input v-model="form.priceUnit" class="ph-input" maxlength="16" placeholder="次 / 只 / 天 / 课时 / 件" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <textarea v-model="form.description" class="ph-textarea" maxlength="512" placeholder="这个服务包含什么、和目录里已有的有什么区别" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-prop__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-prop__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-prop__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "提交申请" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-prop__alert">{{ submit.doneMessage.value }}</p>
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

.ph-prop__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-prop__detail,
.ph-prop__form {
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

.ph-prop__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
