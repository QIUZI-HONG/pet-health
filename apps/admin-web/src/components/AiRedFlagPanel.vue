<script setup lang="ts">
/**
 * 硬红线词（`/api/v1/admin/ai/red-flags`）：分页列表 / 新增 / 修改 / 停用。
 *
 * 这是**医疗安全网**，不是社区敏感词（CONTEXT.md 专门划了这条线）：命中红线的咨询**不经过模型**
 * 直接短路（ADR-0021），所以它的每一条改动都直接改变线上对用户的答复。三条口径：
 * - **规则编号不许重复**（40900）：留痕引用编号，重复会让两次命中分不开；
 * - **`enabled` 与 `review_status` 是两件事**：内容有没有被兽医复核 vs 管线要不要用它。
 *   词表首版全部 `pending_review` 但 `enabled`（ADR-0021）；`review_status` 接口不给改（ADR-0040 第二节）；
 * - **停用是软删**（`DELETE` 只置停用）：留痕里引用过它的咨询仍要能查到当时的规则，所以没有硬删除，
 *   停用要给一次二次确认——它是安全网，少一条就多一类漏进去的咨询。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { adminApp, type RedFlagRequest, type RedFlagRow } from "../api/adminApi";

const enabledFilter = ref("");
const levelFilter = ref("");

const flags = usePagedList<RedFlagRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listAiRedFlags(
      {
        // 默认连停用的一起给：运营要能看见自己停用过的规则（停用不删）
        enabled: enabledFilter.value === "" ? undefined : enabledFilter.value === "1",
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "红线词加载失败，请稍后重试" },
);

const submit = useSubmitAction("红线保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这一条 */
const editing = ref<RedFlagRow | null>(null);
const formVisible = ref(false);
const form = ref({
  code: "",
  pattern: "",
  variants: "",
  speciesScope: "all",
  ageStageScope: "all",
  level: "3",
  actionHint: "",
  enabled: true,
  remark: "",
});
const localError = ref("");
/** 正在等二次确认的那一条（停用是安全网上的动作，手滑的代价是漏检） */
const confirmingDisable = ref<RedFlagRow | null>(null);

function reload(): void {
  void flags.reload();
}

/** 列表按等级再筛一道：等级是「命中后至少到几级」，与后端的过滤参数无关，所以在前端做 */
function visibleRows(): RedFlagRow[] {
  if (levelFilter.value === "") return flags.items.value;
  return flags.items.value.filter((row) => String(row.level) === levelFilter.value);
}

function splitVariants(text: string): string[] {
  return text
    .split(/[\n,，;；]+/)
    .map((item) => item.trim())
    .filter((item) => item !== "");
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = {
    code: "",
    pattern: "",
    variants: "",
    speciesScope: "all",
    ageStageScope: "all",
    // 等级**留空**：2 还是 3 是一次判断，界面不替产品挑（宁严勿松不等于默认给你选 3）
    level: "",
    actionHint: "",
    enabled: true,
    remark: "",
  };
  formVisible.value = true;
}

function openEdit(row: RedFlagRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    code: row.code,
    pattern: row.pattern,
    variants: (row.variants ?? []).join("\n"),
    speciesScope: row.species_scope ?? "all",
    ageStageScope: row.age_stage_scope ?? "all",
    level: String(row.level),
    actionHint: row.action_hint,
    enabled: row.enabled,
    remark: row.remark ?? "",
  };
  formVisible.value = true;
}

