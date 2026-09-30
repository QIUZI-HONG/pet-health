<script setup lang="ts">
/**
 * 邀请阶梯档位（`/api/v1/admin/invites/ladder-tiers`）：读五档 / 配置每一档的奖励。
 *
 * 门槛是固定的五档（1 / 3 / 5 / 10 / 15 有效邀请数，ADR-0039），运营能改的是**每档发什么**：
 * 发券（指定券模板，可发多张）或授权益（指定权益码，永久）。三条口径决定这一屏：
 * - **奖励物默认未配**：`reward_type` 为空的档位达成时**不发东西**，但达成记录照记
 *   （ADR-0046 第四节：「谁在哪一档」是数据，「发什么」是配置）。所以页面不预置任何奖励；
 * - **改配置不会补发历史档位**：补发是一次显式的运营动作，不该藏在配置里。所以保存后的提示语
 *   要说清「只对之后达成的生效」；
 * - **门槛不可改**（契约的路径参数就是门槛）：改门槛等于重新定义阶梯，能改的只有奖励与启停。
 *
 * 有效邀请的口径是「完成建档 + 24 小时内有行为」（ADR-0046 第二节），不是注册数——
 * 界面文案要带一句，否则运营会把「注册了 5 个人」当成已达成 5 人档。
 */
