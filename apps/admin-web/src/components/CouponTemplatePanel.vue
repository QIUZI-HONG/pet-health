<script setup lang="ts">
/**
 * 券模板：平台统一维护的券目录（列表 / 新建 / 修改 / 启停）。
 *
 * 为什么模板只能由平台建（contract/admin.yaml 的 `/coupon-templates`）：统一券池是这个机制的核心，
 * 放开自建等于没有券池——服务者侧只能「选券并承诺额度」（`/provider/coupon-contributions`）。
 * 所以这一页的写入口只有运营，服务者那一边看不到「新建模板」。
 *
 * 三条口径决定了表单长什么样：
 * - **编码与成本归属创建后不可改**：编码挂在已发出的券上，成本归属一旦有人按它贡献过额度，
 *   改了就改了考核的归属（后端 40001，不是静默忽略）——所以编辑时这两项禁用；
 * - **适用范围与标准目录编码挂钩**：`scope_type=1` 用分类编码、`=2` 用目录项编码，写错了要到
 *   核销时才会暴露，所以分类给勾选框（编码从标准目录来，不靠手打），目录项编码手填并写明后端会核对；
 * - **发放上限只对平台补贴券有意义**：服务者成本券传了会被忽略（不报错）——表单里按成本归属
 *   显隐这一项，省得运营以为设了上限就真的限住了。
 *
 * 停用（0）**只挡新的发放与新的贡献**：已发出的券照常可核销（用户手上的券不是运营可撤销的承诺），
 * 服务者已承诺的额度账也留着。模板不做物理删除——券实例引用它，删行会让历史券失去定义。
 */
import { ref } from "vue";
import { formatAmount, formatDateTime, isRangeBoundValid, parseAmountToCents } from "@pet-health/shared";
import { ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import {
  adminApp,
  type CategoryRow,
  type CouponTemplateRequest,
  type CouponTemplateRow,
} from "../api/adminApi";

/** 标准目录分类（适用范围里 `scope_type=1` 的取值来源）由页面加载一次传进来——理由同 CatalogView */
const props = defineProps<{ categories: CategoryRow[] }>();
const emit = defineEmits<{ changed: [] }>();

const statusFilter = ref("");
const costBearerFilter = ref("");
const keyword = ref("");

const templates = usePagedList<CouponTemplateRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listCouponTemplates(
      {
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        costBearer: costBearerFilter.value === "" ? undefined : Number(costBearerFilter.value),
        keyword: keyword.value.trim() || undefined,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "券模板加载失败，请稍后重试" },
);

const submit = useSubmitAction("保存失败，请稍后重试");
const statusSubmit = useSubmitAction("启停失败，请稍后重试");
/** null = 新建；有值 = 正在改这个模板 */
const editing = ref<CouponTemplateRow | null>(null);
const formVisible = ref(false);
const form = ref({
  code: "",
  name: "",
  faceValue: "",
  minAmount: "0.00",
  validDays: "30",
  costBearer: "2",
  scopeType: "0",
  scopeCodes: "",
  /** 平台补贴券才有意义；置空 = 不限 */
  issueLimit: "",
  description: "",
});
const localError = ref("");

function reload(): void {
  void templates.reload();
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = {
    code: "",
    name: "",
    faceValue: "",
    minAmount: "0.00",
    validDays: "30",
    costBearer: "2",
    scopeType: "0",
    scopeCodes: "",
    issueLimit: "",
    description: "",
  };
  formVisible.value = true;
}

function openEdit(row: CouponTemplateRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    code: row.code,
    name: row.name,
    faceValue: row.face_value,
    minAmount: row.min_amount,
    validDays: String(row.valid_days),
    costBearer: String(row.cost_bearer),
    scopeType: String(row.scope_type),
    scopeCodes: (row.scope_codes ?? []).join(", "),
    issueLimit: row.issue_limit === null || row.issue_limit === undefined ? "" : String(row.issue_limit),
    description: row.description ?? "",
  };
  formVisible.value = true;
}