/** 本地先拦一遍，规则与契约的 40001 / 40900 条件逐条对应 */
function validate(): boolean {
  if (form.value.code.trim() === "") {
    localError.value = "规则编号必填（如 RF-007）：留痕按它引用，所以不许重复";
    return false;
  }
  if (form.value.pattern.trim() === "") {
    localError.value = "主词不能为空：命中判定看的就是它";
    return false;
  }
  const variants = splitVariants(form.value.variants);
  if (variants.length > 20) {
    localError.value = `变体最多 20 条，现在有 ${variants.length} 条`;
    return false;
  }
  if (variants.some((item) => item.length > 64)) {
    localError.value = "单条变体最长 64 字";
    return false;
  }
  if (form.value.actionHint.trim() === "") {
    localError.value = "命中后的第一句话必填——它是用户看到的动作（必须建议就医）";
    return false;
  }
  if (!["2", "3"].includes(form.value.level)) {
    localError.value = "请选风险等级：红线的下限是 2（2 黄 / 3 红），界面不替你挑";
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): RedFlagRequest {
  return {
    code: form.value.code.trim(),
    pattern: form.value.pattern.trim(),
    variants: splitVariants(form.value.variants),
    species_scope: form.value.speciesScope as RedFlagRequest["species_scope"],
    age_stage_scope: form.value.ageStageScope as RedFlagRequest["age_stage_scope"],
    level: Number(form.value.level) as 2 | 3,
    action_hint: form.value.actionHint.trim(),
    enabled: form.value.enabled,
    remark: form.value.remark.trim() || null,
  };
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateAiRedFlag(target.id, body) : adminApp.createAiRedFlag(body)),
    target ? "红线已更新（即时生效：最多滞后一个 TTL）" : "红线已新增（即时生效：最多滞后一个 TTL）",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  confirmingDisable.value = null;
  reload();
}

async function disable(row: RedFlagRow): Promise<void> {
  const outcome = await submit.run(
    () => adminApp.disableAiRedFlag(row.id),
    `已停用 ${row.code}：不再参与短路判定，规则留在库里（留痕要能查当时命中了什么）`,
  );
  if (!outcome.ok) return;
  confirmingDisable.value = null;
  reload();
}

function levelText(value: number): string {
  return value === 3 ? "3 红（建议立即就医）" : "2 黄（建议尽快就医）";
}

function scopeText(species?: string, ageStage?: string): string {
  const speciesLabel = species === "dog" ? "犬" : species === "cat" ? "猫" : "全物种";
  const ageLabel =
    ageStage === "puppy_kitten" ? "幼年" : ageStage === "adult" ? "成年" : ageStage === "senior" ? "老年" : "全年龄";
  return `${speciesLabel} · ${ageLabel}`;
}

