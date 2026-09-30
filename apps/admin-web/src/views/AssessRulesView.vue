<script setup lang="ts">
/**
 * 考核规则配置（交付文档 3.1 模块树；口径见 PRD F022 / ADR-0039 第三节 / ADR-0050 第四节 / ADR-0052）。
 *
 * 规则本身：总分 = 拉新 40% + 券 40% + 过程 20%，三项各有一条达标线，三档（基础 / 优选 / 战略合作）
 * 各有阈值与 AI 推荐优先级。**改了只影响之后算出的账期**——历史账期存的是当时的快照，所以
 * 「改规则」不会让上个月的分变样（跨月可对比的前提）。
 *
 * 达标线为 0 表示**该项还没定要求**：按 ADR-0050 第四节，该维度不参与计分并重算权重
 * （明细里注明「未参与」），而不是给它记 0 分——所以规则的展示要把「0 = 未配置」说清楚，
 * 不能让人以为是「要求是 0 分」。
 *
 * **写入口只归超级管理员**（ADR-0037 第一节的矩阵把考核规则配置划给超管，ADR-0052 定的门禁是
 * 登录域 + 超管名单，名单未配置时**任何 admin 域身份都不能改**＝fail-closed）。而前端拿不到
 * 「谁是超管」这个事实：令牌里只有登录域、没有角色（`session.isSuperAdmin` **当前恒为 false**，
 * 理由见 session.ts）。所以这一页的处理与 `ProviderListPanel` 的清退入口一致——**不渲染写入口**，
 * 只读展示规则并写明原因：界面假装拦住不算拦住，而改错规则的代价是所有服务者下个月的分。
 * 角色声明进令牌（或有了 `GET /api/v1/admin/me` 之类）之后，这一位就自动接通，页面不用改。
 *
 * 另一处同样只归超管的动作是**覆盖单项分**（`POST /assessments/{score_id}/overrides`）：它需要一个
 * 「考核明细」页（服务者后台有同形状的接口，本端只有这一个页面），本 app 的模块树里没有它，
 * 所以这里不摆入口——缺口写在页面下方。
 */
import { computed, onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, useSubmitAction } from "@pet-health/ui";
import { isRangeBoundValid, parseAmountToCents } from "@pet-health/shared";
import { adminApp, type AssessmentLevelRuleRequest, type AssessmentRuleRequest, type AssessmentRuleView } from "../api/adminApi";
import { useSection } from "../composables/useSection";
import { useAdminSession } from "../session";

const { status, isSuperAdmin } = useAdminSession();

const rules = useSection<AssessmentRuleView>();

const submit = useSubmitAction("考核规则保存失败，请稍后重试");
const editing = ref(false);
const localError = ref("");
/** 表单：三项权重 / 三条达标线 / 三档阈值。分数与达标线一律**字符串**（两位小数，不走浮点） */
const form = ref({
  inviteWeight: "40",
  couponWeight: "40",
  processWeight: "20",
  inviteTarget: "0",
  couponTarget: "0.00",
  responseMinutesTarget: "0",
  levels: [] as { level: number; levelName: string; minScore: string; recommendPriority: string }[],
});

const levels = computed(() => rules.data.value?.levels ?? []);

async function load(): Promise<void> {
  await rules.load(() => adminApp.getAssessmentRules(), "考核规则加载失败，请稍后重试");
}

function openEdit(): void {
  const current = rules.data.value;
  if (!current) return;
  submit.clear();
  localError.value = "";
  form.value = {
    inviteWeight: String(current.invite_weight ?? 0),
    couponWeight: String(current.coupon_weight ?? 0),
    processWeight: String(current.process_weight ?? 0),
    inviteTarget: String(current.invite_target ?? 0),
    couponTarget: current.coupon_target ?? "0.00",
    responseMinutesTarget: String(current.response_minutes_target ?? 0),
    levels: (current.levels ?? []).map((item) => ({
      level: item.level ?? 0,
      levelName: item.level_name ?? "",
      minScore: item.min_score ?? "0.00",
      recommendPriority: String(item.recommend_priority ?? 3),
    })),
  };
  editing.value = true;
}

