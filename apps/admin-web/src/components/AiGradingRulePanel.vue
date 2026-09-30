<script setup lang="ts">
/**
 * 分级规则（`/api/v1/admin/ai/grading-rules`）：读列表 / 新增 / 修改。
 *
 * 规则把「命中词 → 风险下限」抬档，**只抬不降**（ADR-0021）：它是给模型的分级兜一条底线，
 * 不会把模型判出的高危降下来。所以这一屏改的是「哪类词至少算几级」，不是最终分级
 * （最终分级还要看模型的判断与红线的短路）。
 *
 * 两条约束决定了表单：`match_terms` 1–20 条、`advice` **不许写药名与剂量**
 * （它可能进用户的照护要点，而输出侧还有护栏词在过滤——两边都不许越界，见 ADR-0028 的医疗口径）。
 * 与红线词的区别：红线命中**不经过模型**，这里只是抬下限。
 */
import { ref } from "vue";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type GradingRuleRequest, type GradingRuleRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const rules = useSection<GradingRuleRow[]>();

const submit = useSubmitAction("分级规则保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这一条 */
const editing = ref<GradingRuleRow | null>(null);
const formVisible = ref(false);
const form = ref({
  code: "",
  name: "",
  terms: "",
  minLevel: "2",
  speciesScope: "all",
  ageStageScope: "all",
  advice: "",
  enabled: true,
  remark: "",
});
const localError = ref("");

async function load(): Promise<void> {
  await rules.load(() => adminApp.listAiGradingRules(), "分级规则加载失败，请稍后重试");
}

function rows(): GradingRuleRow[] {
  return rules.data.value ?? [];
}

function splitTerms(text: string): string[] {
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
    name: "",
    terms: "",
    // 下限**留空**：抬到几级是一次判断，界面不替产品挑
    minLevel: "",
    speciesScope: "all",
    ageStageScope: "all",
    advice: "",
    enabled: true,
    remark: "",
  };
  formVisible.value = true;
}

function openEdit(row: GradingRuleRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    code: row.code,
    name: row.name,
    terms: (row.match_terms ?? []).join("\n"),
    minLevel: String(row.min_level),
    speciesScope: row.species_scope ?? "all",
    ageStageScope: row.age_stage_scope ?? "all",
    advice: row.advice ?? "",
    enabled: row.enabled,
    remark: row.remark ?? "",
  };
  formVisible.value = true;
}