void flags.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-ai__filter">
        <span class="ph-field__label">启用状态</span>
        <select v-model="enabledFilter" class="ph-select" @change="reload">
          <option value="">全部</option>
          <option value="1">启用中</option>
          <option value="0">已停用</option>
        </select>
      </label>
      <label class="ph-field ph-ai__filter">
        <span class="ph-field__label">等级</span>
        <select v-model="levelFilter" class="ph-select">
          <option value="">全部</option>
          <option value="3">3 红</option>
          <option value="2">2 黄</option>
        </select>
      </label>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="flags.loading.value" @click="reload">刷新</button>
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新增红线</button>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      命中红线的咨询**不经过模型**（ADR-0021）：这是一条安全网，改动即时生效。
      停用是**软删**——留痕里引用过它的咨询仍要能查到当时的规则，所以只停用、不删除。
      这套词表与「内容审核」的敏感词是两回事：那一套管社区内容要不要拦，这一套管医疗风险要不要短路。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="flags.loading.value"
      :forbidden="flags.forbidden.value"
      :error-message="flags.errorMessage.value"
      :request-id="flags.requestId.value"
      :is-empty="flags.isEmpty.value"
      loading-title="正在加载红线词"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取红线词表。"
      empty-title="红线词表是空的"
      empty-description="没有规则就没有任何咨询会被短路：医疗安全网此时只剩模型的判断。词条要按兽医复核过的判据逐条填，这一页不预置任何词。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编号</th>
              <th>主词 / 变体</th>
              <th>等级</th>
              <th>适用</th>
              <th>命中后的第一句话</th>
              <th>启用</th>
              <th>复核</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in visibleRows()" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>
                {{ row.pattern }}
                <span v-if="(row.variants ?? []).length > 0" class="ph-text-weak">
                  （+{{ (row.variants ?? []).length }} 个变体）
                </span>
              </td>
              <td>
                <span class="ph-tag" :class="row.level === 3 ? 'ph-tag--danger' : 'ph-tag--warning'">{{ levelText(row.level) }}</span>
              </td>
              <td>{{ scopeText(row.species_scope, row.age_stage_scope) }}</td>
              <td>{{ row.action_hint }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled ? "启用" : "停用" }}
                </span>
              </td>
              <td>{{ row.review_status === "vetted" ? "已复核" : "待复核" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">编辑</button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="!row.enabled || submit.submitting.value"
                    @click="confirmingDisable = row"
                  >
                    停用
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 停用要二次确认：它是安全网上的口子，把代价讲清楚再让他点 -->
      <div v-if="confirmingDisable" class="ph-alert ph-alert--warn ph-ai__alert">
        <p>
          确认停用 <strong>{{ confirmingDisable.code }}（{{ confirmingDisable.pattern }}）</strong>？
          停用后这条规则**不再参与短路判定**：命中原先由它兜住的咨询会回到模型通道，
          而模型对这类信号的处理不如硬规则确定。
        </p>
        <p class="ph-field__hint">规则不会消失（软删），留痕里引用过它的咨询仍查得到当时的规则；随时可以再启用。</p>
        <div class="ph-ai__actions">
          <button type="button" class="ph-button ph-button--primary" :disabled="submit.submitting.value" @click="disable(confirmingDisable)">
            {{ submit.submitting.value ? "提交中…" : "确认停用" }}
          </button>
          <button type="button" class="ph-button ph-button--secondary" @click="confirmingDisable = null">取消</button>
        </div>
      </div>

      <div class="ph-pager">
        <span>共 {{ flags.total.value }} 条</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="flags.page.value <= 1 || flags.loading.value"
          @click="flags.prevPage"
        >
          上一页
        </button>
        <span>第 {{ flags.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!flags.hasMore.value || flags.loading.value"
          @click="flags.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-ai__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑红线：${editing.code}` : "新增红线" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">规则编号</span>
          <input v-model="form.code" class="ph-input" maxlength="32" placeholder="RF-007" />
          <span class="ph-field__hint">留痕引用它，**不许重复**（重复即 40900）</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">主词</span>
          <input v-model="form.pattern" class="ph-input" maxlength="64" placeholder="兽医复核过的症状主词" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">风险等级</span>
          <select v-model="form.level" class="ph-select">
            <option value="">请选择</option>
            <option value="3">3 红（建议立即就医）</option>
            <option value="2">2 黄（建议尽快就医）</option>
          </select>
          <span class="ph-field__hint">红线的下限是 2：命中即短路，等级只决定答复的紧迫措辞</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">适用物种</span>
          <select v-model="form.speciesScope" class="ph-select">
            <option value="all">全物种</option>
            <option value="dog">犬</option>
            <option value="cat">猫</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">适用年龄段</span>
          <select v-model="form.ageStageScope" class="ph-select">
            <option value="all">全年龄</option>
            <option value="puppy_kitten">幼年</option>
            <option value="adult">成年</option>
            <option value="senior">老年</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">启用</span>
          <select v-model="form.enabled" class="ph-select">
            <option :value="true">启用（参与短路）</option>
            <option :value="false">停用</option>
          </select>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">变体（同义说法，一行一个或逗号分隔，最多 20 条）</span>
          <textarea v-model="form.variants" class="ph-textarea" rows="3" placeholder="同义说法，一行一个" />
          <span class="ph-field__hint">单条最长 64 字；留空表示只有主词。</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">命中后的第一句话</span>
          <input v-model="form.actionHint" class="ph-input" maxlength="256" placeholder="命中后要用户做什么（必须建议就医）" />
          <span class="ph-field__hint">**这句会直接给用户看**，按「宁严勿松」写：必须建议就医，不许给诊断或用药建议</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">备注（可选）</span>
          <input v-model="form.remark" class="ph-input" maxlength="256" placeholder="依据是什么（兽医复核意见、文献）——给下一个看词表的人" />
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
  min-width: 120px;
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
