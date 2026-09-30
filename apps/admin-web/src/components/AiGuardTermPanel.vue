<script setup lang="ts">
/**
 * 护栏词表（`/api/v1/admin/ai/guard-terms`）：读列表 / 新增 / 修改。
 *
 * 护栏词是**输出侧**的过滤依据（ADR-0028 的医疗口径）：`kind=drug` 是药名（不许出现在给用户的
 * 文案里）、`kind=phrase` 是越界表述（诊断、剂量、处方）。加词即时生效。
 *
 * 为什么启停放在编辑表单里、而不是行内一个「停用」按钮：这是**减少防护面**的动作，
 * 一次误点会让某类词从此不再被拦。同一份契约里也没有 `DELETE`——护栏词只增不减，
 * 停用即可（留着它才解释得清「这句为什么被改写」）。同类同词重复是 40900，所以本地先把空值拦住。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type GuardTermRequest, type GuardTermRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const kindFilter = ref("");
const terms = useSection<GuardTermRow[]>();

const submit = useSubmitAction("护栏词保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这一条 */
const editing = ref<GuardTermRow | null>(null);
const formVisible = ref(false);
const form = ref({ kind: "phrase" as "drug" | "phrase", term: "", note: "", enabled: true });
const localError = ref("");

async function load(): Promise<void> {
  await terms.load(
    () => adminApp.listAiGuardTerms(kindFilter.value === "" ? undefined : { kind: kindFilter.value as "drug" | "phrase" }),
    "护栏词加载失败，请稍后重试",
  );
}

function rows(): GuardTermRow[] {
  return terms.data.value ?? [];
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = { kind: "phrase", term: "", note: "", enabled: true };
  formVisible.value = true;
}

function openEdit(row: GuardTermRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = { kind: row.kind as "drug" | "phrase", term: row.term, note: row.note ?? "", enabled: row.enabled };
  formVisible.value = true;
}

/** 本地先拦一遍：同类同词重复是 40900，空词没有意义 */
function validate(): boolean {
  if (form.value.term.trim() === "") {
    localError.value = "词条不能为空：输出侧就是按它匹配的";
    return false;
  }
  if (form.value.term.trim().length > 64) {
    localError.value = "词条最长 64 字";
    return false;
  }
  const duplicated = rows().some(
    (row) => row.id !== editing.value?.id && row.kind === form.value.kind && row.term === form.value.term.trim(),
  );
  if (duplicated) {
    localError.value = `同类同词已在表里（${form.value.kind === "drug" ? "药名" : "越界表述"}）：重复添加会被拒（40900）`;
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): GuardTermRequest {
  return {
    kind: form.value.kind,
    term: form.value.term.trim(),
    note: form.value.note.trim() || null,
    enabled: form.value.enabled,
  };
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateAiGuardTerm(target.id, body) : adminApp.createAiGuardTerm(body)),
    target
      ? `已更新 ${body.term}：${body.enabled ? "重新参与输出过滤" : "不再参与输出过滤"}`
      : "护栏词已新增（即时生效：最多滞后一个 TTL）",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

function kindLabel(value: string): string {
  return value === "drug" ? "药名" : "越界表述";
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-ai__filter">
        <span class="ph-field__label">类型</span>
        <select v-model="kindFilter" class="ph-select" @change="load">
          <option value="">全部</option>
          <option value="drug">药名</option>
          <option value="phrase">越界表述</option>
        </select>
      </label>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="terms.loading.value" @click="load">刷新</button>
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新增护栏词</button>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      护栏词管**输出**：药名与越界表述（诊断、剂量、处方）都不许出现在给用户的文案里。
      加词即时生效（最多滞后一个 TTL）；**没有删除动作**——只增不减，停用即可（停用要进编辑表单改，
      它减少的是防护面）。与红线词、敏感词都不相通：红线是输入侧短路、敏感词管社区内容。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="terms.loading.value"
      :forbidden="terms.forbidden.value"
      :error-message="terms.errorMessage.value"
      :request-id="terms.requestId.value"
      :is-empty="terms.loaded.value && rows().length === 0"
      loading-title="正在加载护栏词"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取护栏词表。"
      empty-title="护栏词表是空的"
      empty-description="空表意味着输出侧不做词级过滤，只靠提示词约束——这一页不预置任何词，按复核过的口径逐条加。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>词条</th>
              <th>说明</th>
              <th>参与过滤</th>
              <th>复核</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td>
                <span class="ph-tag" :class="row.kind === 'drug' ? 'ph-tag--danger' : 'ph-tag--warning'">
                  {{ kindLabel(row.kind) }}
                </span>
              </td>
              <td>{{ row.term }}</td>
              <td>{{ row.note ?? "—" }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled ? "参与" : "已停用" }}
                </span>
              </td>
              <td>{{ row.review_status === "vetted" ? "已复核" : "待复核" }}</td>
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

    <form v-if="formVisible" class="ph-card ph-ai__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑护栏词：${editing.term}` : "新增护栏词" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">类型</span>
          <select v-model="form.kind" class="ph-select">
            <option value="phrase">越界表述（诊断、剂量、处方）</option>
            <option value="drug">药名</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">词条</span>
          <input v-model="form.term" class="ph-input" maxlength="64" placeholder="要拦下的那个词" />
          <span class="ph-field__hint">同类同词重复会被拒（40900）</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">参与输出过滤</span>
          <select v-model="form.enabled" class="ph-select">
            <option :value="true">参与</option>
            <option :value="false">停用（不再过滤）</option>
          </select>
          <span class="ph-field__hint">停用是**减少防护面**：确认这个词确实不该再拦</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <input v-model="form.note" class="ph-input" maxlength="256" placeholder="为什么加它——给下一个看词表的人" />
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-ai__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-ai__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-ai__hint {
  max-width: 880px;
}

.ph-ai__alert {
  margin-top: var(--ph-space-3);
}

.ph-ai__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-ai__filter .ph-select {
  width: auto;
  min-width: 140px;
}

.ph-ai__form {
  margin-top: var(--ph-space-5);
}

.ph-ai__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
