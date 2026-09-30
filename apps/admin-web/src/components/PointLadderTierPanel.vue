<script setup lang="ts">
/**
 * 月度阶梯档位（`/api/v1/admin/points/ladder-tiers`）：读列表 / 新增 / 修改（F018）。
 *
 * **种子里一个档位都没有**（ADR-0046 第八节）：门槛值与奖励没有规则依据，不编。档位为空时
 * 每月 1 日的批算照跑、不发任何券（骨架是通的），运营配好档位即生效——所以这一屏的空态不是在报故障，
 * 而是在说「这里本来就该由你来填」。页面也**不预置**任何门槛与张数。
 *
 * 另外两条批算口径要写在界面上，否则运营会按直觉理解：
 * - **只拿达成的最高一档**（不是累加多档）：一个人上月 520 分 → 拿 500 分档；
 * - 发的是平台补贴券（与兑换同理：阶梯奖励属于平台侧的发放），所以券模板选择器只列平台补贴券。
 */
import { ref } from "vue";
import { formatAmount, formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type CouponTemplateRow, type PointLadderRow, type PointLadderTierRequest } from "../api/adminApi";
import { useSection } from "../composables/useSection";

/** 平台补贴券模板（由页面统一加载：见 GrowthConfigView 的说明） */
const props = defineProps<{ templates: CouponTemplateRow[] }>();

const tiers = useSection<PointLadderRow[]>();

const submit = useSubmitAction("阶梯档位保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这一档 */
const editing = ref<PointLadderRow | null>(null);
const formVisible = ref(false);
const form = ref({ thresholdPoints: "", couponTemplateId: "", couponCount: "1", sortOrder: "", status: "1" });
const localError = ref("");

async function load(): Promise<void> {
  await tiers.load(() => adminApp.listPointLadderTiers(), "月度阶梯档位加载失败，请稍后重试");
}

function rows(): PointLadderRow[] {
  return tiers.data.value ?? [];
}

function templateName(id?: number): string {
  const template = props.templates.find((item) => item.id === id);
  return template ? `${template.name}（${formatAmount(template.face_value)}）` : `模板 ${id ?? "—"}`;
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = {
    thresholdPoints: "",
    couponTemplateId: props.templates[0] ? String(props.templates[0].id) : "",
    // 张数留空：它是一份要发出去的奖励，界面不预设一个数（1 张也不是「显然的」）
    couponCount: "",
    sortOrder: "",
    status: "1",
  };
  formVisible.value = true;
}

function openEdit(row: PointLadderRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    thresholdPoints: String(row.threshold_points),
    couponTemplateId: String(row.coupon_template_id),
    couponCount: String(row.coupon_count),
    sortOrder: row.sort_order === null || row.sort_order === undefined ? "" : String(row.sort_order),
    status: String(row.status),
  };
  formVisible.value = true;
}