import { ref } from "vue";
import { formatAmount, formatDateTime } from "@pet-health/shared";
import { ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { adminApp, type CouponTemplateRow, type InviteLadderRow, type InviteLadderTierRequest, type RightsCodeRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

/** 券模板（只列启用中的，后端也要求启用）与权益码（授权益时按码挑），都由页面统一加载 */
const props = defineProps<{ templates: CouponTemplateRow[]; rightsCodes: RightsCodeRow[] }>();

const tiers = useSection<InviteLadderRow[]>();

const submit = useSubmitAction("阶梯奖励保存失败，请稍后重试");
/** 正在配置的那一档（门槛是路径参数，所以这里记的是整行） */
const editing = ref<InviteLadderRow | null>(null);
const formVisible = ref(false);
/** "none" = 这一档不发东西（契约里 `reward_type` 传空） */
const form = ref({ rewardType: "none", couponTemplateId: "", rewardCount: "1", rightsCode: "", status: "1" });
const localError = ref("");

async function load(): Promise<void> {
  await tiers.load(() => adminApp.listInviteLadderTiers(), "邀请阶梯档位加载失败，请稍后重试");
}

function rows(): InviteLadderRow[] {
  return tiers.data.value ?? [];
}

function templateText(id?: number | null): string {
  if (id === null || id === undefined) return "—";
  const template = props.templates.find((item) => item.id === id);
  return template ? `${template.name}（${formatAmount(template.face_value)}）` : `模板 ${id}`;
}

function openEdit(row: InviteLadderRow): void {
  submit.clear();
  localError.value = "";
  editing.value = row;
  form.value = {
    rewardType: row.reward_type === 1 ? "coupon" : row.reward_type === 2 ? "rights" : "none",
    couponTemplateId: row.coupon_template_id === null || row.coupon_template_id === undefined ? "" : String(row.coupon_template_id),
    rewardCount: String(row.reward_count ?? 1),
    rightsCode: row.rights_code ?? "",
    status: String(row.status),
  };
  formVisible.value = true;
}

/** 本地先拦一遍，规则与契约的 40001 条件逐条对应（奖励物缺失是这一屏最容易犯的错） */
function validate(): boolean {
  if (form.value.rewardType === "coupon") {
    if (form.value.couponTemplateId === "") {
      localError.value = "发券的档位必须选一个券模板（否则后端 40001）";
      return false;
    }
    const count = form.value.rewardCount.trim();
    if (!/^\d+$/.test(count) || Number(count) < 1 || Number(count) > 10) {
      localError.value = "发券张数要 1–10（契约的 minimum 1 / maximum 10）";
      return false;
    }
  }
  if (form.value.rewardType === "rights" && form.value.rightsCode === "") {
    localError.value = "授权益的档位必须选一个权益码（否则后端 40001）";
    return false;
  }
  localError.value = "";
  return true;
}

function buildBody(): InviteLadderTierRequest {
  const body: InviteLadderTierRequest = {
    status: Number(form.value.status),
    // 三态：发券 / 授权益 / 不发东西。传空就是「不发东西」（达成记录照记，ADR-0046 第四节）
    reward_type: form.value.rewardType === "coupon" ? 1 : form.value.rewardType === "rights" ? 2 : null,
    coupon_template_id: form.value.rewardType === "coupon" ? Number(form.value.couponTemplateId) : null,
    rights_code: form.value.rewardType === "rights" ? form.value.rightsCode : null,
  };
  // 张数只在发券时有意义：不发东西的档位带上它，读的人会以为发了一张
  if (form.value.rewardType === "coupon") {
    body.reward_count = Number(form.value.rewardCount.trim());
  }
  return body;
}

async function save(): Promise<void> {
  const target = editing.value;
  if (!target) return;
  if (!validate()) return;
  const outcome = await submit.run(
    () => adminApp.updateInviteLadderTier(target.threshold, buildBody()),
    `门槛 ${target.threshold} 的奖励已更新：**只对之后达成的用户生效**，历史已达成的不补发（补发是另一件显式动作）`,
  );
  if (!outcome.ok) return;
  formVisible.value = false;
  await load();
}

function rewardText(row: InviteLadderRow): string {
  if (row.reward_type === 1) return `${row.reward_count ?? 1} 张券 · ${templateText(row.coupon_template_id)}`;
  if (row.reward_type === 2) return `权益 · ${row.rights_name ?? row.rights_code ?? "—"}（永久）`;
  return "未配奖励：达成照记，不发东西";
}

void load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <span class="ph-text-weak">门槛固定五档（有效邀请数 1 / 3 / 5 / 10 / 15）——这一屏改的是「每档发什么」</span>
      <span class="ph-toolbar__spacer" />
      <button type="button" class="ph-button ph-button--secondary" :disabled="tiers.loading.value" @click="load">刷新</button>
    </div>

    <p class="ph-field__hint ph-growth__hint">
      「有效邀请」= 被邀请人**完成建档 + 24 小时内有行为**（ADR-0046 第二节），不是注册数：
      只看注册数会让刷出来的号当晚就把阶梯奖领走。**奖励默认未配**——未配的档位达成时只记达成、
      不发东西（「谁在哪一档」是数据，「发什么」是配置，两件事分开才不会在改配置时丢历史）。
      奖励**只在达成那一次发一次**，改配置不会补发历史档位。
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
      loading-title="正在加载邀请阶梯档位"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取邀请阶梯档位。"
      empty-title="没有档位数据"
      empty-description="五档门槛由后端初始化落库（ADR-0039 定的固定档），空表说明初始化没跑完，不是这一页能补的。"
      @retry="load"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>门槛（有效邀请数）</th>
              <th>奖励</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows()" :key="row.threshold">
              <td class="ph-table__num">{{ row.threshold }} 人</td>
              <td>{{ rewardText(row) }}</td>
              <td>
                <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                  {{ row.status === 1 ? "启用" : "停用" }}
                </span>
              </td>
              <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button type="button" class="ph-table__action" @click="openEdit(row)">
                    {{ row.reward_type === null || row.reward_type === undefined ? "配置奖励" : "编辑" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </ConsoleListState>

    <form v-if="formVisible && editing" class="ph-card ph-growth__form" @submit.prevent="save">
      <h4 class="ph-card__title">配置奖励：门槛 {{ editing.threshold }} 人</h4>
      <p class="ph-field__hint ph-growth__hint">
        门槛不可改（改门槛等于重新定义阶梯）：能改的只有奖励物与启停。
      </p>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">奖励类型</span>
          <select v-model="form.rewardType" class="ph-select ph-growth__wide">
            <option value="none">不发奖励（达成照记）</option>
            <option value="coupon">发券</option>
            <option value="rights">授予权益（永久）</option>
          </select>
        </label>
        <label v-if="form.rewardType === 'coupon'" class="ph-field">
          <span class="ph-field__label">券模板（后端要求启用中）</span>
          <select v-model="form.couponTemplateId" class="ph-select ph-growth__wide">
            <option value="">请选择</option>
            <option v-for="template in props.templates" :key="template.id" :value="String(template.id)">
              {{ template.code }} · {{ template.name }}（{{ formatAmount(template.face_value) }}）
            </option>
          </select>
        </label>
        <label v-if="form.rewardType === 'coupon'" class="ph-field">
          <span class="ph-field__label">发几张</span>
          <input v-model="form.rewardCount" class="ph-input ph-growth__num" inputmode="numeric" />
          <span class="ph-field__hint">1–10 张</span>
        </label>
        <label v-if="form.rewardType === 'rights'" class="ph-field">
          <span class="ph-field__label">权益码</span>
          <select v-model="form.rightsCode" class="ph-select ph-growth__wide">
            <option value="">请选择</option>
            <option v-for="code in props.rightsCodes" :key="code.code" :value="code.code">
              {{ code.code }} · {{ code.name }}{{ code.status === 0 ? "（已停用）" : "" }}
            </option>
          </select>
          <span class="ph-field__hint">授予的是**永久**权益（ADR-0046 第四节）；停用中的码仍可被授予，但不许新授予——挑之前先确认</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">状态</span>
          <select v-model="form.status" class="ph-select">
            <option value="1">启用（达成时发）</option>
            <option value="0">停用（达成时跳过这一档）</option>
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
          {{ submit.submitting.value ? "保存中…" : "保存奖励" }}
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
  min-width: 300px;
}

.ph-growth__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
