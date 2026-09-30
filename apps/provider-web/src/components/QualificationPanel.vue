<script setup lang="ts">
/**
 * 资质材料：清单、到期提示、补交 / 更新。
 *
 * ⚠️ **清单只能从入驻申请详情读**：`GET /profile`（`ProviderProfileView`）里**没有** qualifications
 * 字段，而 `PUT /profile/qualifications` 的返回值也是它——所以「读清单」这件事在契约里只有
 * `/onboarding/applications/{id}` 一条路（申请详情的材料与门店的资质是同一批记录，都按
 * `provider_id` 查）。这是契约现状，不是这一页绕路：等 `ProviderProfileView` 补上这个字段，
 * 改成读 profile 即可（本组件只依赖 `materials` 这个数组）。
 *
 * 补交有两条必须先说清楚的口径（否则用户会以为在「追加」）：
 * - **整体替换**：提交的清单会替换现有全部材料，所以要保留的材料得一起填；
 * - **证件号读回来是脱敏值**（ADR-0013），不能当明文回填——表单里留空，需要保留证件号的材料
 *   要重新输入一次（不填就是这份材料没有证件号）。
 *
 * 到期口径与后端一致（`ProviderAccess.hasValidQualification`）：**被驳回的材料不算资质**，
 * 一份有效材料都没有时不能新增选品，已上架的也会被定时任务下架。
 */
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, formatDate, toApiFailure } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import {
  providerApp,
  type ProviderQualificationRequest,
  type ProviderQualificationView,
} from "../api/providerApi";
import { useSubmitAction } from "@pet-health/ui";
import { qualificationStatusLabel, qualificationTypeLabel } from "@pet-health/shared";
import { countsAsValid, expiryText, expiryTone, hasNoValidQualification } from "../utils/labels";

const props = defineProps<{
  /** 父级在申请提交/重提后 +1，用来触发重新读取材料清单 */
  refreshKey: number;
  /** 门店状态是否「正常」——只有正常状态能补交（其余状态后端回 40300） */
  writable: boolean;
}>();

const emit = defineEmits<{ changed: [] }>();

const loading = ref(false);
const forbidden = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const materials = ref<ProviderQualificationView[]>([]);

const formVisible = ref(false);
interface MaterialRow {
  type: string;
  name: string;
  certNo: string;
  validFrom: string;
  validUntil: string;
}

const rows = ref<MaterialRow[]>([emptyRow()]);
const submit = useSubmitAction("材料提交失败，请稍后重试");

function emptyRow(): MaterialRow {
  return { type: "1", name: "", certNo: "", validFrom: "", validUntil: "" };
}