/** 本地先拦一遍：逐条对应契约的 40001 条件（权重和为 100 / 三档严格递增 / 基础档从 0 起） */
function validate(): boolean {
  const weights = [form.value.inviteWeight, form.value.couponWeight, form.value.processWeight].map((item) => Number(item));
  if (weights.some((item) => !Number.isInteger(item) || item < 0) || weights.reduce((sum, item) => sum + item, 0) !== 100) {
    localError.value = "三项权重必须是整数且**之和为 100**（默认 40 / 40 / 20）";
    return false;
  }
  if (!/^\d+$/.test(form.value.inviteTarget.trim())) {
    localError.value = "拉新达标线要非负整数（有效邀请数）；0 = 该项未配置，不参与计分";
    return false;
  }
  if (!isRangeBoundValid(form.value.couponTarget)) {
    localError.value = "券达标线要是两位小数字符串（完成率 × 核销数，如 0.60 或 3.00）；0.00 = 未配置";
    return false;
  }
  if (!/^\d+$/.test(form.value.responseMinutesTarget.trim()) || Number(form.value.responseMinutesTarget) <= 0) {
    localError.value = "接单响应达标线要是正整数（分钟）：不超过它记满分，超过按比例扣";
    return false;
  }
  if (form.value.levels.length !== 3) {
    localError.value = "必须有且仅有三档（基础 / 优选 / 战略合作）";
    return false;
  }
  const scores: number[] = [];
  for (const item of form.value.levels) {
    const cents = parseAmountToCents(item.minScore);
    // 总分是 0.00–100.00 的两位小数字符串：分不是钱，但同样不许浮点（ADR-0011 的精度纪律）
    if (cents === null || cents > 10000) {
      localError.value = `「${item.levelName}」档的最低总分要写成两位小数字符串（0.00–100.00）`;
      return false;
    }
    scores.push(cents);
  }
  for (let index = 1; index < scores.length; index += 1) {
    if (scores[index]! <= scores[index - 1]!) {
      localError.value = "三档阈值要**严格递增**：否则会出现谁都匹配不上的分数段";
      return false;
    }
  }
  if (scores[0] !== 0) {
    localError.value = "基础档必须从 0.00 起：分数段不能有缺口（契约的 40001 条件）";
    return false;
  }
  localError.value = "";
  return true;
}

async function save(): Promise<void> {
  if (!validate()) return;
  const body: AssessmentRuleRequest = {
    invite_weight: Number(form.value.inviteWeight),
    coupon_weight: Number(form.value.couponWeight),
    process_weight: Number(form.value.processWeight),
    invite_target: Number(form.value.inviteTarget),
    coupon_target: form.value.couponTarget.trim(),
    response_minutes_target: Number(form.value.responseMinutesTarget),
    levels: form.value.levels.map<AssessmentLevelRuleRequest>((item) => ({
      level: item.level,
      min_score: item.minScore.trim(),
      recommend_priority: Number(item.recommendPriority),
    })),
  };
  const outcome = await submit.run(() => adminApp.updateAssessmentRules(body), "规则已更新：只影响之后算出的账期");
  if (!outcome.ok) return;
  editing.value = false;
  await load();
}

/** 0 = 未配置（不参与计分），与「要求是 0」不是一回事，所以要单独说一句 */
function targetText(value?: number | string | null): string {
  if (value === undefined || value === null) return "—";
  return String(value) === "0" || String(value) === "0.00" ? `${value}（未配置，该项不参与计分）` : String(value);
}

