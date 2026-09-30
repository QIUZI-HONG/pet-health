<script setup lang="ts">
/**
 * 标准目录分类：列表、新建、修改（改名 / 排序 / 说明）。
 *
 * 三条口径来自契约（contract/admin.yaml 的 `/catalog/categories`）：
 * - **编码与编码前缀不可变更**：已有项目与将来的券适用范围都引用它们，所以编辑表单里这两个
 *   字段是只读的（传了别的值后端也是 40001，不是静默忽略）；
 * - **默认连停用的一起给**：运营要能看见自己停用过的分类；
 * - **没有「停用/启用分类」接口**：契约里只有 POST 与 PUT，`ServiceCategoryRequest` 里没有 status
 *   字段——所以这一页**不摆启停按钮**（摆了就是假动作）。项目侧的启停在「目录项」分段里做。
 *
 * 前缀决定项目编码的前两位（HE / GR / …），而编码发布后不可改、不可复用——建分类时就要给对，
 * 所以新建表单把这件事写在字段提示里。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { adminApp, type CategoryRow, type ServiceCategoryRequest } from "../api/adminApi";
import { useSubmitAction } from "@pet-health/ui";
import { isIdentityError, toApiFailure } from "@pet-health/shared";

const emit = defineEmits<{ changed: [] }>();

const loading = ref(false);
const forbidden = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const categories = ref<CategoryRow[]>([]);

const submit = useSubmitAction("保存失败，请稍后重试");
/** null = 新建；有值 = 正在改这个分类 */
const editing = ref<CategoryRow | null>(null);
const formVisible = ref(false);
const form = ref({ code: "", itemCodePrefix: "", name: "", icon: "", description: "", sortOrder: "0" });

async function load(): Promise<void> {
  loading.value = true;
  forbidden.value = false;
  errorMessage.value = "";
  requestId.value = "";
  try {
    categories.value = await adminApp.listCategories();
  } catch (error) {
    if (isIdentityError(error)) {
      forbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "分类加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  submit.clear();
  editing.value = null;
  form.value = { code: "", itemCodePrefix: "", name: "", icon: "", description: "", sortOrder: "0" };
  formVisible.value = true;
}

function openEdit(row: CategoryRow): void {
  submit.clear();
  editing.value = row;
  form.value = {
    code: row.code,
    itemCodePrefix: row.item_code_prefix,
    name: row.name,
    icon: row.icon ?? "",
    description: row.description ?? "",
    sortOrder: String(row.sort_order ?? 0),
  };
  formVisible.value = true;
}

const localError = ref("");
function validate(): boolean {
  if (!/^[A-Z][A-Z0-9_]{1,31}$/.test(form.value.code)) {
    localError.value = "分类编码要 2–32 位、大写字母开头（如 HOSPITAL）";
    return false;
  }
  if (!/^[A-Z]{2}$/.test(form.value.itemCodePrefix)) {
    localError.value = "编码前缀要两位大写字母（如 HE / GR）";
    return false;
  }
  if (form.value.name.trim() === "") {
    localError.value = "请填写分类名称";
    return false;
  }
  localError.value = "";
  return true;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body: ServiceCategoryRequest = {
    code: form.value.code.trim(),
    item_code_prefix: form.value.itemCodePrefix.trim(),
    name: form.value.name.trim(),
    icon: form.value.icon.trim() || null,
    description: form.value.description.trim() || null,
    sort_order: Number(form.value.sortOrder) || 0,
  };
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateCategory(target.id, body) : adminApp.createCategory(body)),
    target ? "分类已更新" : "分类已创建",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  emit("changed");
  await load();
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <button type="button" class="ph-button ph-button--secondary" :disabled="loading" @click="load">刷新</button>
      <span class="ph-text-weak">默认连停用的分类一起显示（运营要能看见自己停用过的）</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新建分类</button>
    </div>

    <p class="ph-field__hint">
      分类没有「停用」接口：契约里只有新建与修改，停用是项目（目录项）那一层的动作。
    </p>

    <ConsoleListState
      :loading="loading"
      :forbidden="forbidden"
      :error-message="errorMessage"
      :request-id="requestId"
      :is-empty="categories.length === 0"
      loading-title="正在加载分类"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取标准目录分类。"
      empty-title="还没有分类"
      empty-description="先建分类（含编码前缀），再往里加项目。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编码</th>
              <th>前缀</th>
              <th>名称</th>
              <th>项目数</th>
              <th>状态</th>
              <th>排序</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in categories" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td class="ph-table__num">{{ row.item_code_prefix }}</td>
              <td>{{ row.name }}</td>
              <td class="ph-table__num">{{ row.item_count ?? 0 }}</td>
              <td>
                <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.status === 1 ? "启用" : "停用" }}
                </span>
              </td>
              <td class="ph-table__num">{{ row.sort_order ?? 0 }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">编辑</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-cat__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑分类：${editing.name}` : "新建分类" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">分类编码</span>
          <input v-model="form.code" class="ph-input" maxlength="32" :disabled="editing !== null" placeholder="HOSPITAL" />
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "2–32 位，大写字母开头；创建后不可改" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">编码前缀</span>
          <input v-model="form.itemCodePrefix" class="ph-input" maxlength="2" :disabled="editing !== null" placeholder="HE" />
          <span class="ph-field__hint">{{ editing ? "创建后不可变更" : "两位大写字母，项目编码的前两位；不可复用" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">名称</span>
          <input v-model="form.name" class="ph-input" maxlength="64" placeholder="如：医疗健康" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">排序</span>
          <input v-model="form.sortOrder" class="ph-input" inputmode="numeric" placeholder="0" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">图标标识（可选）</span>
          <input v-model="form.icon" class="ph-input" maxlength="64" placeholder="如 hospital" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <input v-model="form.description" class="ph-input" maxlength="255" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-cat__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-cat__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-cat__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-cat__alert">{{ submit.doneMessage.value }}</p>
  </div>
</template>

<style scoped>
.ph-cat__form {
  margin-top: var(--ph-space-5);
}

.ph-cat__alert {
  margin-top: var(--ph-space-4);
}

.ph-cat__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
