<script setup lang="ts">
/**
 * 联盟分类维度维护：列表、新建、改名 / 说明 / 排序、启用 / 停用。
 *
 * 一期验收标准的「服务者联盟分类：分类维度维护」那一项（术语按 CONTEXT.md 统一说服务者）。
 * 这张表就是 `provider.category` 的**值域**——种子三档来自交付文档 7.2
 * （直接同业 / 直接异业 / 间接异业），但值域从此由运营维护：新增一档立刻可指派，不必发版。
 *
 * 四条口径来自契约（contract/admin.yaml 的 `/alliance-categories`）：
 * - **默认连停用的维度一起给**：停用不移动既有归属，藏起来会让那些门店的分类列显示空白；
 * - **编码不可改**：它是稳定标识（前端与报表引用它），所以编辑表单里这个字段是只读的；
 * - **没有删除接口**：维度被 `provider.category` 引用，删掉会让历史归属悬空——停用是唯一的收敛手段；
 * - **`provider_count` 是当前归属的门店数**：停用之前先看这一列，就知道会影响多少家。
 *
 * 归属（哪家店属于哪一档）不在这里改，在「服务者列表与状态」分段的详情里——
 * 改的是服务者，就该在那个对象的页面上做。
 */
import { ref } from "vue";
import { formatDateTime, isIdentityError, toApiFailure } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type AllianceCategoryRequest, type AllianceCategoryView } from "../api/adminApi";

const loading = ref(false);
const forbidden = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const categories = ref<AllianceCategoryView[]>([]);

const submit = useSubmitAction("保存失败，请稍后重试");
/** null = 新建；有值 = 正在改这一档 */
const editing = ref<AllianceCategoryView | null>(null);
const formVisible = ref(false);
const form = ref({ code: "", name: "", description: "", sortOrder: "0" });

async function load(): Promise<void> {
  loading.value = true;
  forbidden.value = false;
  errorMessage.value = "";
  requestId.value = "";
  try {
    categories.value = await adminApp.listAllianceCategories();
  } catch (error) {
    if (isIdentityError(error)) {
      forbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "联盟分类加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  submit.clear();
  editing.value = null;
  form.value = { code: "", name: "", description: "", sortOrder: "0" };
  formVisible.value = true;
}

function openEdit(row: AllianceCategoryView): void {
  submit.clear();
  editing.value = row;
  form.value = {
    // 生成物里这些字段是可选的（契约没写 required）；列表行来自服务端，实际都有值
    code: row.code ?? "",
    name: row.name ?? "",
    description: row.description ?? "",
    sortOrder: String(row.sort_order ?? 0),
  };
  formVisible.value = true;
}

const localError = ref("");
function validate(): boolean {
  if (!editing.value && !/^[A-Z][A-Z0-9_]{1,31}$/.test(form.value.code.trim())) {
    localError.value = "维度编码要 2–32 位、大写字母开头（如 DIRECT_PEER）";
    return false;
  }
  if (form.value.name.trim() === "") {
    localError.value = "请填写维度名称";
    return false;
  }
  localError.value = "";
  return true;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body: AllianceCategoryRequest = {
    // 修改路径上后端会忽略 code（编码不可改），仍然原样回传：契约里它是必填
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    description: form.value.description.trim() || null,
    sort_order: Number(form.value.sortOrder) || 0,
  };
  const target = editing.value;
  const outcome = await submit.run(
    () =>
      target
        ? adminApp.updateAllianceCategory(requireId(target), body)
        : adminApp.createAllianceCategory(body),
    target ? "联盟分类已更新" : "联盟分类已创建",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

/** 生成物里 `id` 是可选的；这里只在真拿到 id 时才发请求（列表行来自服务端，恒有值）。 */
function requireId(row: AllianceCategoryView): number {
  if (row.id == null) {
    throw new Error("维度缺少 id，无法提交修改");
  }
  return row.id;
}

/** 启停是独立的动作（不是编辑表单的一部分）：改归属规则与改名字是两件事。 */
async function toggle(row: AllianceCategoryView): Promise<void> {
  const next: 0 | 1 = row.enabled === 1 ? 0 : 1;
  const action = next === 1 ? "启用" : "停用";
  const outcome = await submit.run(
    () => adminApp.updateAllianceCategoryStatus(requireId(row), next),
    `「${row.name}」已${action}`,
  );
  if (!outcome.ok) return;
  await load();
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <button type="button" class="ph-button ph-button--secondary" :disabled="loading" @click="load">刷新</button>
      <span class="ph-text-weak">默认连停用的维度一起显示（停用不移动既有归属）</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新建维度</button>
    </div>

    <p class="ph-field__hint">
      这张表是服务者「联盟分类」的取值范围。停用一档不会改变已在档上的门店，只挡住新的指派；没有删除接口——
      删掉会让历史归属悬空。要调整已归属的门店，去「服务者列表与状态」分段。
    </p>

    <ConsoleListState
      :loading="loading"
      :forbidden="forbidden"
      :error-message="errorMessage"
      :request-id="requestId"
      :is-empty="categories.length === 0"
      loading-title="正在加载联盟分类"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取联盟分类维度。"
      empty-title="还没有维度"
      empty-description="先建一档维度，再去服务者列表里指定归属。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>取值</th>
              <th>编码</th>
              <th>名称</th>
              <th>说明</th>
              <th>归属门店数</th>
              <th>状态</th>
              <th>排序</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in categories" :key="row.id">
              <td class="ph-table__num">{{ row.id }}</td>
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td class="ph-text-weak">{{ row.description ?? "—" }}</td>
              <td class="ph-table__num">{{ row.provider_count ?? 0 }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled === 1 ? "启用" : "停用" }}
                </span>
              </td>
              <td class="ph-table__num">{{ row.sort_order ?? 0 }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">编辑</button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="submit.submitting.value"
                    @click="toggle(row)"
                  >
                    {{ row.enabled === 1 ? "停用" : "启用" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-alliance__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑维度：${editing.name}` : "新建维度" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">维度编码</span>
          <input v-model="form.code" class="ph-input" maxlength="32" :disabled="editing !== null" placeholder="DIRECT_PEER" />
          <span class="ph-field__hint">{{ editing ? "创建后不可变更（它是稳定标识）" : "2–32 位，大写字母开头；创建后不可改" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">名称</span>
          <input v-model="form.name" class="ph-input" maxlength="64" placeholder="如：直接同业" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">排序</span>
          <input v-model="form.sortOrder" class="ph-input" inputmode="numeric" placeholder="0" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <input v-model="form.description" class="ph-input" maxlength="255" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-alliance__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-alliance__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-alliance__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-alliance__alert">{{ submit.doneMessage.value }}</p>
  </div>
</template>

<style scoped>
.ph-alliance__form {
  margin-top: var(--ph-space-5);
}

.ph-alliance__alert {
  margin-top: var(--ph-space-4);
}

.ph-alliance__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
