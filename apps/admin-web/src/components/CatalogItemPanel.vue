<script setup lang="ts">
/**
 * 标准目录项：列表（含停用）、新建、修改、启用 / 停用。
 *
 * 契约里三条容易被忽略的口径（contract/admin.yaml 的 `/catalog/items`）：
 * - **编码与所属分类创建后不可变更**：编码的前两位就是分类前缀，换分类必然要换码，而编码不可改。
 *   所以编辑表单里这两个字段是只读的。
 * - **区间必须下限 ≤ 上限**（非负、最多两位小数）：写反了在入口就被拦住，否则会在某个服务者
 *   定价时以「越界」的形式暴露，那时已分不清是区间错还是价格错。前端也先判一次（整数分比较）。
 * - **停用只挡新的选品**：已上架的服务项保持原状、照常对外，不自动下架；
 *   收窄区间同理（ADR-0034 的代价一节——自动下架会打断已预约的订单）。这两句写在操作旁边。
 */
import { computed, ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { adminApp, type CategoryRow, type ItemRow, type ServiceItemRequest } from "../api/adminApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { applicablePetsLabel, formatAmountRange, isRangeBoundValid, parseAmountToCents } from "@pet-health/shared";

const props = defineProps<{ categories: CategoryRow[] }>();
const emit = defineEmits<{ changed: [] }>();

const categoryFilter = ref("");
const statusFilter = ref("");
const keyword = ref("");

const items = usePagedList<ItemRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listItems(
      {
        categoryCode: categoryFilter.value || undefined,
        keyword: keyword.value.trim() || undefined,
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "目录项加载失败，请稍后重试" },
);

const submit = useSubmitAction("保存失败，请稍后重试");
const editing = ref<ItemRow | null>(null);
const formVisible = ref(false);
const form = ref({
  code: "",
  categoryCode: "",
  name: "",
  priceMin: "",
  priceMax: "",
  priceUnit: "次",
  durationMinutes: "",
  applicablePets: "3",
  description: "",
  sortOrder: "0",
});

/** 列表重载：分类 / 状态 / 关键字三个筛选与刷新、错误重试共用（loader 每次现读这三个 ref）。 */
function reload(): void {
  void items.reload();
}

/** 新建：编码与分类可填（编辑时才只读），分类预选第一个；先 clear()，免得上次的失败文案跟过来。 */
function openCreate(): void {
  submit.clear();
  editing.value = null;
  form.value = {
    code: "",
    categoryCode: props.categories[0]?.code ?? "",
    name: "",
    priceMin: "",
    priceMax: "",
    priceUnit: "次",
    durationMinutes: "",
    applicablePets: "3",
    description: "",
    sortOrder: "0",
  };
  formVisible.value = true;
}

/** 编辑：编码与分类从行里带出并只读（创建后不可变更，见文件头）；其余字段一律转成表单的字符串。 */
function openEdit(row: ItemRow): void {
  submit.clear();
  editing.value = row;
  form.value = {
    code: row.code,
    categoryCode: row.category_code ?? "",
    name: row.name,
    priceMin: row.price_min,
    priceMax: row.price_max,
    priceUnit: row.price_unit ?? "次",
    durationMinutes: row.duration_minutes ? String(row.duration_minutes) : "",
    applicablePets: String(row.applicable_pets ?? 3),
    description: row.description ?? "",
    sortOrder: String(row.sort_order ?? 0),
  };
  formVisible.value = true;
}

const localError = computed(() => {
  if (form.value.categoryCode === "") return "请选择所属分类";
  if (form.value.name.trim() === "") return "请填写项目名称";
  // 区间边界允许 0（0.00–50.00 的试吃装是合法区间），所以判的是「非负」而不是「大于 0」
  if (!isRangeBoundValid(form.value.priceMin)) return "区间下限填 0 或正数，最多两位小数";
  if (!isRangeBoundValid(form.value.priceMax)) return "区间上限填 0 或正数，最多两位小数";
  const min = parseAmountToCents(form.value.priceMin);
  const max = parseAmountToCents(form.value.priceMax);
  if (min !== null && max !== null && min > max) return "区间下限不能高于上限";
  // 编码规则：两位大写字母 + 三位数字，且前缀要与分类一致（服务端也会判 40001）
  if (editing.value === null && !/^[A-Z]{2}-\d{3}$/.test(form.value.code.trim())) {
    return "项目编码要形如 HE-001（两位大写字母 + 三位数字）";
  }
  const prefix = props.categories.find((category) => category.code === form.value.categoryCode)?.item_code_prefix;
  if (prefix && !form.value.code.trim().startsWith(`${prefix}-`)) {
    return `编码前缀要与分类一致：本分类的项目编码应以 ${prefix}- 开头`;
  }
  const duration = Number(form.value.durationMinutes);
  if (form.value.durationMinutes.trim() !== "" && (!Number.isInteger(duration) || duration < 1 || duration > 1440)) {
    return "时长填 1–1440 之间的整数分钟（不清楚就留空）";
  }
  return "";
});

/** 新建 / 修改同一入口（editing 为空即新建）；金额按两位小数字符串提交不走浮点；成功后重载列表。 */
async function save(): Promise<void> {
  if (localError.value !== "") {
    submit.errorMessage.value = localError.value;
    return;
  }
  const body: ServiceItemRequest = {
    code: form.value.code.trim(),
    category_code: form.value.categoryCode,
    name: form.value.name.trim(),
    // 金额一律按两位小数字符串提交（不经过浮点）
    price_min: form.value.priceMin.trim(),
    price_max: form.value.priceMax.trim(),
    price_unit: form.value.priceUnit.trim() || "次",
    duration_minutes: form.value.durationMinutes.trim() === "" ? null : Number(form.value.durationMinutes),
    applicable_pets: Number(form.value.applicablePets),
    description: form.value.description.trim() || null,
    sort_order: Number(form.value.sortOrder) || 0,
  };
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateItem(target.id, body) : adminApp.createItem(body)),
    target ? "目录项已更新（存量服务项不会自动收敛，下次改价或重审时按新区间生效）" : "目录项已创建",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  emit("changed");
  reload();
}

