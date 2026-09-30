<script setup lang="ts">
/**
 * 行为分值表（`/api/v1/admin/points/behaviors`）：读列表 / 改分值、频次与启停。
 *
 * **不能新增行为**（契约与 ADR-0046 第七节）：行为码要有代码去触发它、要有人发分，
 * 加一行数据只会产生一个永远不会发生的动作——新增行为是代码变更，不是配置变更。所以这一屏
 * 没有「新增」按钮，只有每行的编辑。
 *
 * 三件套一起改：`points`（单次分值，0 = 只记行为不发分）、`counts_toward_daily_cap`（是否占日上限）、
 * `daily_count_limit` / `monthly_count_limit`（次数上限，空 = 不限）、`once_only`（一次性）。
 * 契约写明「不传保持原值」，但这里**每次都把当前值显式回传**：表单里看得见的就是要写进去的，
 * 隐藏的字段不参与改动（否则「只改了分值，上限却被清空」这种事故没人能复现）。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type PointBehaviorRequest, type PointBehaviorRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const behaviors = useSection<PointBehaviorRow[]>();

const submit = useSubmitAction("行为分值保存失败，请稍后重试");
const editing = ref<PointBehaviorRow | null>(null);
const formVisible = ref(false);
const form = ref({
  points: "0",
  countsTowardDailyCap: "1",
  dailyLimit: "",
  monthlyLimit: "",
  onceOnly: "0",
  status: "1",
});
const localError = ref("");

async function load(): Promise<void> {
  await behaviors.load(() => adminApp.listPointBehaviors(), "行为分值表加载失败，请稍后重试");
}

function rows(): PointBehaviorRow[] {
  return behaviors.data.value ?? [];
}

function openEdit(row: PointBehaviorRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    points: String(row.points ?? 0),
    countsTowardDailyCap: String(row.counts_toward_daily_cap ?? 0),
    dailyLimit: row.daily_count_limit === null || row.daily_count_limit === undefined ? "" : String(row.daily_count_limit),
    monthlyLimit: row.monthly_count_limit === null || row.monthly_count_limit === undefined ? "" : String(row.monthly_count_limit),
    onceOnly: String(row.once_only ?? 0),
    status: String(row.status ?? 1),
  };
  formVisible.value = true;
}

function limitOrNull(text: string): number | null {
  const value = text.trim();
  return value === "" ? null : Number(value);
}

/** 本地先拦一遍，规则与契约的 40001 条件逐条对应 */
function validate(): boolean {
  const points = form.value.points.trim();
  if (!/^\d+$/.test(points) || Number(points) > 1000) {
    localError.value = "单次分值要 0–1000 的整数；0 表示只记行为不发分";
    return false;
  }
  for (const [label, text] of [["每日次数上限", form.value.dailyLimit], ["每月次数上限", form.value.monthlyLimit]] as const) {
    const value = text.trim();
    if (value !== "" && (!/^\d+$/.test(value) || Number(value) < 1)) {
      localError.value = `${label}要是正整数，不限就留空（0 与空同义，这里一律按空处理）`;
      return false;
    }
  }
  localError.value = "";
  return true;
}

function buildBody(): PointBehaviorRequest {
  return {
    points: Number(form.value.points.trim()),
    counts_toward_daily_cap: Number(form.value.countsTowardDailyCap),
    daily_count_limit: limitOrNull(form.value.dailyLimit),
    monthly_count_limit: limitOrNull(form.value.monthlyLimit),
    once_only: Number(form.value.onceOnly),
    status: Number(form.value.status),
  };
}