async function load(): Promise<void> {
  loading.value = true;
  forbidden.value = false;
  errorMessage.value = "";
  requestId.value = "";
  try {
    // 先看有没有申请单：没有申请单就没有材料可读（未绑定服务者时列表为空）
    const page = await providerApp.listApplications({ page: 1, pageSize: 1 });
    const latest = page.list[0];
    if (!latest) {
      materials.value = [];
      return;
    }
    const detail = await providerApp.getApplication(latest.id);
    materials.value = detail.qualifications ?? [];
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null;
    if (apiError && (apiError.code === 40100 || apiError.code === 40101 || apiError.code === 40300)) {
      forbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "资质材料加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

watch(() => props.refreshKey, load);
onMounted(load);

const noValid = computed(() => hasNoValidQualification(materials.value));

/** 打开补交表单：把现有材料抄成待编辑的行（证件号留空，见文件头说明）。 */
function openForm(): void {
  submit.clear();
  const copied = materials.value.map<MaterialRow>((item) => ({
    type: String(item.type ?? 1),
    name: item.name ?? "",
    certNo: "",
    validFrom: item.valid_from ?? "",
    validUntil: item.valid_until ?? "",
  }));
  rows.value = copied.length > 0 ? copied : [emptyRow()];
  formVisible.value = true;
}

function addRow(): void {
  rows.value.push(emptyRow());
}

function removeRow(index: number): void {
  if (rows.value.length <= 1) return;
  rows.value.splice(index, 1);
}

const localError = computed(() => {
  const reversed = rows.value.find(
    (row) => row.validFrom !== "" && row.validUntil !== "" && row.validUntil < row.validFrom,
  );
  if (reversed) return "到期日不能早于生效日";
  return "";
});

async function confirmSubmit(): Promise<void> {
  if (localError.value !== "") {
    submit.errorMessage.value = localError.value;
    return;
  }
  const body = rows.value.map<ProviderQualificationRequest>((row) => ({
    type: Number(row.type),
    name: row.name.trim() === "" ? undefined : row.name.trim(),
    cert_no: row.certNo.trim() === "" ? null : row.certNo.trim(),
    valid_from: row.validFrom === "" ? null : row.validFrom,
    valid_until: row.validUntil === "" ? null : row.validUntil,
  }));
  const outcome = await submit.run(
    () => providerApp.updateQualifications({ qualifications: body }),
    "材料已提交，回到待审核；补交即可恢复上架资格",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  emit("changed");
  await load();
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <button type="button" class="ph-button ph-button--secondary" :disabled="loading" @click="load">刷新</button>
      <span class="ph-toolbar__spacer" />
      <button
        type="button"
        class="ph-button ph-button--primary"
        :disabled="!props.writable"
        :title="props.writable ? '' : '门店状态为正常时才能补交材料'"
        @click="openForm"
      >
        补交 / 更新材料
      </button>
    </div>

    <p v-if="noValid && !loading && materials.length >= 0" class="ph-alert ph-alert--warn">
      当前没有有效资质（材料过期或被驳回）：平台会下架全部服务项，补交新材料后即可重新上架。
    </p>

    <ConsoleListState
      :loading="loading"
      :forbidden="forbidden"
      :error-message="errorMessage"
      :request-id="requestId"
      :is-empty="materials.length === 0"
      loading-title="正在加载资质材料"
      empty-title="还没有材料记录"
      empty-description="材料随入驻申请一起提交；提交后这里会显示有效期与到期提示。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>名称</th>
              <th>证件号</th>
              <th>有效期</th>
              <th>状态</th>
              <th>有效期提示</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in materials" :key="item.id ?? item.name">
              <td>{{ qualificationTypeLabel(item.type) }}</td>
              <td>{{ item.name ?? "—" }}</td>
              <td>{{ item.cert_no ?? "—" }}</td>
              <td class="ph-table__num">
                {{ item.valid_from ? formatDate(item.valid_from) : "—" }} ~
                {{ item.valid_until ? formatDate(item.valid_until) : "长期有效" }}
              </td>
              <td>
                <span class="ph-tag" :class="item.status === 1 ? 'ph-tag--success' : item.status === 2 ? 'ph-tag--danger' : 'ph-tag--warning'">
                  {{ qualificationStatusLabel(item.status) }}
                </span>
              </td>
              <td>
                <span class="ph-tag" :class="expiryTone(item)">{{ expiryText(item) }}</span>
                <span v-if="!countsAsValid(item) && item.status !== 2" class="ph-text-weak">（不计入有效资质）</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-qual__form" @submit.prevent="confirmSubmit">
      <h4 class="ph-card__title">补交 / 更新资质材料</h4>
      <p class="ph-alert ph-alert--warn">
        提交的清单会<strong>整体替换</strong>现有全部材料，要保留的请一起填上。证件号读回来是脱敏值
        （如 9133**********1234），无法回填，需要保留的要重新输入一次。
      </p>

      <div v-for="(row, index) in rows" :key="index" class="ph-qual__row">
        <select v-model="row.type" class="ph-select" aria-label="材料类型">
          <option value="1">营业执照</option>
          <option value="2">执业许可证</option>
          <option value="3">法人身份证</option>
          <option value="4">训犬师认证</option>
          <option value="5">健康证</option>
          <option value="6">其他</option>
        </select>
        <input v-model="row.name" class="ph-input" maxlength="128" placeholder="材料名称" />
        <input v-model="row.certNo" class="ph-input" maxlength="64" placeholder="证件号（重填）" />
        <input v-model="row.validFrom" class="ph-input" type="date" aria-label="生效日" />
        <input v-model="row.validUntil" class="ph-input" type="date" aria-label="到期日" />
        <button type="button" class="ph-table__action" :disabled="rows.length <= 1" @click="removeRow(index)">
          删除
        </button>
      </div>
      <button type="button" class="ph-button ph-button--secondary" @click="addRow">再加一份材料</button>

      <p v-if="localError" class="ph-alert ph-alert--error ph-qual__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-qual__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-qual__alert">{{ submit.doneMessage.value }}</p>

      <div class="ph-qual__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "提交材料" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-qual__form {
  margin-top: var(--ph-space-5);
}

.ph-qual__row {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) minmax(0, 1fr) 150px 150px max-content;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-2);
}

.ph-qual__alert {
  margin-top: var(--ph-space-4);
}

.ph-qual__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