/** 适用范围的多选：勾上的分类编码就是 `scope_codes`（`scope_type=1` 时用） */
function toggleScopeCategory(code: string, checked: boolean): void {
  const current = splitCodes(form.value.scopeCodes);
  const next = checked ? [...current, code] : current.filter((item) => item !== code);
  form.value.scopeCodes = next.join(", ");
}

function splitCodes(text: string): string[] {
  return text
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item !== "");
}

/** 本地先拦一遍：只拦「一定会被后端拒」的，规则与契约的 40001 条件逐条对应。 */
function validate(): boolean {
  if (!/^[A-Z]{2}-\d{3}$/.test(form.value.code.trim())) {
    localError.value = "模板编码要「两位大写字母 + 三位数字」（如 CP-001），创建后不可改、不可复用";
    return false;
  }
  if (form.value.name.trim() === "") {
    localError.value = "请填写券名称";
    return false;
  }
  const faceValueCents = parseAmountToCents(form.value.faceValue);
  if (faceValueCents === null || faceValueCents <= 0) {
    localError.value = "面额要大于 0，最多两位小数（金额不走浮点，两位小数之外的一律拒）";
    return false;
  }
  if (!isRangeBoundValid(form.value.minAmount)) {
    localError.value = "使用门槛要非负、最多两位小数；无门槛填 0.00";
    return false;
  }
  const validDays = Number(form.value.validDays);
  if (!Number.isInteger(validDays) || validDays < 1 || validDays > 3650) {
    localError.value = "有效期要 1–3650 天（自发放之日起算）";
    return false;
  }
  const scopeType = Number(form.value.scopeType);
  const scopeCodes = splitCodes(form.value.scopeCodes);
  if (scopeType !== 0 && scopeCodes.length === 0) {
    localError.value = "选了适用范围就必须给编码：限分类给分类编码、限目录项给项目编码";
    return false;
  }
  if (scopeType === 0 && scopeCodes.length > 0) {
    localError.value = "「不限」不允许带适用范围编码（后端会拒成 40001）";
    return false;
  }
  const issueLimit = form.value.issueLimit.trim();
  if (issueLimit !== "" && (!Number.isInteger(Number(issueLimit)) || Number(issueLimit) < 1)) {
    localError.value = "发放上限要是正整数，不限就留空";
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): CouponTemplateRequest {
  const scopeType = Number(form.value.scopeType);
  const scopeCodes = splitCodes(form.value.scopeCodes);
  const issueLimit = form.value.issueLimit.trim();
  const body: CouponTemplateRequest = {
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    face_value: form.value.faceValue.trim(),
    min_amount: form.value.minAmount.trim(),
    valid_days: Number(form.value.validDays),
    cost_bearer: Number(form.value.costBearer),
    scope_type: scopeType,
    description: form.value.description.trim() || null,
  };
  // `scope_type=0` 时**不能带** scope_codes（带了是 40001，不是静默忽略），所以只在有范围时挂上它
  if (scopeType !== 0) {
    body.scope_codes = scopeCodes;
  }
  // 发放上限只对平台补贴券有意义：服务者成本券传了会被忽略，那就干脆不传，免得读的人以为它生效了
  if (Number(form.value.costBearer) === 2 && issueLimit !== "") {
    body.issue_limit = Number(issueLimit);
  }
  return body;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateCouponTemplate(target.id, body) : adminApp.createCouponTemplate(body)),
    target ? "模板已更新：只影响之后新发的券，已发出的券仍是发放时的快照" : "模板已创建",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  reload();
  emit("changed");
}

/** 启停：停用只挡新的发放与新的贡献，已发出的券照常核销——所以提示语要把这句说出来 */
async function toggleStatus(row: CouponTemplateRow): Promise<void> {
  const next = row.status === 1 ? 0 : 1;
  const outcome = await statusSubmit.run(
    () => adminApp.updateCouponTemplateStatus(row.id, next as 0 | 1),
    next === 0 ? "已停用：挡住新的发放与新的贡献，已发出的券照常可核销" : "已启用",
  );
  if (!outcome.ok) return;
  reload();
  emit("changed");
}