async function save(): Promise<void> {
  const target = editing.value;
  if (!target) return;
  if (!validate()) return;
  const outcome = await submit.run(
    () => adminApp.updatePointBehavior(target.code, buildBody()),
    `${target.name} 已更新：只影响之后的发放，历史流水不会重算`,
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

function limitText(value?: number | null): string {
  return value === null || value === undefined ? "不限" : `${value} 次`;
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">行为码不能新增（新增行为是代码变更）；这一屏只改分值、频次与启停</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="behaviors.loading.value" @click="load">刷新</button>
    </div>

    <p class="ph-field__hint ph-growth__hint">
      分值、频次上限、是否占每日上限都可调（ADR-0010），改完即时生效。**分值不能凭空加行为**：
      行为码要有代码去触发，所以这里没有「新增」——加一行数据只会造出一个永远不会发生的动作。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-growth__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-growth__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="behaviors.loading.value"
      :forbidden="behaviors.forbidden.value"
      :error-message="behaviors.errorMessage.value"
      :request-id="behaviors.requestId.value"
      :is-empty="behaviors.loaded.value && rows().length === 0"
      loading-title="正在加载行为分值表"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取行为分值表。"
      empty-title="行为表是空的"
      empty-description="行为表由后端初始化脚本落库（行为码必须有人发分），空表说明初始化没跑完，不是这一页能补的。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>行为码</th>
              <th>名称</th>
              <th>单次分值</th>
              <th>占每日上限</th>
              <th>每日次数</th>
              <th>每月次数</th>
              <th>一次性</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.code">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td class="ph-table__num">{{ row.points ?? 0 }}{{ (row.points ?? 0) === 0 ? "（只记行为）" : " 分" }}</td>
              <td>{{ (row.counts_toward_daily_cap ?? 0) === 1 ? "占" : "不占" }}</td>
              <td class="ph-table__num">{{ limitText(row.daily_count_limit) }}</td>
              <td class="ph-table__num">{{ limitText(row.monthly_count_limit) }}</td>
              <td>{{ (row.once_only ?? 0) === 1 ? "一次性" : "可重复" }}</td>
              <td>
                <span class="ph-tag" :class="(row.status ?? 0) === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ (row.status ?? 0) === 1 ? "启用" : "停用" }}
                </span>
              </td>
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

    <form v-if="formVisible && editing" class="ph-card ph-growth__form" @submit.prevent="save">
      <h4 class="ph-card__title">修改行为分值：{{ editing.name }}（{{ editing.code }}）</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">单次分值</span>
          <input v-model="form.points" class="ph-input ph-growth__num" inputmode="numeric" />
          <span class="ph-field__hint">0–1000；0 表示只记行为不发分</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">占每日上限</span>
          <select v-model="form.countsTowardDailyCap" class="ph-select">
            <option value="1">占（受每日获取上限约束）</option>
            <option value="0">不占（邀请与一次性项）</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">每日次数上限（留空 = 不限）</span>
          <input v-model="form.dailyLimit" class="ph-input ph-growth__num" inputmode="numeric" placeholder="不限" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">每月次数上限（留空 = 不限）</span>
          <input v-model="form.monthlyLimit" class="ph-input ph-growth__num" inputmode="numeric" placeholder="不限" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">可重复性</span>
          <select v-model="form.onceOnly" class="ph-select">
            <option value="0">可重复（受上面的次数上限约束）</option>
            <option value="1">一次性（一辈子只发一次）</option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">状态</span>
          <select v-model="form.status" class="ph-select">
            <option value="1">启用</option>
            <option value="0">停用</option>
          </select>
          <span class="ph-field__hint">停用后这个行为不再发分（流水里仍会留 `change=0` 的行为记录）</span>
        </label>
      </div>

      <p class="ph-field__hint ph-growth__hint">
        判据都是**业务日**（东八区）算的，不是自然日的 `created_at`（ADR-0046 第五节）。
        到了上限是**整笔不发**，不做部分发放——所以把分值调大时要顺带看一眼上限。
      </p>

      <p v-if="localError" class="ph-alert ph-alert--error ph-growth__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-growth__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-growth__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="formVisible = false">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-growth__hint {
  max-width: 880px;
}

.ph-growth__form {
  margin-top: var(--ph-space-5);
}

.ph-growth__alert {
  margin-top: var(--ph-space-3);
}

.ph-growth__num {
  width: 140px;
}

.ph-growth__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