/** 启停只挡新的选品：已上架的服务项照常对外（ADR-0034）；与保存共用提交态，忙时一起禁用。 */
async function toggleStatus(row: ItemRow): Promise<void> {
  const next: 0 | 1 = row.status === 1 ? 0 : 1;
  const outcome = await submit.run(
    () => adminApp.updateItemStatus(row.id, next),
    next === 0 ? "已停用：只挡新的选品，已上架的服务项照常对外" : "已启用",
  );
  if (outcome.ok) reload();
}
// 首屏加载一次：这两个面板是「打开就该有内容」的列表，等用户改筛选才发请求等于空页
void items.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-item__filter">
        <span class="ph-field__label">分类</span>
        <select v-model="categoryFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option v-for="category in props.categories" :key="category.code" :value="category.code">
            {{ category.name }}
          </option>
        </select>
      </label>
      <label class="ph-field ph-item__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">启用</option>
          <option value="0">停用</option>
        </select>
      </label>
      <input v-model="keyword" class="ph-input ph-item__search" maxlength="64" placeholder="按项目名称搜索" @keyup.enter="reload" />
      <button type="button" class="ph-button ph-button--secondary" @click="reload">搜索</button>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--primary" :disabled="props.categories.length === 0" @click="openCreate">
        新建目录项
      </button>
    </div>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-item__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-item__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="items.loading.value"
      :forbidden="items.forbidden.value"
      :error-message="items.errorMessage.value"
      :request-id="items.requestId.value"
      :is-empty="items.isEmpty.value"
      loading-title="正在加载目录项"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取目录项。"
      empty-title="没有匹配的目录项"
      empty-description="换个筛选条件，或新建一个。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编码</th>
              <th>项目</th>
              <th>分类</th>
              <th>区间</th>
              <th>单位</th>
              <th>时长</th>
              <th>适用</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items.items.value" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td>{{ row.category_name ?? row.category_code ?? "—" }}</td>
              <td class="ph-table__num">{{ formatAmountRange(row.price_min, row.price_max) }}</td>
              <td>{{ row.price_unit ?? "—" }}</td>
              <td>{{ row.duration_minutes ? `${row.duration_minutes} 分钟` : "—" }}</td>
              <td>{{ applicablePetsLabel(row.applicable_pets) }}</td>
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
                    :disabled="submit.submitting.value"
                    :title="row.status === 1 ? '停用只挡新的选品，已上架的服务项不自动下架' : '启用后服务者可以重新选品'"
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
        <span>共 {{ items.total.value }} 项</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="items.page.value <= 1 || items.loading.value"
          @click="items.prevPage"
        >
          上一页
        </button>
        <span>第 {{ items.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!items.hasMore.value || items.loading.value"
          @click="items.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-item__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑目录项：${editing.name}` : "新建目录项" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">项目编码</span>
          <input v-model="form.code" class="ph-input" maxlength="16" :disabled="editing !== null" placeholder="HE-001" />
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "两位大写字母 + 三位数字，前缀要与分类一致" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">所属分类</span>
          <select v-model="form.categoryCode" class="ph-select" :disabled="editing !== null">
            <option v-for="category in props.categories" :key="category.code" :value="category.code">
              {{ category.name }}（{{ category.item_code_prefix }}-）
            </option>
          </select>
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "项目编码的前两位由分类前缀决定" }}</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">项目名称</span>
          <input v-model="form.name" class="ph-input" maxlength="128" placeholder="如：犬只基础体检" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">区间下限（元）</span>
          <input v-model="form.priceMin" class="ph-input" inputmode="decimal" placeholder="如 100.00" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">区间上限（元）</span>
          <input v-model="form.priceMax" class="ph-input" inputmode="decimal" placeholder="如 300.00" />
          <span class="ph-field__hint">收窄区间不会自动下架存量服务项（ADR-0034）</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">计价单位</span>
          <input v-model="form.priceUnit" class="ph-input" maxlength="16" placeholder="次 / 只 / 天 / 课时 / 件" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">时长（分钟，可选）</span>
          <input v-model="form.durationMinutes" class="ph-input" inputmode="numeric" placeholder="如 60" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">适用宠物</span>
          <select v-model="form.applicablePets" class="ph-select">
            <option value="1">犬</option>
            <option value="2">猫</option>
            <option value="3">犬猫</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">排序</span>
          <input v-model="form.sortOrder" class="ph-input" inputmode="numeric" placeholder="0" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <textarea v-model="form.description" class="ph-textarea" maxlength="512" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-item__alert">{{ localError }}</p>
      <div class="ph-item__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-item__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-item__filter .ph-select {
  width: auto;
  min-width: 110px;
}

.ph-item__search {
  width: 200px;
}

.ph-item__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-item__form {
  margin-top: var(--ph-space-5);
}

.ph-item__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