/** 本地先拦一遍，规则与契约的 40001 / 40900 条件逐条对应 */
function validate(): boolean {
  const threshold = form.value.thresholdPoints.trim();
  if (!/^\d+$/.test(threshold) || Number(threshold) < 1 || Number(threshold) > 1000000) {
    localError.value = "门槛要 1–1000000 的整数（上月累计获得积分不低于它）";
    return false;
  }
  const duplicated = rows().some(
    (row) => row.id !== editing.value?.id && row.threshold_points === Number(threshold),
  );
  if (duplicated) {
    localError.value = `已经有一个门槛 ${threshold} 的档位：同一门槛只允许一个档（40900）`;
    return false;
  }
  if (form.value.couponTemplateId === "") {
    localError.value = "必须选一张平台补贴券模板：阶梯奖励发的是券";
    return false;
  }
  const count = form.value.couponCount.trim();
  if (!/^\d+$/.test(count) || Number(count) < 1 || Number(count) > 10) {
    localError.value = "发券张数要显式填 1–10 的整数：这是一份真发出去的奖励，界面不替你定";
    return false;
  }
  const sortOrder = form.value.sortOrder.trim();
  if (sortOrder !== "" && !/^\d+$/.test(sortOrder)) {
    localError.value = "排序要是非负整数，留空按后端默认";
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): PointLadderTierRequest {
  const sortOrder = form.value.sortOrder.trim();
  const body: PointLadderTierRequest = {
    threshold_points: Number(form.value.thresholdPoints.trim()),
    coupon_template_id: Number(form.value.couponTemplateId),
    coupon_count: Number(form.value.couponCount.trim()),
    status: Number(form.value.status),
  };
  if (sortOrder !== "") {
    body.sort_order = Number(sortOrder);
  }
  return body;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body = buildBody();
  const target = editing.value;
  const outcome = await submit.run(
    () => (target ? adminApp.updatePointLadderTier(target.id, body) : adminApp.createPointLadderTier(body)),
    target
      ? "档位已更新：下个月 1 日的批算按新配置发（不会补发上月已算过的）"
      : "档位已新增：下个月 1 日的批算就会用它（当月不追溯）",
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">每月 1 日算上月累计积分 → **只发达成的最高一档**（不是累加多档）</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="tiers.loading.value" @click="load">刷新</button>
      <button type="button" class="ph-button ph-button--primary" :disabled="props.templates.length === 0" @click="openCreate">新增档位</button>
    </div>

    <p v-if="props.templates.length === 0" class="ph-alert ph-alert--info ph-growth__alert">
      还没有可选的**平台补贴券模板**（`cost_bearer = 2`）：阶梯奖励发的是平台补贴券，
      所以先去「券池管理」建一张。
    </p>

    <p class="ph-field__hint ph-growth__hint">
      **门槛与奖励没有任何规则依据，所以这一页一个档位都没预置**（ADR-0046 第八节）：
      档位为空时每月 1 日的批算照跑、不发券（骨架是通的），配好即生效。批算口径：按**上月累计获得积分**
      取达成的最高一档，一个人只拿一档；幂等靠「用户 + 账期」唯一，重跑不会发两次。
      年度大奖不做（连占位都不留）。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-growth__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-growth__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="tiers.loading.value"
      :forbidden="tiers.forbidden.value"
      :error-message="tiers.errorMessage.value"
      :request-id="tiers.requestId.value"
      :is-empty="tiers.loaded.value && rows().length === 0"
      loading-title="正在加载月度阶梯档位"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取月度阶梯档位。"
      empty-title="还没有配置任何档位（这是初始状态）"
      empty-description="空档位不会报错：每月 1 日的批算照跑，只是谁都不发。要开始发就先加一档——门槛多少分、发哪张平台补贴券、发几张，都由你填。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>门槛（上月累计积分）</th>
              <th>奖励券模板</th>
              <th>发几张</th>
              <th>排序</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td class="ph-table__num">≥ {{ row.threshold_points }}</td>
              <td>{{ templateName(row.coupon_template_id) }}</td>
              <td class="ph-table__num">{{ row.coupon_count }} 张</td>
              <td class="ph-table__num">{{ row.sort_order ?? "—" }}</td>
              <td>
                <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.status === 1 ? "启用" : "停用" }}
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

    <form v-if="formVisible" class="ph-card ph-growth__form" @submit.prevent="save">
      <h4 class="ph-card__title">{{ editing ? `编辑档位：≥ ${editing.threshold_points} 分` : "新增月度阶梯档位" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">门槛（上月累计获得积分）</span>
          <input v-model="form.thresholdPoints" class="ph-input ph-growth__num" inputmode="numeric" placeholder="由你定" />
          <span class="ph-field__hint">1–1000000；同一门槛只允许一个档</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">奖励券模板（只列平台补贴券）</span>
          <select v-model="form.couponTemplateId" class="ph-select ph-growth__wide">
            <option value="">请选择</option>
            <option v-for="template in props.templates" :key="template.id" :value="String(template.id)">
              {{ template.code }} · {{ template.name }}（{{ formatAmount(template.face_value) }}）
            </option>
          </select>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">发几张</span>
          <input v-model="form.couponCount" class="ph-input ph-growth__num" inputmode="numeric" placeholder="1–10" />
          <span class="ph-field__hint">1–10 张；要显式填，不预置</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">排序（留空按默认）</span>
          <input v-model="form.sortOrder" class="ph-input ph-growth__num" inputmode="numeric" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">状态</span>
          <select v-model="form.status" class="ph-select">
            <option value="1">启用（参与批算）</option>
            <option value="0">停用（批算时跳过这一档）</option>
          </select>
        </label>
      </div>

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

.ph-growth__wide {
  min-width: 280px;
}

.ph-growth__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
