<script setup lang="ts">
/**
 * 入驻申请面板：申请列表与进度、驳回原因、驳回后修改重提。
 *
 * 三段界面一件事：**服务者要能自己回答「卡在哪」**（交付文档用户故事 62）。
 * 所以列表里就带 `reject_reason` 与 `submit_count`，不必点进详情；驳回原因在详情里还会再出现一次，
 * 并配上审核流水（`review_logs`，append-only，含每一次重提）。
 *
 * 三条契约规则决定按钮的可用性（contract/provider.yaml 的 `/onboarding/applications`）：
 * - 已有**待审核**的申请 → 不能再提交（40900），待审核时也不许改（会绕过审核员手里的那一版材料）；
 * - 已**驳回**的申请 → 用 `PUT` 改**同一份申请单**重提，`submit_count` 加一（不新建服务者记录）；
 * - 已**通过** → 之后的维护在门店信息与资质两处，不再走这张表单。
 *
 * 材料图片（`file_url`）这一波**不收集**：预签名直传只有 C 端（`open` 域）那一套，provider 域
 * 还没有上传入口。字段照契约留着，等上传能力接到本域再补控件——先不摆一个传不上东西的上传框。
 */
import { computed, onMounted, reactive, ref } from "vue";
import { formatDate, formatDateTime, toApiFailure } from "@pet-health/shared";
import { ConsoleListState, ConsoleState } from "@pet-health/ui";
import {
  providerApp,
  type OnboardingApplicationRequest,
  type OnboardingApplicationView,
  type OnboardingRow,
} from "../api/providerApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { providerTypeLabel, qualificationTypeLabel, reviewStatusLabel, reviewStatusTone } from "@pet-health/shared";

const props = defineProps<{
  /** 门店是否已经通过审核（通过后不再允许新建申请，只能维护） */
  approved: boolean;
}>();

const emit = defineEmits<{ changed: [] }>();

/** 状态筛选：不传表示全部（契约里 status 是可选查询参数） */
const statusFilter = ref<string>("");
const rowsPerPage = 20;

const applications = usePagedList<OnboardingRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listApplications(
      { status: statusFilter.value === "" ? undefined : Number(statusFilter.value), page, pageSize },
      signal,
    ),
  { pageSize: rowsPerPage, failureText: "入驻申请加载失败，请稍后重试" },
);

const detail = ref<OnboardingApplicationView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");
/** 正在看/刚看失败的那一行：重试要重读**这一行**，而不是列表最新的一条 */
const detailTarget = ref<OnboardingRow | null>(null);

const formVisible = ref(false);
/** 正在改的是哪一份申请：null = 新提交（POST），有值 = 驳回后重提（PUT） */
const editingId = ref<number | null>(null);
const submit = useSubmitAction("提交失败，请稍后重试");

const form = reactive({
  name: "",
  type: "1",
  category: "1",
  address: "",
  contactPhone: "",
  applicantName: "",
  intro: "",
  /** 表单里的材料行；`type` 留字符串，提交时转数字（select 的值是字符串） */
  qualifications: [{ type: "1", name: "", certNo: "", validFrom: "", validUntil: "" }],
});

/** 「现在能做什么」由列表整体决定：有待审核的不能提交，有被驳回的只能改那一份。 */
const pendingExists = computed(() => applications.items.value.some((item) => item.status === 0));
const rejected = computed(() => applications.items.value.find((item) => item.status === 2) ?? null);

const formTitle = computed(() => (editingId.value === null ? "提交入驻申请" : "修改后重新提交"));
const canCreate = computed(() => !props.approved && !pendingExists.value && rejected.value === null);

function reload(): void {
  void applications.reload();
}