/** 已发 / 上限：上限为空表示不限（服务者成本券恒为空） */
function issueLimitText(row: CouponTemplateRow): string {
  if (row.issue_limit === null || row.issue_limit === undefined) return "不限";
  return `${row.issued_count ?? 0} / ${row.issue_limit}`;
}

function costBearerLabel(value: number): string {
  return value === 2 ? "平台补贴" : "服务者成本";
}

function scopeTypeLabel(value: number): string {
  if (value === 1) return "限服务分类";
  if (value === 2) return "限目录项";
  return "不限";
}

void templates.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-cp__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">启用</option>
          <option value="0">停用</option>
        </select>
      </label>
      <label class="ph-field ph-cp__filter">
        <span class="ph-field__label">成本归属</span>
        <select v-model="costBearerFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">服务者成本</option>
          <option value="2">平台补贴</option>
        </select>
      </label>
      <input v-model="keyword" class="ph-input ph-cp__search" maxlength="64" placeholder="按券名搜索" @keyup.enter="reload" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="templates.loading.value" @click="reload">搜索</button>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新建券模板</button>
    </div>

    <p class="ph-field__hint">
      模板只能由平台创建（ADR-0037 第三节）：统一券池是这套机制的核心，放开自建等于没有券池，服务者侧只能选券并承诺额度。停用只挡新的发放与新的贡献，已发出的券照常可核销。
    </p>

    <ConsoleListState
      :loading="templates.loading.value"
      :forbidden="templates.forbidden.value"
      :error-message="templates.errorMessage.value"
      :request-id="templates.requestId.value"
      :is-empty="templates.isEmpty.value"
      loading-title="正在加载券模板"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取券模板。"
      empty-title="还没有券模板"
      empty-description="先建模板（面额 / 门槛 / 有效期 / 适用范围 / 成本归属），服务者才能选它并承诺额度。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>面额 / 门槛</th>
              <th>有效期</th>
              <th>成本归属</th>
              <th>适用范围</th>
              <th>已发 / 上限</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in templates.items.value" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td class="ph-table__num">{{ formatAmount(row.face_value) }} / 满 {{ formatAmount(row.min_amount) }}</td>
              <td class="ph-table__num">{{ row.valid_days }} 天</td>
              <td>
                <span class="ph-tag" :class="row.cost_bearer === 2 ? 'ph-tag--info' : ''">{{ costBearerLabel(row.cost_bearer) }}</span>
              </td>
              <td>{{ row.scope_desc ?? scopeTypeLabel(row.scope_type) }}</td>
              <td class="ph-table__num">{{ issueLimitText(row) }}</td>
              <td>
                <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.status === 1 ? "启用" : "停用" }}
                </span>
              </td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">编辑</button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="statusSubmit.submitting.value"
                    @click="toggleStatus(row)"
                  >
                    {{ row.status === 1 ? "停用" : "启用" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ templates.total.value }} 个模板</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="templates.page.value <= 1 || templates.loading.value"
          @click="templates.prevPage"
        >
          上一页
        </button>
        <span>第 {{ templates.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!templates.hasMore.value || templates.loading.value"
          @click="templates.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <p v-if="statusSubmit.errorMessage.value" class="ph-alert ph-alert--error ph-cp__alert">
      {{ statusSubmit.errorMessage.value }}
      <span v-if="statusSubmit.requestId.value" class="ph-text-weak">（请求 ID：{{ statusSubmit.requestId.value }}）</span>
    </p>
    <p v-if="statusSubmit.doneMessage.value" class="ph-alert ph-alert--info ph-cp__alert">{{ statusSubmit.doneMessage.value }}</p>

    <form v-if="formVisible" class="ph-card ph-cp__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑券模板：${editing.name}` : "新建券模板" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">模板编码</span>
          <input v-model="form.code" class="ph-input" maxlength="8" :disabled="editing !== null" placeholder="CP-001" />
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "两位大写字母 + 三位数字；创建后不可改、不可复用" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">券名称</span>
          <input v-model="form.name" class="ph-input" maxlength="128" placeholder="如：新客立减 20 元" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">抵扣面额（元）</span>
          <input v-model="form.faceValue" class="ph-input" inputmode="decimal" placeholder="20.00" />
          <span class="ph-field__hint">大于 0，最多两位小数</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">使用门槛（元）</span>
          <input v-model="form.minAmount" class="ph-input" inputmode="decimal" placeholder="0.00" />
          <span class="ph-field__hint">订单总额不低于它才可用；0.00 = 无门槛</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">有效期（天）</span>
          <input v-model="form.validDays" class="ph-input" inputmode="numeric" placeholder="30" />
          <span class="ph-field__hint">自发放之日起算，1–3650 天；改它只影响之后新发的券</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">成本归属</span>
          <select v-model="form.costBearer" class="ph-select" :disabled="editing !== null">
            <option value="2">平台补贴（平台出这张券）</option>
            <option value="1">服务者成本（服务者承诺额度）</option>
          </select>
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "只影响核销统计与考核，不产生资金（ADR-0036）；创建后不可改" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">适用范围</span>
          <select v-model="form.scopeType" class="ph-select">
            <option value="0">不限</option>
            <option value="1">限服务分类</option>
            <option value="2">限目录项</option>
          </select>
        </label>
        <label v-if="form.costBearer === '2'" class="ph-field">
          <span class="ph-field__label">发放上限（张，留空 = 不限）</span>
          <input v-model="form.issueLimit" class="ph-input" inputmode="numeric" placeholder="留空表示不限" />
          <span class="ph-field__hint">只对平台补贴券有意义：服务者成本券的额度由服务者的贡献决定</span>
        </label>

        <div v-if="form.scopeType === '1'" class="ph-field ph-form-grid--full">
          <span class="ph-field__label">适用的服务分类</span>
          <div class="ph-cp__scopes">
            <label v-for="category in props.categories" :key="category.id" class="ph-cp__scope">
              <input
                type="checkbox"
                :checked="splitCodes(form.scopeCodes).includes(category.code)"
                @change="toggleScopeCategory(category.code, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ category.code }} · {{ category.name }}</span>
            </label>
            <span v-if="props.categories.length === 0" class="ph-field__hint">
              标准目录里还没有分类，先到「标准目录管理」建分类。
            </span>
          </div>
          <span class="ph-field__hint">分类编码从标准目录来（手打容易错，错了要到核销时才暴露）。</span>
        </div>
        <label v-else-if="form.scopeType === '2'" class="ph-field ph-form-grid--full">
          <span class="ph-field__label">适用的目录项编码</span>
          <input v-model="form.scopeCodes" class="ph-input" placeholder="HE-001, HE-002" />
          <span class="ph-field__hint">目录项编码（形如 HE-001），逗号分隔；后端会到标准目录里核对，找不到即 40001。</span>
        </label>

        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <input v-model="form.description" class="ph-input" maxlength="255" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-cp__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-cp__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-cp__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
      <p v-if="editing" class="ph-field__hint">
        改面额 / 门槛 / 有效期**只影响之后新发的券**：已经发到用户手里的券是平台对用户的承诺，存的是发放时的快照。
      </p>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-cp__alert">{{ submit.doneMessage.value }}</p>
  </div>
</template>

<style scoped>
.ph-cp__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-cp__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-cp__search {
  width: 180px;
}

.ph-cp__form {
  margin-top: var(--ph-space-5);
}

.ph-cp__alert {
  margin-top: var(--ph-space-4);
}

.ph-cp__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

.ph-cp__scopes {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  padding: var(--ph-space-2) 0;
}

.ph-cp__scope {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 13px;
}
</style>
