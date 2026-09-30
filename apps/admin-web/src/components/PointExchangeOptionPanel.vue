<script setup lang="ts">
/**
 * 积分兑换档位（`/api/v1/admin/points/exchange-options`）：读列表 / 新增 / 修改。
 *
 * **只兑平台补贴券**（ADR-0046 第六节）：兑换消耗的是平台的钱，不消耗服务者的贡献额度——
 * 否则等于把兑换成本转嫁给服务者，直接抵消它的出券意愿。后端在建档位时就校验 `cost_bearer = 2`，
 * 不是平台补贴券的模板会被拒成 40900，所以这里的券模板选择器**只列平台补贴券**
 * （从券池页的模板里筛），从源头上不给运营一个点了才知道被拒的选项。
 *
 * 档位一建就生效：用户能在积分中心看到并兑换。所以「消耗多少分、换什么」都是显式的两格，
 * 页面不预置任何档位（ADR-0046 提到兑换档位的数值同样属「没有规则依据，不编」那一类）。
 */
import { ref } from "vue";
import { formatAmount, formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type CouponTemplateRow, type ExchangeOptionRow, type PointExchangeOptionRequest } from "../api/adminApi";
import { useSection } from "../composables/useSection";

/** 平台补贴券模板（由页面统一加载：见 GrowthConfigView 的说明） */
const props = defineProps<{ templates: CouponTemplateRow[] }>();

const options = useSection<ExchangeOptionRow[]>();

const submit = useSubmitAction("兑换档位保存失败，请稍后重试");
/** null = 新增；有值 = 正在改这一档 */
const editing = ref<ExchangeOptionRow | null>(null);
const formVisible = ref(false);
const form = ref({ name: "", pointsCost: "", couponTemplateId: "", sortOrder: "", status: "1" });
const localError = ref("");

async function load(): Promise<void> {
  await options.load(() => adminApp.listPointExchangeOptions(), "兑换档位加载失败，请稍后重试");
}

function rows(): ExchangeOptionRow[] {
  return options.data.value ?? [];
}

function templateName(id?: number): string {
  const template = props.templates.find((item) => item.id === id);
  return template ? `${template.name}（${formatAmount(template.face_value)}）` : `模板 ${id ?? "—"}`;
}

function openCreate(): void {
  submit.clear();
  localError.value = "";
  editing.value = null;
  form.value = { name: "", pointsCost: "", couponTemplateId: props.templates[0] ? String(props.templates[0].id) : "", sortOrder: "", status: "1" };
  formVisible.value = true;
}

function openEdit(row: ExchangeOptionRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    name: row.name,
    pointsCost: String(row.points_cost),
    couponTemplateId: String(row.coupon_template_id),
    sortOrder: row.sort_order === null || row.sort_order === undefined ? "" : String(row.sort_order),
    status: String(row.status),
  };
  formVisible.value = true;
}

/** 本地先拦一遍，规则与契约的 40001 / 40900 条件逐条对应 */
function validate(): boolean {
  if (form.value.name.trim() === "") {
    localError.value = "档位名必填：用户在积分中心看到的就是它";
    return false;
  }
  if (form.value.name.trim().length > 64) {
    localError.value = "档位名最长 64 字";
    return false;
  }
  const cost = form.value.pointsCost.trim();
  if (!/^\d+$/.test(cost) || Number(cost) < 1 || Number(cost) > 100000) {
    localError.value = "消耗积分要 1–100000 的整数（契约的 40001 条件）";
    return false;
  }
  if (form.value.couponTemplateId === "") {
    localError.value = "必须选一个券模板：兑换的本质就是「扣分换券」";
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

function buildBody(): PointExchangeOptionRequest {
  const sortOrder = form.value.sortOrder.trim();
  const body: PointExchangeOptionRequest = {
    name: form.value.name.trim(),
    points_cost: Number(form.value.pointsCost.trim()),
    coupon_template_id: Number(form.value.couponTemplateId),
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
    () => (target ? adminApp.updatePointExchangeOption(target.id, body) : adminApp.createPointExchangeOption(body)),
    target ? "档位已更新（即时生效）" : "档位已新增（即时生效）",
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
      <span class="ph-text-weak">兑换只兑**平台补贴券**：消耗的是平台的钱，不动服务者的贡献额度</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="options.loading.value" @click="load">刷新</button>
      <button type="button" class="ph-button ph-button--primary" :disabled="props.templates.length === 0" @click="openCreate">新增兑换档位</button>
    </div>

    <p v-if="props.templates.length === 0" class="ph-alert ph-alert--info ph-growth__alert">
      还没有可选的**平台补贴券模板**（`cost_bearer = 2`）：兑换档位必须指向一张平台补贴券
      （兑换消耗的是平台的钱，ADR-0046 第六节），所以先去「券池管理」建一张平台补贴券模板。
    </p>

    <p class="ph-field__hint ph-growth__hint">
      档位一建就生效：用户能在积分中心看到并兑换它。这里不预置任何档位与消耗分值——
      「多少分换什么」是一次产品决定，不由代码替你定。停用（而不是删除）用于下架一个档位，
      已兑换出去的券不受影响。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-growth__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-growth__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="options.loading.value"
      :forbidden="options.forbidden.value"
      :error-message="options.errorMessage.value"
      :request-id="options.requestId.value"
      :is-empty="options.loaded.value && rows().length === 0"
      loading-title="正在加载兑换档位"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取兑换档位。"
      empty-title="还没有兑换档位"
      empty-description="没有档位时积分只能攒着、换不了东西（积分中心会显示暂无兑换项）。建一档：选一张平台补贴券 + 填消耗多少分。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>档位名</th>
              <th>消耗积分</th>
              <th>券模板</th>
              <th>排序</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.id">
              <td>{{ row.name }}</td>
              <td class="ph-table__num">{{ row.points_cost }}</td>
              <td>{{ templateName(row.coupon_template_id) }}</td>
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
      <h4 class="ph-card__title">{{ editing ? `编辑兑换档位：${editing.name}` : "新增兑换档位" }}</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">档位名</span>
          <input v-model="form.name" class="ph-input" maxlength="64" placeholder="用户看到的兑换项名称" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">消耗积分</span>
          <input v-model="form.pointsCost" class="ph-input ph-growth__num" inputmode="numeric" placeholder="正整数" />
          <span class="ph-field__hint">1–100000；积分是整数，不参与金额计算</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">券模板（只列平台补贴券）</span>
          <select v-model="form.couponTemplateId" class="ph-select ph-growth__wide">
            <option value="">请选择</option>
            <option v-for="template in props.templates" :key="template.id" :value="String(template.id)">
              {{ template.code }} · {{ template.name }}（{{ formatAmount(template.face_value) }}）
            </option>
          </select>
          <span class="ph-field__hint">不是平台补贴券的模板会被后端拒成 40900，所以这里只列它</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">排序（留空按默认）</span>
          <input v-model="form.sortOrder" class="ph-input ph-growth__num" inputmode="numeric" />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">状态</span>
          <select v-model="form.status" class="ph-select">
            <option value="1">启用（用户可兑换）</option>
            <option value="0">停用（不再展示）</option>
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