function priorityLabel(value?: number | null): string {
  switch (value) {
    case 1:
      return "1 最高";
    case 2:
      return "2 较高";
    case 3:
      return "3 普通";
    default:
      return "—";
  }
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    void load();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">考核规则配置</h2>
    <p class="ph-page-desc">
      考核的权重、达标线与三档阈值：拉新 40% + 券 40% + 过程 20% 这三项怎么算、多少分对应哪个等级、每档对应什么推荐优先级（等级映射进考核记录，推荐逻辑本身不在这里）。改动只影响之后算出的账期，历史账期存的是当时的快照。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <p v-if="!isSuperAdmin" class="ph-alert ph-alert--info ph-assess__gate">
        <strong>本页目前是只读的：写入口只归超级管理员</strong>（ADR-0037 第一节的矩阵；门禁见 ADR-0052：
        超管名单未配置时任何 admin 域身份都不能改，fail-closed）。而前端拿不到「谁是超管」——令牌里
        只有登录域、没有角色声明（ADR-0035「需要协调」第 3 条），`session.isSuperAdmin` 当前恒为 false。
        所以这里**不渲染编辑入口**，而不是渲染出来再靠二次确认提醒「请自行确认有权」：改错规则的代价是
        所有服务者下个月的分。角色声明进令牌（或有了 `GET /api/v1/admin/me` 之类）之后，这一位自动接通。
      </p>

      <ConsoleListState
        :loading="rules.loading.value"
        :forbidden="rules.forbidden.value"
        :error-message="rules.errorMessage.value"
        :request-id="rules.requestId.value"
        :is-empty="false"
        loading-title="正在加载考核规则"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取考核规则。"
        @retry="load"
      >
        <template v-if="rules.data.value">
          <div class="ph-toolbar">
            <span class="ph-text-weak">最近更新：{{ rules.data.value.updated_at ?? "—" }}</span>
            <span class="ph-toolbar__spacer" />
            <button v-if="isSuperAdmin" type="button" class="ph-button ph-button--primary" @click="openEdit">编辑规则</button>
          </div>

          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>项目</th>
                  <th>权重</th>
                  <th>达标线</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>拉新（INVITE）</td>
                  <td class="ph-table__num">{{ rules.data.value.invite_weight ?? "—" }}%</td>
                  <td class="ph-table__num">{{ targetText(rules.data.value.invite_target) }}</td>
                </tr>
                <tr>
                  <td>券（COUPON）</td>
                  <td class="ph-table__num">{{ rules.data.value.coupon_weight ?? "—" }}%</td>
                  <td class="ph-table__num">{{ targetText(rules.data.value.coupon_target) }}</td>
                </tr>
                <tr>
                  <td>过程（PROCESS）</td>
                  <td class="ph-table__num">{{ rules.data.value.process_weight ?? "—" }}%</td>
                  <td class="ph-table__num">接单响应 ≤ {{ rules.data.value.response_minutes_target ?? "—" }} 分钟记满分</td>
                </tr>
              </tbody>
            </table>
          </div>

          <p class="ph-field__hint ph-assess__note">
            达标线为 0 表示**平台侧还没有这一项的要求**：该维度不参与计分并重算权重（明细里注明「未参与」），
            而不是给它记 0 分（ADR-0050 第四节）。过程分的五个子项（接单响应 / 核销率 / 报工完整率 /
            评价分 / 服务者取消率）由这一条响应时长与订单侧的事实推出，不在本页单独配。
          </p>

          <h4 class="ph-card__title ph-assess__sub">三档阈值与推荐优先级</h4>
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>等级</th>
                  <th>最低总分（闭区间）</th>
                  <th>AI 推荐优先级</th>
                  <th>本档更新时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in levels" :key="item.level ?? 0">
                  <td>{{ item.level_name ?? item.level }}（level={{ item.level }}）</td>
                  <td class="ph-table__num">{{ item.min_score ?? "—" }}</td>
                  <td>{{ priorityLabel(item.recommend_priority) }}</td>
                  <td class="ph-table__num">{{ item.updated_at ?? "—" }}</td>
                </tr>
                <tr v-if="levels.length === 0">
                  <td colspan="4">没有档位数据：契约要求恰好三档（基础 / 优选 / 战略合作）。</td>
                </tr>
              </tbody>
            </table>
          </div>

          <form v-if="editing" class="ph-card ph-assess__form" @submit.prevent="save">
            <h4 class="ph-card__title">修改考核规则</h4>
            <div class="ph-form-grid">
              <label class="ph-field">
                <span class="ph-field__label">拉新权重（%）</span>
                <input v-model="form.inviteWeight" class="ph-input ph-assess__num" inputmode="numeric" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">券权重（%）</span>
                <input v-model="form.couponWeight" class="ph-input ph-assess__num" inputmode="numeric" />
              </label>
              <label class="ph-field">
                <span class="ph-field__label">过程权重（%）</span>
                <input v-model="form.processWeight" class="ph-input ph-assess__num" inputmode="numeric" />
                <span class="ph-field__hint">三项之和必须是 100（后端 40001）</span>
              </label>
              <label class="ph-field">
                <span class="ph-field__label">拉新达标线（有效邀请数）</span>
                <input v-model="form.inviteTarget" class="ph-input ph-assess__num" inputmode="numeric" />
                <span class="ph-field__hint">0 = 该项未配置 → 不参与计分并重算权重</span>
              </label>
              <label class="ph-field">
                <span class="ph-field__label">券达标线（完成率 × 核销数）</span>
                <input v-model="form.couponTarget" class="ph-input ph-assess__num" inputmode="decimal" />
                <span class="ph-field__hint">两位小数字符串，如 0.60；0.00 = 未配置</span>
              </label>
              <label class="ph-field">
                <span class="ph-field__label">接单响应达标线（分钟）</span>
                <input v-model="form.responseMinutesTarget" class="ph-input ph-assess__num" inputmode="numeric" />
                <span class="ph-field__hint">正整数；不超过它记满分，超过按比例扣</span>
              </label>
            </div>

            <h4 class="ph-card__title ph-assess__sub">三档阈值</h4>
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>等级</th>
                    <th>最低总分</th>
                    <th>推荐优先级</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in form.levels" :key="item.level">
                    <td>{{ item.levelName }}（level={{ item.level }}）</td>
                    <td>
                      <input v-model="item.minScore" class="ph-input ph-assess__score" inputmode="decimal" />
                    </td>
                    <td>
                      <select v-model="item.recommendPriority" class="ph-select ph-assess__priority">
                        <option value="1">1 最高</option>
                        <option value="2">2 较高</option>
                        <option value="3">3 普通</option>
                      </select>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="ph-field__hint ph-assess__note">
              基础档必须从 0.00 起、三档严格递增（否则会出现谁都匹配不上的分数段，后端 40001）。
              等级本身不可增删——能改的只有阈值与优先级。
            </p>

            <p v-if="localError" class="ph-alert ph-alert--error ph-assess__alert">{{ localError }}</p>
            <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-assess__alert">
              {{ submit.errorMessage.value }}
              <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
            </p>
            <p v-if="submit.forbidden.value" class="ph-alert ph-alert--error ph-assess__alert">
              服务端按「不是超级管理员」拒绝了这次修改（40300）：门禁是登录域 + 超管名单（ADR-0052）。
            </p>

            <div class="ph-assess__actions">
              <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
                {{ submit.submitting.value ? "保存中…" : "保存规则" }}
              </button>
              <button type="button" class="ph-button ph-button--secondary" @click="editing = false">取消</button>
            </div>
          </form>

          <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-assess__alert">{{ submit.doneMessage.value }}</p>

          <p class="ph-field__hint ph-assess__gap">
            缺口：**覆盖单项分**（超级管理员覆盖拉新 / 券 / 过程三项的得分，理由必填且服务者可见）在契约里是
            `POST /api/v1/admin/assessments/{score_id}/overrides`，它需要一个「考核明细」页承载，本 app 的模块树里
            没有这个页面——所以这里不摆入口。考核列表与明细的只读接口（`/assessments`、`/assessments/{score_id}`）
            已在数据看板用于统计分布。
          </p>
        </template>
      </ConsoleListState>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-assess__gate {
  margin-bottom: var(--ph-space-4);
  max-width: 880px;
}

.ph-assess__note {
  margin: var(--ph-space-3) 0 0;
  max-width: 880px;
}

.ph-assess__sub {
  margin: var(--ph-space-5) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-assess__form {
  margin-top: var(--ph-space-5);
}

.ph-assess__num {
  width: 140px;
}

.ph-assess__score {
  width: 120px;
}

.ph-assess__priority {
  width: 140px;
}

.ph-assess__alert {
  margin-top: var(--ph-space-4);
}

.ph-assess__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

.ph-assess__gap {
  margin-top: var(--ph-space-5);
  max-width: 880px;
}
</style>
