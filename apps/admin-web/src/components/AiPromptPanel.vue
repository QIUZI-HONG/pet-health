<script setup lang="ts">
/**
 * 提示词版本（`/api/v1/admin/ai/prompts`）：读版本列表 / 新建版本 / 调灰度与启停。
 *
 * 三条口径来自 ADR-0010，它们决定了这一屏长什么样：
 * - **正文改动必须换版本号**（同 (code, version) 已存在即 40900）：留痕按 `prompt_version` 归因
 *   分级漂移，同号覆盖会让归因失去对照物。所以「新建」是唯一能改正文的动作，而新建表单里的
 *   版本号**留空**（不预填、不自动 +1）：填什么版本号是一次显式决定，替运营猜会造出一个
 *   没人认得的号。正文与工具定义默认带出当前版本的那一份（沿用服务端值），改完仍是新版本。
 * - **`gray_ratio` 就是灰度开关**：出问题把它改回 0 即回滚，**不必发版**——所以行内有一个
 *   「灰度归零」动作，而不是把回滚藏进编辑表单里让人现填 0。
 * - **`tool_schema` 是唯一的只读下发项**：工具定义归代码常量那一层（ADR-0010），改它要发版；
 *   这里下发是为了让「当前生效的正文 + 它的工具定义」能一起被看见。新建时照抄一份即可。
 *
 * `review_status` 只读（ADR-0040 第二节）：复核是人工流程，接口给它开个后门等于假造复核状态。
 * 新建的版本一律 `pending_review` + 启用，**在人复核之前它就可能被灰度命中**——页面把这句说出来。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type PromptRow, type PromptTemplateRequest } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const prompts = useSection<PromptRow[]>();

const submit = useSubmitAction("提示词保存失败，请稍后重试");
/** null = 新建版本；有值 = 正在调这一版的灰度与启停 */
const editing = ref<PromptRow | null>(null);
const formVisible = ref(false);
const form = ref({ code: "", version: "", systemPrompt: "", toolSchema: "", grayRatio: "0", remark: "" });
const localError = ref("");

async function load(): Promise<void> {
  await prompts.load(() => adminApp.listAiPrompts(), "提示词版本加载失败，请稍后重试");
}

function rows(): PromptRow[] {
  return prompts.data.value ?? [];
}

/** 当前启用中的版本：工具条上要念得出「现在跑的是哪一版」 */
function activeText(): string {
  const enabled = rows().filter((item) => item.enabled);
  if (enabled.length === 0) return "无（管线回落到代码里的基线提示词）";
  return enabled.map((item) => `${item.code} ${item.version}（灰度 ${item.gray_ratio ?? 0}%）`).join(" · ");
}

function openCreate(): void {
  // 沿用服务端值：新版本通常是「在现版本上改一句」，空白起步更容易造出一份缺内容的正文
  const latest = rows()[0];
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = {
    code: latest?.code ?? "triage",
    version: "",
    systemPrompt: latest?.system_prompt ?? "",
    toolSchema: latest?.tool_schema ?? "",
    grayRatio: "0",
    remark: "",
  };
  formVisible.value = true;
}

function openEdit(row: PromptRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    code: row.code,
    version: row.version,
    systemPrompt: row.system_prompt,
    toolSchema: row.tool_schema ?? "",
    grayRatio: String(row.gray_ratio ?? 0),
    remark: "",
  };
  formVisible.value = true;
}

function validateGrayRatio(): boolean {
  const ratio = form.value.grayRatio.trim();
  if (!/^\d+$/.test(ratio) || Number(ratio) > 100) {
    localError.value = "灰度比例要 0–100 的整数；新版本先设 0 再逐步放量";
    return false;
  }
  localError.value = "";
  return true;
}

