<script setup lang="ts">
/**
 * 敏感词管理（机审词表：`/api/v1/admin/community/sensitive-words`）。
 *
 * **词表在库里维护**（ADR-0010 的业务可调项分层）：改完即时生效、不用重启，所以它是一个可写的
 * 列表页，不是一个只读的配置快照。契约里的三条口径决定了这里的动作：
 * - **命中判定是包含匹配**（不做正则、不做分词）：运营要能肉眼预测「这句话为什么被拦」，
 *   所以表单里没有「正则表达式」这种输入，词就是词；
 * - **没有删除动作**：误伤的词停用（`enabled=false`）即可——留着它才能解释「昨天为什么拦了那条内容」，
 *   所以「停用 / 启用」是常规操作，按钮不藏在「编辑」里；
 * - **同一句词重复添加是 40900**（唯一键也是这个口径），所以本地先拦一道空值，其余交给后端。
 *
 * **别和 AI 的硬红线词混**（CONTEXT.md 专门划了这条线）：那一套（`/ai/red-flags`）用于医疗风险
 * 分级、命中即短路不经过模型；这一套只管社区内容要不要拦。两套词表不通。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { adminApp, type SensitiveWordRequest, type WordRow } from "../api/adminApi";

const enabledFilter = ref("");
const keyword = ref("");

const words = usePagedList<WordRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listSensitiveWords(
      {
        // 默认连停用的一起给：运营要能看见自己停用过的词（停用不删）
        enabled: enabledFilter.value === "" ? undefined : enabledFilter.value === "1",
        keyword: keyword.value.trim() || undefined,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "敏感词加载失败，请稍后重试" },
);

const submit = useSubmitAction("保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这个词条 */
const editing = ref<WordRow | null>(null);
const formVisible = ref(false);
const form = ref({ word: "", category: "", remark: "", enabled: true });
const localError = ref("");

function reload(): void {
  void words.reload();
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = { word: "", category: "", remark: "", enabled: true };
  formVisible.value = true;
}

function openEdit(row: WordRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    word: row.word,
    category: row.category ?? "",
    remark: row.remark ?? "",
    enabled: row.enabled,
  };
  formVisible.value = true;
}

function buildBody(): SensitiveWordRequest {
  return {
    word: form.value.word.trim(),
    category: form.value.category.trim() || null,
    remark: form.value.remark.trim() || null,
    enabled: form.value.enabled,
  };
}

async function save(): Promise<void> {
  if (form.value.word.trim() === "") {
    localError.value = "词不能为空：机审是按包含匹配找它的";
    return;
  }
  localError.value = "";
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateSensitiveWord(target.id, body) : adminApp.createSensitiveWord(body)),
    target ? "词条已更新（即时生效）" : "词条已新增（即时生效）",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  reload();
}

/** 停用 / 启用：词表没有删除动作，停用就是它的「软删除」口径 */
async function toggleEnabled(row: WordRow): Promise<void> {
  const outcome = await submit.run(
    () =>
      adminApp.updateSensitiveWord(row.id, {
        word: row.word,
        category: row.category ?? null,
        remark: row.remark ?? null,
        enabled: !row.enabled,
      }),
    row.enabled ? "已停用：不再参与机审，但词条留着（能解释昨天为什么拦了那条内容）" : "已启用",
  );
  if (!outcome.ok) return;
  reload();
}

void words.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-word__filter">
        <span class="ph-field__label">参与机审</span>
        <select v-model="enabledFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">启用中</option>
          <option value="0">已停用</option>
        </select>
      </label>
      <input v-model="keyword" class="ph-input ph-word__search" maxlength="64" placeholder="按词模糊匹配" @keyup.enter="reload" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="words.loading.value" @click="reload">搜索</button>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新增敏感词</button>
    </div>

    <p class="ph-field__hint ph-word__hint">
      命中判定是**包含匹配**（不做正则、不做分词），所以词就是词；同一句词重复添加会被拒（40900）。
      词表没有删除动作：误伤的词停用即可，留着它才能解释「昨天为什么拦了那条内容」。
      这一套只管社区内容要不要拦——AI 的**硬红线词**（`/ai/red-flags`）是另一套，用于医疗风险分级。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-word__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-word__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="words.loading.value"
      :forbidden="words.forbidden.value"
      :error-message="words.errorMessage.value"
      :request-id="words.requestId.value"
      :is-empty="words.isEmpty.value"
      loading-title="正在加载敏感词表"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取敏感词表。"
      empty-title="词表是空的"
      empty-description="没有词就意味着机审放行一切；先加几条最常见的引流、联系方式类词。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>词</th>
              <th>分类</th>
              <th>参与机审</th>
              <th>说明</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in words.items.value" :key="row.id">
              <td>{{ row.word }}</td>
              <td>{{ row.category ?? "—" }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled ? "启用" : "停用" }}
                </span>
              </td>
              <td>{{ row.remark ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">编辑</button>
                  <button type="button" class="ph-table__action" :disabled="submit.submitting.value" @click="toggleEnabled(row)">
                    {{ row.enabled ? "停用" : "启用" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ words.total.value }} 条</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="words.page.value <= 1 || words.loading.value"
          @click="words.prevPage"
        >
          上一页
        </button>
        <span>第 {{ words.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!words.hasMore.value || words.loading.value"
          @click="words.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-word__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑词条：${editing.word}` : "新增敏感词" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">词</span>
          <input v-model="form.word" class="ph-input" maxlength="64" placeholder="如：加微信" />
          <span class="ph-field__hint">按包含匹配，大小写不敏感；最长 64 字</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">分类（可选，不参与判定）</span>
          <input v-model="form.category" class="ph-input" maxlength="32" placeholder="如：引流" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">说明（可选）</span>
          <input v-model="form.remark" class="ph-input" maxlength="255" placeholder="为什么拦这个词——给下一个看词表的人" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">参与机审</span>
          <select v-model="form.enabled" class="ph-select">
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select>
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-word__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-word__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-word__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-word__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-word__filter .ph-select {
  width: auto;
  min-width: 130px;
}

.ph-word__search {
  width: 180px;
}

.ph-word__hint {
  margin: 0 0 var(--ph-space-4);
  max-width: 880px;
}

.ph-word__alert {
  margin-top: var(--ph-space-4);
}

.ph-word__form {
  margin-top: var(--ph-space-5);
}

.ph-word__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
