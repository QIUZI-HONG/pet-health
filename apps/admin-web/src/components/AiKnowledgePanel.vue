<script setup lang="ts">
/**
 * 知识条目复核（`/api/v1/admin/ai/knowledge-entries`）：交付文档 F024/F025 的内容面。
 *
 * 这一屏存在的理由是**引用为空**：只有 `vetted` 条目能进 AI 回答的 `citations`，
 * 而种子里 40 条全是 `pending_review`——在此之前「复核通过」只能手改数据库。
 *
 * 四条口径：
 * - **两级状态，不扩第三态**：「打回」就是置回未复核（`pending_review`），不是新状态；
 * - **通过必须填复核人与资质**：`vetted` 是一句专业背书（ADR-0054），不是一次开关操作；
 * - **没有批量复核**：一次一条。「批量通过」等于把背书变成点按钮；
 * - **正文不在这一屏**（列表只给标题 / 摘要 / 来源 / 状态）：检索素材不进运营列表。
 */
import { ref } from "vue";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type KnowledgeEntryRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const entries = useSection<KnowledgeEntryRow[]>();
const total = ref(0);

/** 状态筛选：空串 = 全部；默认只看未复核（运营打开就想看到还差哪些） */
const filter = ref("pending_review");
/** 关键词（标题 / 摘要）：只在点「查询」时提交，避免每敲一个字打一次接口 */
const keywordInput = ref("");
const keyword = ref("");

/** 复核动作的闸门（提交中禁用 + 失败文案 + 2 秒节流） */
const submit = useSubmitAction("复核失败，请稍后重试");
/** 正在复核的那一条（通过 / 打回共同一个确认区） */
const pending = ref<{ row: KnowledgeEntryRow; action: "vet" | "reject" } | null>(null);
const reviewer = ref("");
const credential = ref("");

async function load(): Promise<void> {
  await entries.load(async () => {
    const page = await adminApp.listAiKnowledgeEntries({
      reviewStatus: filter.value === "" ? undefined : filter.value,
      keyword: keyword.value === "" ? undefined : keyword.value,
      pageSize: 50,
    });
    total.value = page.total ?? 0;
    return page.list;
  }, "知识条目加载失败，请稍后重试");
}

function rows(): KnowledgeEntryRow[] {
  return entries.data.value ?? [];
}

function ask(row: KnowledgeEntryRow, action: "vet" | "reject"): void {
  submit.clear();
  pending.value = { row, action };
}

async function confirmReview(): Promise<void> {
  const target = pending.value;
  if (!target) return;
  const outcome = await submit.run(
    () => adminApp.reviewAiKnowledgeEntry(target.row.code, {
      action: target.action,
      reviewer: reviewer.value.trim(),
      credential: credential.value.trim(),
    }),
    target.action === "vet"
      ? `${target.row.code} 已复核通过：它会（也只有它会）出现在 AI 回答的引用里`
      : `${target.row.code} 已打回未复核：引用里不会再出现它`,
  );
  if (!outcome.ok) return;
  pending.value = null;
  credential.value = "";
  await load();
}

function statusText(row: KnowledgeEntryRow): string {
  return row.review_status === "vetted" ? "已复核" : "未复核";
}