/** 本地先拦一遍，规则与契约的 40001 / 40900 条件逐条对应 */
function validateForCreate(): boolean {
  const code = form.value.code.trim();
  const version = form.value.version.trim();
  if (code === "") {
    localError.value = "用途代码必填（当前只有 triage 一处）";
    return false;
  }
  if (version === "") {
    localError.value = "版本号必填，而且**必须是新号**：同 (用途, 版本) 已存在会被拒（40900）";
    return false;
  }
  if (rows().some((item) => item.code === code && item.version === version)) {
    localError.value = `已存在 ${code} ${version}：版本号只能新增，不能原地改（留痕按版本号归因分级漂移）`;
    return false;
  }
  if (form.value.systemPrompt.trim() === "") {
    localError.value = "提示词正文不能为空";
    return false;
  }
  if (form.value.toolSchema.trim() === "") {
    localError.value = "工具定义要随正文一起给（缺了模型不会按 report_triage 的格式上报），照抄当前版本那一份即可";
    return false;
  }
  return validateGrayRatio();
}

async function createVersion(): Promise<void> {
  if (!validateForCreate()) return;
  const body: PromptTemplateRequest = {
    code: form.value.code.trim(),
    version: form.value.version.trim(),
    system_prompt: form.value.systemPrompt,
    tool_schema: form.value.toolSchema,
    gray_ratio: Number(form.value.grayRatio),
    remark: form.value.remark.trim() || null,
  };
  const outcome = await submit.run(
    () => adminApp.createAiPrompt(body),
    `新版本已入库（待复核 + 启用）：灰度 ${body.gray_ratio}%，放量前先确认复核状态`,
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

/** 调灰度 / 启停：正文不在这里改（契约的 PromptTemplateUpdateRequest 里根本没有 system_prompt） */
async function saveShade(): Promise<void> {
  const target = editing.value;
  if (!target) return;
  if (!validateGrayRatio()) return;
  const ratio = Number(form.value.grayRatio);
  const outcome = await submit.run(
    () =>
      adminApp.updateAiPrompt(target.id, {
        gray_ratio: ratio,
        enabled: ratio !== 0,
        remark: form.value.remark.trim() || undefined,
      }),
    ratio === 0
      ? "已改：灰度 0 表示这一版不再被命中（这就是回滚）"
      : `已改：${target.code} ${target.version} 灰度 ${ratio}%`,
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

/** 一键回滚：把灰度改回 0（ADR-0010 的「不必发版」那条） */
async function rollback(row: PromptRow): Promise<void> {
  const outcome = await submit.run(
    () => adminApp.updateAiPrompt(row.id, { gray_ratio: 0, enabled: row.enabled, remark: "灰度归零（回滚）" }),
    `已回滚：${row.code} ${row.version} 灰度归零，管线回落其余版本 / 代码基线`,
  );
  if (!outcome.ok) return;
  await load();
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">当前启用中的版本：{{ activeText() }}</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="prompts.loading.value" @click="load">刷新</button>
      <button type="button" class="ph-button ph-button--primary" @click="openCreate">新建版本</button>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      **改正文必须换版本号，不要原地改**：留痕按 `prompt_version` 归因分级漂移（ADR-0010），
      同号覆盖会让归因失去对照物。灰度就是开关——出问题把比例改回 0 即是回滚，不必发版。
      这里的改动**即时影响线上**：AI 服务带 TTL 缓存直读这些表，最多滞后一个 TTL（默认 60 秒）。
      人工复核状态（`review_status`）**接口不给改**（ADR-0040 第二节）：复核是人工流程，
      给它开个后门等于假造复核状态——所以新建的版本会先以「待复核」的身份参与灰度。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.forbidden.value" class="ph-alert ph-alert--error ph-ai__alert">
      服务端按「没有权限」拒绝了这次修改（40100 / 40300）——运营后台的写入口只对 admin 域的账号开放。
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="prompts.loading.value"
      :forbidden="prompts.forbidden.value"
      :error-message="prompts.errorMessage.value"
      :request-id="prompts.requestId.value"
      :is-empty="prompts.loaded.value && rows().length === 0"
      loading-title="正在加载提示词版本"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取提示词版本。"
      empty-title="还没有入库的提示词版本"
      empty-description="此时管线用代码里的基线提示词。要改就新建一个版本（正文 + 与它同源的工具定义），先把灰度设成 0 再放量。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>用途</th>
              <th>版本</th>
              <th>灰度比例</th>
              <th>启用</th>
              <th>复核状态</th>
              <th>最后改动人</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td class="ph-table__num">{{ row.version }}</td>
              <td class="ph-table__num">{{ row.gray_ratio ?? 0 }}%</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.enabled ? "启用" : "停用" }}
                </span>
              </td>
              <td>{{ row.review_status === "vetted" ? "已复核" : "待复核" }}</td>
              <td class="ph-table__num">{{ row.updated_by ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">调灰度 / 启停</button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="submit.submitting.value || (row.gray_ratio ?? 0) === 0"
                    @click="rollback(row)"
                  >
                    灰度归零
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="ph-field__hint ph-ai__hint">
        「调灰度 / 启停」里也带着正文与工具定义（只读展示）：正文不进表格是因为它太长，
        而这一屏要看的是「跑的是哪一版、放了多少量」。「灰度归零」是回滚的快捷动作，只动比例。
      </p>
    </ConsoleListState>

    <form v-if="formVisible" class="ph-card ph-ai__form" @submit.prevent="editing ? saveShade() : createVersion()">
      <h4 class="ph-card__title">
        {{ editing ? `调灰度 / 启停：${editing.code} ${editing.version}` : "新建提示词版本" }}
      </h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">用途代码</span>
          <input v-model="form.code" class="ph-input" maxlength="32" :disabled="editing !== null" placeholder="triage" />
          <span class="ph-field__hint">{{ editing ? "已有版本不可换用途" : "当前只有 triage 一处" }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">版本号</span>
          <input v-model="form.version" class="ph-input" maxlength="32" :disabled="editing !== null" placeholder="如 v4" />
          <span class="ph-field__hint">
            {{ editing ? "版本号不可改：改正文要新建一版" : "**必须是新号**，同号会被拒（40900）" }}
          </span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">灰度比例（0–100）</span>
          <input v-model="form.grayRatio" class="ph-input ph-ai__num" inputmode="numeric" placeholder="0" />
          <span class="ph-field__hint">新版本先设 0 再逐步放量；改回 0 就是回滚</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">{{ editing ? "备注（留空保持原值）" : "备注（可选）" }}</span>
          <input v-model="form.remark" class="ph-input" maxlength="256" :placeholder="editing ? '' : '这一版改了什么'" />
        </label>
      </div>

      <label class="ph-field ph-ai__wide">
        <span class="ph-field__label">提示词正文{{ editing ? "（只读展示）" : "" }}</span>
        <textarea v-model="form.systemPrompt" class="ph-textarea ph-ai__code" rows="8" :readonly="editing !== null" />
        <span class="ph-field__hint">
          {{ editing ? "正文不在这个接口里改：改正文要新建一个版本（留痕按版本号归因）" : "新建时默认带出当前版本那一份：在它上面改，不要把整段重写掉" }}
        </span>
      </label>
      <label class="ph-field ph-ai__wide">
        <span class="ph-field__label">工具定义（只读下发）</span>
        <textarea v-model="form.toolSchema" class="ph-textarea ph-ai__code" rows="4" readonly />
        <span class="ph-field__hint">
          工具定义归代码常量那一层（ADR-0010）：这里只下发、不能改。新建版本要照抄一份——
          缺了它模型不会按格式上报，而这在线上表现为「解析失败 → 降级」。
        </span>
      </label>

      <p v-if="localError" class="ph-alert ph-alert--error ph-ai__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-ai__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : editing ? "保存灰度与启停" : "新建版本" }}
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

.ph-ai__num {
  width: 140px;
}

.ph-ai__wide {
  display: block;
  margin-top: var(--ph-space-4);
}

.ph-ai__code {
  width: 100%;
}

.ph-ai__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