/** 打开详情：先记下这是哪一行（错误态的「重新加载」要重读它），并清掉上一次的错误与请求 ID。 */
async function openDetail(row: OnboardingRow): Promise<void> {
  detailTarget.value = row;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  try {
    detail.value = await providerApp.getApplication(row.id);
  } catch (error) {
    const failure = toApiFailure(error, "申请详情加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    detailLoading.value = false;
  }
}

/** 收起详情只清这两项：detailTarget 留着——它记的是「重试该重读哪一行」，与当前展示无关。 */
function closeDetail(): void {
  detail.value = null;
  detailError.value = "";
}

/** 打开表单：新申请给空表单；重提则把上一条被驳回的材料抄进表单（服务者只需改要改的那几项）。 */
function openForm(source: OnboardingRow | null): void {
  submit.clear();
  editingId.value = source ? source.id : null;
  form.name = source?.provider_name ?? "";
  form.type = String(source?.provider_type ?? 1);
  form.category = "1";
  form.address = "";
  form.contactPhone = "";
  form.applicantName = source?.applicant_name ?? "";
  form.intro = "";
  form.qualifications = [{ type: "1", name: "", certNo: "", validFrom: "", validUntil: "" }];
  formVisible.value = true;
  // 重提要带上门店全貌与材料：那两段只有详情接口给，先取回来再填
  if (source) {
    void providerApp
      .getApplication(source.id)
      .then((view) => fillFromDetail(view))
      .catch((error: unknown) => {
        const failure = toApiFailure(error, "申请详情加载失败，请稍后重试");
        submit.errorMessage.value = failure.message;
        submit.requestId.value = failure.requestId;
      });
  }
}

/** 重提时把详情抄进表单；证件号读回来的是脱敏值，故意留空让服务者重填（ADR-0013）。 */
function fillFromDetail(view: OnboardingApplicationView): void {
  const provider = view.provider;
  form.name = provider?.name ?? form.name;
  form.type = String(provider?.type ?? form.type);
  form.category = String(provider?.category ?? 1);
  form.address = provider?.address ?? "";
  form.applicantName = view.applicant_name ?? form.applicantName;
  form.intro = provider?.intro ?? "";
  form.qualifications = (view.qualifications ?? []).map((item) => ({
    type: String(item.type ?? 1),
    name: item.name ?? "",
    certNo: "",
    // 证件号读回来的是脱敏值（ADR-0013），不能当明文回填，留空让服务者重新填
    validFrom: item.valid_from ?? "",
    validUntil: item.valid_until ?? "",
  }));
  if (form.qualifications.length === 0) {
    form.qualifications = [{ type: "1", name: "", certNo: "", validFrom: "", validUntil: "" }];
  }
}

function addQualification(): void {
  form.qualifications.push({ type: "1", name: "", certNo: "", validFrom: "", validUntil: "" });
}

/** 至少留一份材料（契约要求至少一份）：按钮在那时已禁用，这里再兜一道。 */
function removeQualification(index: number): void {
  if (form.qualifications.length <= 1) return;
  form.qualifications.splice(index, 1);
}

/** 提交前先在本地说清哪里不对——后端也会拒（40001），但用户不该为了一个空字段来回一趟。 */
const localError = computed(() => {
  if (form.name.trim() === "") return "请填写门店名称";
  if (form.address.trim() === "") return "请填写门店地址";
  if (!/^(1[3-9]\d{9}|0\d{2,3}-?\d{7,8})$/.test(form.contactPhone.trim())) {
    return "联系电话填手机号或带区号的固定电话（如 0571-88886666）";
  }
  if (form.applicantName.trim() === "") return "请填写联系人姓名";
  if (form.qualifications.some((item) => item.type === "")) return "每一份材料都要选类型";
  const reversed = form.qualifications.find(
    (item) => item.validFrom !== "" && item.validUntil !== "" && item.validUntil < item.validFrom,
  );
  if (reversed) return "材料的到期日不能早于生效日";
  return "";
});

/** 本地校验先跑（省一次注定 40001 的往返）；editingId 决定走 POST 新建还是 PUT 重提原单。 */
async function confirmSubmit(): Promise<void> {
  if (localError.value !== "") {
    submit.errorMessage.value = localError.value;
    return;
  }
  const body: OnboardingApplicationRequest = {
    name: form.name.trim(),
    type: Number(form.type),
    category: Number(form.category),
    intro: form.intro.trim() === "" ? null : form.intro.trim(),
    address: form.address.trim(),
    contact_phone: form.contactPhone.trim(),
    applicant_name: form.applicantName.trim(),
    qualifications: form.qualifications.map((item) => ({
      type: Number(item.type),
      name: item.name.trim() === "" ? undefined : item.name.trim(),
      cert_no: item.certNo.trim() === "" ? null : item.certNo.trim(),
      valid_from: item.validFrom === "" ? null : item.validFrom,
      valid_until: item.validUntil === "" ? null : item.validUntil,
    })),
  };

  const editing = editingId.value;
  const outcome = await submit.run(
    () => (editing === null ? providerApp.submitApplication(body) : providerApp.resubmitApplication(editing, body)),
    editing === null ? "申请已提交，等待平台审核" : "已重新提交，等待平台审核",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  emit("changed");
  reload();
}

onMounted(reload);
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-field--inline">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="0">待审核</option>
          <option value="1">已通过</option>
          <option value="2">已驳回</option>
        </select>
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="applications.loading.value" @click="reload">
        刷新
      </button>
      <span class="ph-toolbar__spacer" />
      <button
        v-if="canCreate || rejected"
        type="button"
        class="ph-button ph-button--primary"
        @click="openForm(canCreate ? null : rejected)"
      >
        {{ canCreate ? "提交入驻申请" : "修改后重新提交" }}
      </button>
    </div>

    <p v-if="props.approved" class="ph-field__hint">
      入驻已通过：门店信息与资质在下面两段维护，这张表单不再使用。
    </p>
    <p v-else-if="pendingExists && !rejected" class="ph-field__hint">
      已有一份待审核的申请：审核期间不能修改材料（否则审核员手里的那一版会与最终入库的不一致）。
    </p>

    <ConsoleListState
      :loading="applications.loading.value"
      :forbidden="applications.forbidden.value"
      :error-message="applications.errorMessage.value"
      :request-id="applications.requestId.value"
      :is-empty="applications.isEmpty.value"
      loading-title="正在加载入驻申请"
      forbidden-title="暂无权限"
      forbidden-description="这个账号的令牌不能读取入驻申请。"
      empty-title="还没有提交过入驻申请"
      empty-description="提交后这里会显示审核进度；被驳回时原因会直接显示在列表里。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>类型</th>
              <th>提交时间</th>
              <th>提交次数</th>
              <th>状态</th>
              <th>驳回原因</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in applications.items.value" :key="row.id">
              <td>{{ row.provider_name ?? "—" }}</td>
              <td>{{ providerTypeLabel(row.provider_type) }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.submitted_at) }}</td>
              <td class="ph-table__num">第 {{ row.submit_count ?? 1 }} 次</td>
              <td>
                <span class="ph-tag" :class="reviewStatusTone(row.status)">{{ reviewStatusLabel(row.status) }}</span>
              </td>
              <td>{{ row.reject_reason ?? "—" }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openDetail(row)">查看详情</button>
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

    <!-- 申请详情：门店全貌 + 资质材料 + 审核流水 -->
    <div v-if="detailLoading" class="ph-onboarding__detail">
      <ConsoleState variant="loading" title="正在加载申请详情" />
    </div>
    <div v-else-if="detailError" class="ph-onboarding__detail">
      <ConsoleState
        variant="error"
        :title="detailError"
        :request-id="detailRequestId"
        @retry="detailTarget && openDetail(detailTarget)"
      />
    </div>
    <div v-else-if="detail" class="ph-onboarding__detail ph-card">
      <div class="ph-toolbar">
        <h4 class="ph-card__title">申请详情 #{{ detail.id }}</h4>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" @click="closeDetail">收起</button>
      </div>

      <p v-if="detail.reject_reason" class="ph-alert ph-alert--error">
        驳回原因：{{ detail.reject_reason }}
        <span v-if="detail.review_remark">（审核意见：{{ detail.review_remark }}）</span>
      </p>

      <dl class="ph-kv ph-onboarding__kv">
        <dt>状态</dt>
        <dd><span class="ph-tag" :class="reviewStatusTone(detail.status)">{{ reviewStatusLabel(detail.status) }}</span></dd>
        <dt>门店名称</dt>
        <dd>{{ detail.provider?.name ?? "—" }}</dd>
        <dt>门店类型</dt>
        <dd>{{ providerTypeLabel(detail.provider?.type) }}</dd>
        <dt>门店地址</dt>
        <dd>{{ detail.provider?.address ?? "—" }}</dd>
        <dt>联系电话</dt>
        <dd>{{ detail.contact_phone ?? "—" }}（已脱敏）</dd>
        <dt>联系人</dt>
        <dd>{{ detail.applicant_name ?? "—" }}</dd>
        <dt>提交时间</dt>
        <dd>{{ formatDateTime(detail.submitted_at) }}（第 {{ detail.submit_count ?? 1 }} 次）</dd>
        <dt>审核时间</dt>
        <dd>{{ detail.reviewed_at ? formatDateTime(detail.reviewed_at) : "—" }}</dd>
      </dl>

      <h4 class="ph-card__title ph-onboarding__sub">资质材料</h4>
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>名称</th>
              <th>证件号</th>
              <th>有效期至</th>
              <th>审核</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.qualifications ?? []" :key="item.id ?? item.name">
              <td>{{ qualificationTypeLabel(item.type) }}</td>
              <td>{{ item.name ?? "—" }}</td>
              <td>{{ item.cert_no ?? "—" }}</td>
              <td class="ph-table__num">{{ item.valid_until ? formatDate(item.valid_until) : "长期有效" }}</td>
              <td>{{ item.status === 1 ? "已通过" : item.status === 2 ? "已驳回" : "待审" }}</td>
            </tr>
            <tr v-if="(detail.qualifications ?? []).length === 0">
              <td colspan="5">没有材料记录</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4 class="ph-card__title ph-onboarding__sub">审核流水</h4>
      <ul class="ph-onboarding__logs">
        <li v-for="log in detail.review_logs ?? []" :key="log.id ?? log.created_at">
          <span class="ph-text-weak">{{ formatDateTime(log.created_at) }}</span>
          <span class="ph-onboarding__log-action">{{ log.action === 3 ? "通过" : log.action === 4 ? "驳回" : log.action === 2 ? "重新提交" : "提交" }}</span>
          <span v-if="log.remark">{{ log.remark }}</span>
        </li>
        <li v-if="(detail.review_logs ?? []).length === 0" class="ph-text-weak">暂无流水</li>
      </ul>
    </div>

    <!-- 提交 / 重提表单 -->
    <form v-if="formVisible" class="ph-card ph-onboarding__form" @submit.prevent="confirmSubmit">
      <h4 class="ph-card__title">{{ formTitle }}</h4>

      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">门店名称</span>
          <input v-model="form.name" class="ph-input" maxlength="256" placeholder="如：安心宠物医院" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">门店类型</span>
          <select v-model="form.type" class="ph-select">
            <option value="1">医院</option>
            <option value="2">洗护</option>
            <option value="3">训犬</option>
            <option value="4">寄养上门</option>
            <option value="5">食品用品</option>
            <option value="6">间接服务</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">经营类别</span>
          <select v-model="form.category" class="ph-select">
            <option value="1">直接同业</option>
            <option value="2">直接异业</option>
            <option value="3">间接异业</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">联系人</span>
          <input v-model="form.applicantName" class="ph-input" maxlength="64" placeholder="谁在对接平台审核" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">门店地址</span>
          <input v-model="form.address" class="ph-input" maxlength="512" placeholder="省市区 + 详细地址" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">联系电话</span>
          <input v-model="form.contactPhone" class="ph-input" maxlength="20" placeholder="手机号或 0571-88886666" />
          <span class="ph-field__hint">平台按脱敏值展示（138****8888），原件加密存库（ADR-0013）</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">门店简介（可选）</span>
          <input v-model="form.intro" class="ph-input" maxlength="1024" placeholder="一句话说清擅长什么" />
        </label>
      </div>

      <h5 class="ph-onboarding__sub">资质材料（至少一份）</h5>
      <p class="ph-field__hint">
        材料图片上传还没接到本域，这一波先登记材料信息与有效期；证件号会加密保存、展示时脱敏。
      </p>
      <div v-for="(item, index) in form.qualifications" :key="index" class="ph-onboarding__material">
        <select v-model="item.type" class="ph-select ph-onboarding__material-type" aria-label="材料类型">
          <option value="1">营业执照</option>
          <option value="2">执业许可证</option>
          <option value="3">法人身份证</option>
          <option value="4">训犬师认证</option>
          <option value="5">健康证</option>
          <option value="6">其他</option>
        </select>
        <input v-model="item.name" class="ph-input" maxlength="128" placeholder="材料名称（如 动物诊疗许可证）" />
        <input v-model="item.certNo" class="ph-input" maxlength="64" placeholder="证件号" />
        <input v-model="item.validFrom" class="ph-input" type="date" aria-label="生效日" />
        <input v-model="item.validUntil" class="ph-input" type="date" aria-label="到期日" />
        <button
          type="button"
          class="ph-table__action"
          :disabled="form.qualifications.length <= 1"
          @click="removeQualification(index)"
        >
          删除
        </button>
      </div>
      <button type="button" class="ph-button ph-button--secondary" @click="addQualification">再加一份材料</button>

      <p v-if="localError" class="ph-alert ph-alert--error ph-onboarding__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-onboarding__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-onboarding__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "提交审核" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info">{{ submit.doneMessage.value }}</p>
  </div>
</template>

<style scoped>
.ph-field--inline {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-field--inline .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-onboarding__detail,
.ph-onboarding__form {
  margin-top: var(--ph-space-5);
}

.ph-onboarding__kv {
  margin-bottom: var(--ph-space-5);
}

.ph-onboarding__sub {
  margin: var(--ph-space-5) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-onboarding__logs {
  margin: 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 2;
}

.ph-onboarding__log-action {
  margin: 0 var(--ph-space-2);
  font-weight: 600;
}

.ph-onboarding__material {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) minmax(0, 1fr) 150px 150px max-content;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-2);
}

.ph-onboarding__alert {
  margin: var(--ph-space-4) 0 0;
}

.ph-onboarding__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
