<script setup lang="ts">
/**
 * 运行时开关（`/api/v1/admin/ai/switches`）：读当前状态 / 切一个开关。
 *
 * 这一屏是**一键降级**的落点（ADR-0010 / ADR-0033 第三节），三条口径：
 * - **语义在代码里**，库里只存当前状态：所以没有「新建开关」——新增一个没人读的 code 会让运营
 *   以为它生效了，那比没有开关更糟（契约 `/ai/switches/{switch_code}` 也只在 code 存在时可写）；
 * - **库里读不到时一律按关闭算**：列表为空 = 所有开关都是关闭，不是「没有开关」；
 * - **打开它会立刻改变线上行为**（例如 `force_rule_only` 打开后全量走规则通道、不调模型）。
 *   所以每个开关的切换都要**二次确认**，确认区里写清这个 code 会做什么——这是可以一键降级的动作，
 *   也正是「降级不用发版」这句承诺的代价：点错同样不用发版就能生效。
 */
import { ref } from "vue";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type SwitchRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

const switches = useSection<SwitchRow[]>();

/** 切换动作的闸门（提交中禁用 + 失败文案 + 2 秒节流，见 useSubmitAction） */
const submit = useSubmitAction("开关切换失败，请稍后重试");
/** 正在等二次确认的那一个（含目标状态） */
const pending = ref<{ row: SwitchRow; next: boolean } | null>(null);

async function load(): Promise<void> {
  await switches.load(() => adminApp.listAiSwitches(), "运行时开关加载失败，请稍后重试");
}

function rows(): SwitchRow[] {
  return switches.data.value ?? [];
}

function ask(row: SwitchRow): void {
  submit.clear();
  pending.value = { row, next: !row.enabled };
}

async function confirmSwitch(): Promise<void> {
  const target = pending.value;
  if (!target) return;
  const outcome = await submit.run(
    () => adminApp.updateAiSwitch(target.row.code, target.next),
    target.next
      ? `${target.row.code} 已打开：线上行为立刻按它变化`
      : `${target.row.code} 已关闭：回到默认口径（库里读不到时一律按关闭算）`,
  );
  if (!outcome.ok) return;
  pending.value = null;
  await load();
}

/** 开关的说明来自服务端（`remark`）：库里没写说明时**不替它编**，直接说没有 */
function remarkText(row: SwitchRow): string {
  return row.remark && row.remark.trim() !== "" ? row.remark : "库里没写这个开关的说明（语义在代码里，代码常量是唯一出处）";
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">打开一个开关**立刻影响线上**：这里没有灰度、没有过渡期</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="switches.loading.value" @click="load">刷新</button>
    </div>

    <p class="ph-field__hint ph-ai__hint">
      开关的**语义在代码里**（ADR-0010 的判据），库里只存当前状态：所以这里没有「新建开关」——
      加一个代码里没人读的 code，只会让运营以为它生效了。**库里读不到时一律按关闭算**
      （ADR-0033 第三节），也就是说列表为空 = 所有开关都关着。切换即时生效，最多滞后一个 TTL。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-ai__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-ai__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="switches.loading.value"
      :forbidden="switches.forbidden.value"
      :error-message="switches.errorMessage.value"
      :request-id="switches.requestId.value"
      :is-empty="switches.loaded.value && rows().length === 0"
      loading-title="正在加载运行时开关"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取运行时开关。"
      empty-title="库里没有开关记录"
      empty-description="按契约的口径，读不到就是全部按关闭算——此时不存在「没开关」这回事，只是都关着。要降级请到后端确认开关编码是否已入库。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>开关编码</th>
              <th>当前状态</th>
              <th>含义（服务端给的说明）</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>
                <span class="ph-tag" :class="row.enabled ? 'ph-tag--danger' : 'ph-tag--success'">
                  {{ row.enabled ? "打开" : "关闭" }}
                </span>
              </td>
              <td>{{ remarkText(row) }}</td>
              <td class="ph-table__num">{{ row.updated_at ?? "—" }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" :disabled="submit.submitting.value" @click="ask(row)">
                    {{ row.enabled ? "关闭" : "打开" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <!-- 切换的二次确认：把「打开它线上会变成什么样」讲清楚再让他点 -->
    <div v-if="pending" class="ph-alert ph-alert--warn ph-ai__alert">
      <p>
        确认把 <strong>{{ pending.row.code }}</strong> {{ pending.next ? "打开" : "关闭" }}？
        {{ pending.next ? "打开后线上立刻按它变化：" : "关闭后回到默认口径：" }}{{ remarkText(pending.row) }}
      </p>
      <p class="ph-field__hint">
        这个动作**不用发版也不经过审批**——这正是「一键降级」的意思，请确认当前确实需要它。
        切换留痕在开关表的 `updated_at` 上，操作者由服务端记（ADR-0011）。
      </p>
      <div class="ph-ai__actions">
        <button type="button" class="ph-button ph-button--primary" :disabled="submit.submitting.value" @click="confirmSwitch">
          {{ submit.submitting.value ? "提交中…" : pending.next ? "确认打开" : "确认关闭" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="pending = null">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ph-ai__hint {
  max-width: 880px;
}

.ph-ai__alert {
  margin-top: var(--ph-space-3);
}

.ph-ai__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-3);
}
</style>