/** 本地先拦一遍，规则与契约的 40001 / 40900 条件逐条对应 */
function validate(): boolean {
  if (form.value.code.trim() === "") {
    localError.value = "规则编号必填：留痕按它引用，所以不许重复";
    return false;
  }
  if (form.value.name.trim() === "") {
    localError.value = "规则名必填：运营靠它读懂这条规则在管什么";
    return false;
  }
  const terms = splitTerms(form.value.terms);
  if (terms.length === 0) {
    localError.value = "命中词至少 1 条，最多 20 条";
    return false;
  }
  if (terms.length > 20) {
    localError.value = `命中词最多 20 条，现在有 ${terms.length} 条`;
    return false;
  }
  if (terms.some((item) => item.length > 32)) {
    localError.value = "单条命中词最长 32 字";
    return false;
  }
  if (!["1", "2", "3"].includes(form.value.minLevel)) {
    localError.value = "请选风险下限（1 绿 / 2 黄 / 3 红）：界面不替你挑抬到几级";
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): GradingRuleRequest {
  return {
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    match_terms: splitTerms(form.value.terms),
    min_level: Number(form.value.minLevel) as 1 | 2 | 3,
    species_scope: form.value.speciesScope as GradingRuleRequest["species_scope"],
    age_stage_scope: form.value.ageStageScope as GradingRuleRequest["age_stage_scope"],
    advice: form.value.advice.trim() || null,
    enabled: form.value.enabled,
    remark: form.value.remark.trim() || null,
  };
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updateAiGradingRule(target.id, body) : adminApp.createAiGradingRule(body)),
    target ? "分级规则已更新（即时生效：最多滞后一个 TTL）" : "分级规则已新增（即时生效：最多滞后一个 TTL）",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

/** 启停走同一个 PUT（契约里没有单独的 status 接口）：按当前值取反，其余字段原样回传 */
async function toggleEnabled(row: GradingRuleRow): Promise<void> {
  const outcome = await submit.run(
    () =>
      adminApp.updateAiGradingRule(row.id, {
        code: row.code,
        name: row.name,
        match_terms: row.match_terms,
        min_level: row.min_level as 1 | 2 | 3,
        species_scope: row.species_scope as GradingRuleRequest["species_scope"],
        age_stage_scope: row.age_stage_scope as GradingRuleRequest["age_stage_scope"],
        advice: row.advice ?? null,
        enabled: !row.enabled,
        remark: row.remark ?? null,
      }),
    row.enabled ? `已停用 ${row.code}：抬档不再生效（这条规则退出判定）` : `已启用 ${row.code}`,
  );
  if (!outcome.ok) return;
  await load();
}

function levelText(value: number): string {
  if (value === 3) return "3 红";
  if (value === 2) return "2 黄";
  return "1 绿";
}

function scopeText(species?: string, ageStage?: string): string {
  const speciesLabel = species === "dog" ? "犬" : species === "cat" ? "猫" : "全物种";
  const ageLabel =
    ageStage === "puppy_kitten" ? "幼年" : ageStage === "adult" ? "成年" : ageStage === "senior" ? "老年" : "全年龄";
  return `${speciesLabel} · ${ageLabel}`;
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">分级规则把命中的词抬到一条**下限**上——只抬不降</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="rules.loading.value" @click="load">刷新</button>
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新增分级规则</button>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      命中这些词 → 风险**至少**到这条下限（**只抬不降**，ADR-0021）。它与红线词的分工：
      红线命中即短路、不经过模型；这里只是给模型的分级兜一条底线。改动即时生效（最多滞后一个 TTL）。
      建议文案（`advice`）**不许写药名与剂量**：它可能进用户的照护要点，输出侧另有护栏词在过滤。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="rules.loading.value"
      :forbidden="rules.forbidden.value"
      :error-message="rules.errorMessage.value"
      :request-id="rules.requestId.value"
      :is-empty="rules.loaded.value && rows().length === 0"
      loading-title="正在加载分级规则"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取分级规则。"
      empty-title="还没有分级规则"
      empty-description="没有规则时分级完全交给模型；这一页不预置任何规则，词与下限按复核过的口径逐条填。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编号</th>
              <th>名称</th>
              <th>命中词</th>
              <th>风险下限</th>
              <th>适用</th>
              <th>建议（不写药名与剂量）</th>
              <th>启用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td>{{ (row.match_terms ?? []).join(" / ") }}</td>
              <td>
                <span class="ph-tag" :class="row.min_level === 3 ? 'ph-tag--danger' : row.min_level === 2 ? 'ph-tag--warning' : 'ph-tag--info'">
                  {{ levelText(row.min_level) }}
                </span>
              </td>
              <td>{{ scopeText(row.species_scope, row.age_stage_scope) }}</td>
              <td>{{ row.advice ?? "—" }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled ? "启用" : "停用" }}
                </span>
              </td>
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
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-ai__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑分级规则：${editing.code}` : "新增分级规则" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">规则编号</span>
          <input v-model="form.code" class="ph-input" maxlength="32" placeholder="如 GR-003" />
          <span class="ph-field__hint">留痕引用它，不许重复（重复即 40900）</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">规则名</span>
          <input v-model="form.name" class="ph-input" maxlength="64" placeholder="这条规则在管什么" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">风险下限</span>
          <select v-model="form.minLevel" class="ph-select">
            <option value="">请选择</option>
            <option value="3">3 红（建议立即就医）</option>
            <option value="2">2 黄（建议尽快就医）</option>
            <option value="1">1 绿（一般观察）</option>
          </select>
          <span class="ph-field__hint">只抬不降：模型判得更高时以模型为准</span>
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
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">命中词（一行一个或逗号分隔，1–20 条）</span>
          <textarea v-model="form.terms" class="ph-textarea" rows="3" placeholder="一行一个词" />
          <span class="ph-field__hint">单条最长 32 字。</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">建议（可选）</span>
          <input v-model="form.advice" class="ph-input" maxlength="256" placeholder="给用户的下一步建议" />
          <span class="ph-field__hint">**不许写药名与剂量**：这段文字可能进用户的照护要点</span>
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">备注（可选）</span>
          <input v-model="form.remark" class="ph-input" maxlength="256" />
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

.ph-ai__form {
  margin-top: var(--ph-space-5);
}

.ph-ai__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