/** 复核人表单填全了才让提交（后端也会拦，这里只是别让人白点） */
function canSubmit(): boolean {
  if (!pending.value) return false;
  return reviewer.value.trim() !== "" && credential.value.trim() !== "";
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">
        **只有复核通过的条目**能进 AI 回答的引用（`citations`）——引用为空时，答案本身也就没有依据
      </span>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      `review_status` 只有两级：未复核 / 已复核（ADR-0054）。**打回就是置回未复核**，不新增状态。
      通过时必须填「复核人 + 资质」——这是一句专业背书，要落到数据里，谁签的字出了内容问题追得到人。
      这一屏**没有批量复核**：一次一条，批量通过等于把背书降格成点按钮。
    </p>

    <div class="ph-toolbar">
      <label class="ph-field__label" for="kb-status">复核状态</label>
      <select id="kb-status" v-model="filter" class="ph-input" @change="load">
        <option value="pending_review">未复核</option>
        <option value="vetted">已复核</option>
        <option value="">全部</option>
      </select>
      <label class="ph-field__label" for="kb-keyword">关键词</label>
      <input
        id="kb-keyword"
        v-model="keywordInput"
        class="ph-input"
        type="search"
        placeholder="标题或摘要里的词"
        @keyup.enter="keyword = keywordInput.trim(); load()"
      />
      <button type="button" class="ph-button ph-button--secondary" @click="keyword = keywordInput.trim(); load()">
        查询
      </button>
      <span class="ph-toolbar__spacer" />
      <span class="ph-text-weak">共 {{ total }} 条</span>
      <button type="button" class="ph-button ph-button--secondary" :disabled="entries.loading.value" @click="load">
        刷新
      </button>
    </div>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="entries.loading.value"
      :forbidden="entries.forbidden.value"
      :error-message="entries.errorMessage.value"
      :request-id="entries.requestId.value"
      :is-empty="entries.loaded.value && rows().length === 0"
      loading-title="正在加载知识条目"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取知识条目。"
      empty-title="这个筛选下没有条目"
      empty-description="换个状态或清掉关键词再看看。种子里的 40 条默认都是「未复核」——它们要经过兽医复核才会进 AI 引用。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编号</th>
              <th>标题</th>
              <th>类目</th>
              <th>来源</th>
              <th>状态</th>
              <th>复核人 / 资质</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>
                <div>{{ row.title }}</div>
                <div class="ph-text-weak">{{ row.summary }}</div>
              </td>
              <td>{{ row.category_code }}</td>
              <td>{{ row.source_title }}</td>
              <td>
                <span class="ph-tag" :class="row.review_status === 'vetted' ? 'ph-tag--success' : 'ph-tag--warn'">
                  {{ statusText(row) }}
                </span>
              </td>
              <td>
                <template v-if="row.reviewed_by">
                  {{ row.reviewed_by }}
                  <div class="ph-text-weak">{{ row.reviewed_credential }}</div>
                </template>
                <span v-else class="ph-text-weak">—</span>
              </td>
              <td>
                <button
                  v-if="row.review_status !== 'vetted'"
                  type="button"
                  class="ph-button ph-button--secondary"
                  @click="ask(row, 'vet')"
                >
                  复核通过
                </button>
                <button
                  v-else
                  type="button"
                  class="ph-button ph-button--secondary"
                  @click="ask(row, 'reject')"
                >
                  打回
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <div v-if="pending" class="ph-card ph-ai__confirm">
      <h3 class="ph-card__title">
        {{ pending.action === "vet" ? "复核通过" : "打回未复核" }}：{{ pending.row.code }} {{ pending.row.title }}
      </h3>
      <p class="ph-text-sub">
        {{
          pending.action === "vet"
            ? "通过之后，这条内容可以出现在 AI 回答的引用里（在此之前它一条都不会出现）。"
            : "打回之后，这条内容回到「未复核」，引用里不会再出现它。"
        }}
      </p>
      <label class="ph-field__label" for="kb-reviewer">复核人</label>
      <input id="kb-reviewer" v-model="reviewer" class="ph-input" placeholder="如：李兽医" />
      <label class="ph-field__label" for="kb-credential">复核资质</label>
      <input id="kb-credential" v-model="credential" class="ph-input" placeholder="如：执业兽医师，证号 A1234" />
      <div class="ph-toolbar">
        <button
          type="button"
          class="ph-button ph-button--primary"
          :disabled="!canSubmit() || submit.submitting.value"
          @click="confirmReview"
        >
          确认{{ pending.action === "vet" ? "通过" : "打回" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="pending = null">取消</button>
      </div>
      <p class="ph-field__hint">
        复核人与资质**必填**：`vetted` 是一句专业背书（ADR-0054），不是一次开关操作——
        所以它要落到 `reviewed_by` / `reviewed_credential` / `reviewed_at` 三列里。
      </p>
    </div>
  </div>
</template>
