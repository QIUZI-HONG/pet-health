<script setup lang="ts">
/**
 * 积分规则设置（`/api/v1/admin/points/settings`）：读每日获取上限 / 改它。
 *
 * 只有一项（`daily_earn_limit`，1–1000），但它是**业务可调项**（ADR-0010 的第二层）：
 * 分值松紧直接决定用户攒分速度，改完即时生效。一条容易误解的口径要写在界面上：
 * **邀请与一次性项不占这个上限**（ADR-0038 第四节）——否则 20 分的邀请奖励会被日上限吃掉，
 * 所以「今日上限 20 分」不等于「今天最多拿到 20 分」。
 */
import { ref } from "vue";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const settings = useSection<Awaited<ReturnType<typeof adminApp.getPointsSettings>>>();

const submit = useSubmitAction("积分规则保存失败，请稍后重试");
const editing = ref(false);
const form = ref({ dailyEarnLimit: "" });
const localError = ref("");

async function load(): Promise<void> {
  await settings.load(() => adminApp.getPointsSettings(), "积分规则加载失败，请稍后重试");
}

function openEdit(): void {
  submit.clear();
  localError.value = "";
  // 沿用服务端值：这一格改的是「当前上限」，不预填一个自己想出来的数
  form.value = { dailyEarnLimit: String(settings.data.value?.daily_earn_limit ?? "") };
  editing.value = true;
}

function validate(): boolean {
  const value = form.value.dailyEarnLimit.trim();
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 1000) {
    localError.value = "每日获取上限要是 1–1000 的整数（契约的 40001 条件）";
    return false;
  }
  localError.value = "";
  return true;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const outcome = await submit.run(
    () => adminApp.updatePointsSettings({ daily_earn_limit: Number(form.value.dailyEarnLimit.trim()) }),
    "积分规则已更新（即时生效）",
  );
  if (!outcome.ok) return;
  editing.value = false;
  await load();
}

void load();
</script>

<template>
  <div>
    <ConsoleListState
      :loading="settings.loading.value"
      :forbidden="settings.forbidden.value"
      :error-message="settings.errorMessage.value"
      :request-id="settings.requestId.value"
      :is-empty="false"
      loading-title="正在加载积分规则"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取积分规则设置。"
      @retry="load"
    >
      <dl v-if="settings.data.value" class="ph-kv">
        <dt>每日获取上限</dt>
        <dd>{{ settings.data.value.daily_earn_limit ?? "—" }} 分</dd>
      </dl>
      <p class="ph-field__hint ph-growth__hint">
        上限**只作用于 `counts_toward_daily_cap = 1` 的行为**：邀请与一次性项（完善档案）不占它
        ——否则一次邀请就把当天的上限吃光，之后用户做什么都不再得分（ADR-0038 第四节）。
        所以「今日上限」不等于「今天最多拿到多少分」。
      </p>

      <div class="ph-growth__actions">
        <button type="button" class="ph-button ph-button--primary" @click="openEdit">修改每日上限</button>
      </div>

      <form v-if="editing" class="ph-card ph-growth__form" @submit.prevent="save">
        <h4 class="ph-card__title">修改每日获取上限</h4>
        <label class="ph-field">
          <span class="ph-field__label">每日获取上限（分）</span>
          <input v-model="form.dailyEarnLimit" class="ph-input ph-growth__num" inputmode="numeric" placeholder="如 20" />
          <span class="ph-field__hint">1–1000 的整数；改的是「从什么时候开始算」，不影响已经发出去的积分</span>
        </label>

        <p v-if="localError" class="ph-alert ph-alert--error ph-growth__alert">{{ localError }}</p>
        <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-growth__alert">
          {{ submit.errorMessage.value }}
          <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
        </p>
        <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-growth__alert">{{ submit.doneMessage.value }}</p>

        <div class="ph-growth__actions">
          <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
            {{ submit.submitting.value ? "保存中…" : "保存" }}
          </button>
          <button type="button" class="ph-button ph-button--secondary" @click="editing = false">取消</button>
        </div>
      </form>

      <p v-if="!editing && submit.doneMessage.value" class="ph-alert ph-alert--info ph-growth__alert">{{ submit.doneMessage.value }}</p>
    </ConsoleListState>
  </div>
</template>

<style scoped>
.ph-growth__hint {
  max-width: 880px;
}

.ph-growth__form {
  margin-top: var(--ph-space-4);
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
