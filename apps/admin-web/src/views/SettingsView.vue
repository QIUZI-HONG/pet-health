<script setup lang="ts">
/**
 * 系统配置（交付文档 3.1 模块树；ADR-0010 把「AI 的业务可调项」指到了各自的模块页）。
 *
 * **本页是只读快照，这是有意的**（不是「还没做」）：可写项分散在各自的模块页——考核规则在
 * 「考核规则配置」、券模板在「券池管理」、AI 提示词 / 红线词 / 分级规则 / 护栏词 / 运行时开关在
 * 「AI 运营」、积分上限 / 行为分值 / 兑换档位 / 月度阶梯 / 邀请阶梯在「积分与邀请配置」、
 * 敏感词在「内容审核」。把写入口再搬一份到这里，等于同一份配置有两个可写入口：改错一次要查两个
 * 地方，而「谁改的」这个问题在两处各留一份流水。
 *
 * 这一页回答的是**出事时第一个要问的问题**：现在是哪一版提示词、灰度多少、哪个开关是开的、
 * 积分上限是多少、考核规则的三项权重各是多少。所以它只读、只汇总，并把每一项指到能改它的地方。
 *
 * 技术参数（API Key、base-url、模型名、超时、日预算）**不进这一页**：它们在环境变量里（ADR-0010
 * 第一层），归部署者——让运营在界面上随手改模型名或超时，出事时无法归因。
 */
import { onMounted } from "vue";
import { ConsoleGate, ConsoleListState } from "@pet-health/ui";
import { adminApp } from "../api/adminApi";
import { useSection } from "../composables/useSection";
import { useAdminSession } from "../session";

const { status } = useAdminSession();

/** 考核规则：GET /api/v1/admin/assessments/rules */
const rules = useSection<Awaited<ReturnType<typeof adminApp.getAssessmentRules>>>();
/** 积分规则：GET /api/v1/admin/points/settings */
const pointsSettings = useSection<Awaited<ReturnType<typeof adminApp.getPointsSettings>>>();
/** 券池状态：GET /api/v1/admin/coupon-pool/overview（只看模板计数，见下方注释） */
const pool = useSection<Awaited<ReturnType<typeof adminApp.getCouponPoolOverview>>>();

/** AI 可调项：GET /api/v1/admin/ai/switches + GET /api/v1/admin/ai/prompts */
interface AiConfig {
  prompts: Awaited<ReturnType<typeof adminApp.listAiPrompts>>;
  switches: Awaited<ReturnType<typeof adminApp.listAiSwitches>>;
}
const ai = useSection<AiConfig>();

async function loadRules(): Promise<void> {
  await rules.load(() => adminApp.getAssessmentRules(), "考核规则加载失败，请稍后重试");
}

async function loadPointsSettings(): Promise<void> {
  await pointsSettings.load(() => adminApp.getPointsSettings(), "积分规则加载失败，请稍后重试");
}

async function loadPool(): Promise<void> {
  await pool.load(() => adminApp.getCouponPoolOverview(), "券池状态加载失败，请稍后重试");
}

async function loadAi(): Promise<void> {
  await ai.load(async () => {
    // 两个请求一起取：它们同属「AI 可调项」，分开渲染只会多两块四态
    const [prompts, switches] = await Promise.all([adminApp.listAiPrompts(), adminApp.listAiSwitches()]);
    return { prompts, switches };
  }, "AI 可调项加载失败，请稍后重试");
}

function loadAll(): void {
  void loadRules();
  void loadPointsSettings();
  void loadPool();
  void loadAi();
}

/** 提示词只展示「当前生效的那一版」摘要：正文（system_prompt）很长，看版本与灰度才是这一页的目的 */
function promptSummary(): string {
  const prompts = ai.data.value?.prompts ?? [];
  if (prompts.length === 0) return "还没有入库的提示词版本";
  const enabled = prompts.filter((item) => item.enabled);
  if (enabled.length === 0) return `${prompts.length} 个版本，当前没有启用中的版本（管线会回落到代码基线）`;
  return enabled.map((item) => `${item.code} ${item.version}（灰度 ${item.gray_ratio ?? 0}%）`).join(" · ");
}

function reviewStatusLabel(value?: string): string {
  return value === "vetted" ? "已复核" : "待复核";
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    loadAll();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">系统配置</h2>
    <p class="ph-page-desc">
      当前生效的关键配置一览（只读）：考核规则、积分规则、券池状态，以及 AI 运营可调项的开关与提示词版本。每一块都写明去哪个模块页改。技术参数（模型名、超时、日预算、API Key）在环境变量里，不在这一页——那些改错一次会全线超时。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <p class="ph-alert ph-alert--info ph-set__notice">
        <strong>本页是只读快照</strong>：可写项分散在各自的模块页——AI 提示词 / 红线词 / 分级规则 /
        护栏词 / 运行时开关 → 「AI 运营」；积分上限 / 行为分值 / 兑换档位 / 月度阶梯 / 邀请阶梯 →
        「积分与邀请配置」；考核规则 → 「考核规则配置」；券模板 → 「券池管理」；机审词表 → 「内容审核」。
        把写入口再搬一份到这里，会让同一份配置有两个可写入口、两份流水，所以这里只汇总 + 指向。
      </p>

      <div class="ph-toolbar">
        <button type="button" class="ph-button ph-button--secondary" @click="loadAll">刷新</button>
        <span class="ph-toolbar__spacer" />
        <span class="ph-text-weak">只读</span>
      </div>

      <!-- 考核规则（来源：GET /api/v1/admin/assessments/rules） -->
      <h4 class="ph-card__title ph-set__sub">
        考核规则
        <span class="ph-text-weak">来源：GET /api/v1/admin/assessments/rules</span>
      </h4>
      <ConsoleListState
        :loading="rules.loading.value"
        :forbidden="rules.forbidden.value"
        :error-message="rules.errorMessage.value"
        :request-id="rules.requestId.value"
        :is-empty="false"
        loading-title="正在加载考核规则"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取考核规则。"
        @retry="loadRules"
      >
        <dl v-if="rules.data.value" class="ph-kv">
          <dt>三项权重</dt>
          <dd>
            拉新 {{ rules.data.value.invite_weight ?? "—" }}% · 券 {{ rules.data.value.coupon_weight ?? "—" }}% ·
            过程 {{ rules.data.value.process_weight ?? "—" }}%
          </dd>
          <dt>达标线</dt>
          <dd>
            拉新 {{ rules.data.value.invite_target ?? "—" }} 人 · 券 {{ rules.data.value.coupon_target ?? "—" }} ·
            接单响应 ≤ {{ rules.data.value.response_minutes_target ?? "—" }} 分钟
          </dd>
          <dt>三档阈值</dt>
          <dd>
            {{ (rules.data.value.levels ?? []).map((item) => `${item.level_name ?? item.level} ≥ ${item.min_score ?? "—"}（优先级 ${item.recommend_priority ?? "—"}）`).join(" · ") || "—" }}
          </dd>
          <dt>最近更新</dt>
          <dd>{{ rules.data.value.updated_at ?? "—" }}</dd>
        </dl>
        <p class="ph-field__hint ph-set__note">
          修改在「考核规则配置」页：那里是完整表单，并且按 ADR-0037 的矩阵**只对超级管理员开放写入口**。
        </p>
      </ConsoleListState>

      <!-- 积分规则（来源：GET /api/v1/admin/points/settings） -->
      <h4 class="ph-card__title ph-set__sub">
        积分规则
        <span class="ph-text-weak">来源：GET /api/v1/admin/points/settings</span>
      </h4>
      <ConsoleListState
        :loading="pointsSettings.loading.value"
        :forbidden="pointsSettings.forbidden.value"
        :error-message="pointsSettings.errorMessage.value"
        :request-id="pointsSettings.requestId.value"
        :is-empty="false"
        loading-title="正在加载积分规则"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取积分规则设置。"
        @retry="loadPointsSettings"
      >
        <dl v-if="pointsSettings.data.value" class="ph-kv">
          <dt>每日获取上限</dt>
          <dd>{{ pointsSettings.data.value.daily_earn_limit ?? "—" }} 分</dd>
        </dl>
        <p class="ph-field__hint ph-set__note">
          邀请与一次性项**不占**这个上限（由各自行为的 `counts_toward_daily_cap` 决定，ADR-0038 第四节）——
          否则 20 分的邀请奖励会被日上限吃掉。修改与行为分值、兑换档位、两处阶梯都在
          「积分与邀请配置」页。
        </p>
      </ConsoleListState>

      <!-- 券池状态（来源：GET /api/v1/admin/coupon-pool/overview） -->
      <h4 class="ph-card__title ph-set__sub">
        券池状态
        <span class="ph-text-weak">来源：GET /api/v1/admin/coupon-pool/overview</span>
      </h4>
      <ConsoleListState
        :loading="pool.loading.value"
        :forbidden="pool.forbidden.value"
        :error-message="pool.errorMessage.value"
        :request-id="pool.requestId.value"
        :is-empty="false"
        loading-title="正在加载券池状态"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取券池总览。"
        @retry="loadPool"
      >
        <dl v-if="pool.data.value" class="ph-kv">
          <dt>券模板</dt>
          <dd>
            共 {{ pool.data.value.template_count ?? 0 }} 个，启用中 {{ pool.data.value.template_active_count ?? 0 }} 个
          </dd>
        </dl>
        <p class="ph-field__hint ph-set__note">
          **没有「券池总开关」这个实体**：券的启停是模板级的（在「券池管理」逐条启停），这里给的是两个计数。
          之所以不摆一个总开关，是因为契约里没有它——一个什么都拦得住的开关如果只是前端的概念，那它是假的。
        </p>
      </ConsoleListState>

      <!-- AI 可调项（来源：GET /api/v1/admin/ai/switches、GET /api/v1/admin/ai/prompts） -->
      <h4 class="ph-card__title ph-set__sub">
        AI 可调项（运行时开关与提示词版本）
        <span class="ph-text-weak">来源：GET /api/v1/admin/ai/switches、GET /api/v1/admin/ai/prompts</span>
      </h4>
      <ConsoleListState
        :loading="ai.loading.value"
        :forbidden="ai.forbidden.value"
        :error-message="ai.errorMessage.value"
        :request-id="ai.requestId.value"
        :is-empty="false"
        loading-title="正在加载 AI 可调项"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取 AI 开关与提示词版本。"
        @retry="loadAi"
      >
        <template v-if="ai.data.value">
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>运行时开关</th>
                  <th>当前状态</th>
                  <th>含义</th>
                  <th>更新时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in ai.data.value.switches" :key="item.code">
                  <td class="ph-table__num">{{ item.code }}</td>
                  <td>
                    <span class="ph-tag" :class="item.enabled ? 'ph-tag--warning' : ''">
                      {{ item.enabled ? "打开" : "关闭" }}
                    </span>
                  </td>
                  <td>{{ item.remark ?? "—" }}</td>
                  <td class="ph-table__num">{{ item.updated_at ?? "—" }}</td>
                </tr>
                <tr v-if="ai.data.value.switches.length === 0">
                  <td colspan="4">开关列表为空：库里读不到时一律按关闭算（ADR-0033 第三节）。</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-field__hint ph-set__note">
            开关的**语义在代码里**，库里只存它的当前状态（ADR-0010）；`force_rule_only` 打开后全量走规则通道
            （不调模型），两个检索开关控制知识检索的开关与严格口径。缺省一律按关闭算。
            切换在「AI 运营」页（那里每个开关都要二次确认）。
          </p>

          <div class="ph-table-wrap ph-set__prompts">
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
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in ai.data.value.prompts" :key="item.id">
                  <td class="ph-table__num">{{ item.code }}</td>
                  <td class="ph-table__num">{{ item.version }}</td>
                  <td class="ph-table__num">{{ item.gray_ratio ?? 0 }}%</td>
                  <td>
                    <span class="ph-tag" :class="item.enabled ? 'ph-tag--success' : 'ph-tag--warning'">
                      {{ item.enabled ? "启用" : "停用" }}
                    </span>
                  </td>
                  <td>{{ reviewStatusLabel(item.review_status) }}</td>
                  <td class="ph-table__num">{{ item.updated_by ?? "—" }}</td>
                  <td class="ph-table__num">{{ item.updated_at ?? "—" }}</td>
                </tr>
                <tr v-if="ai.data.value.prompts.length === 0">
                  <td colspan="7">还没有入库的提示词版本：管线此时用代码里的基线提示词。</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-field__hint ph-set__note">
            当前生效：{{ promptSummary() }}。改动要**新增版本号**、不要原地改（留痕按 `prompt_version` 归因分级漂移，
            ADR-0010）；出问题把灰度改回 0 就是回滚，不必发版。`review_status` 是人工复核的产物，
            **接口不给改**（代码里开个后门等于假造复核状态，ADR-0040 第二节）。
            新建版本、调灰度、红线词 / 分级规则 / 护栏词的增改都在「AI 运营」页。
          </p>
        </template>
      </ConsoleListState>

      <div class="ph-card ph-set__gap">
        <h4 class="ph-card__title">写入口在哪一页</h4>
        <ul class="ph-set__gaps">
          <li>
            **AI 运营**（`/admin/ai-ops`）：提示词版本（新建版本 / 调灰度 / 停启用）、红线词（增改停用）、
            分级规则、护栏词、运行时开关（二次确认）。
          </li>
          <li>
            **积分与邀请配置**（`/admin/points-invite`）：每日获取上限、行为分值（分值 / 频次 / 启停，
            行为码不可新增）、兑换档位（只兑平台补贴券）、月度阶梯档位、邀请阶梯奖励（门槛固定五档）。
            档位与奖励**不预置任何数值**（ADR-0046：没有规则依据，不编），由运营填。
          </li>
          <li>
            **仍然没有承载页的接口**（这一波不接）：AI 调用留痕抽检（`GET /ai/consults`，
            它是排查面且不含问题原文）、反作弊记录（`GET /invites/risk-records`）、
            权益码与手动授予（`POST /rights/codes`、`POST|DELETE /rights/grants`）、
            积分任务（`/points/tasks`）——需要时各自开页，而不是把按钮摆在本页。
          </li>
          <li>
            技术参数（API Key、base-url、模型名、超时、日预算）**永远不进本页**：它们在环境变量里，
            属于部署者（ADR-0010 的第一层）。
          </li>
        </ul>
      </div>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-set__notice {
  margin-bottom: var(--ph-space-4);
  max-width: 880px;
}

.ph-set__sub {
  margin: var(--ph-space-6) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-set__sub .ph-text-weak {
  font-size: 12px;
  font-weight: 400;
}

.ph-set__note {
  margin: var(--ph-space-3) 0 0;
  max-width: 880px;
}

.ph-set__prompts {
  margin-top: var(--ph-space-4);
}

.ph-set__gap {
  margin-top: var(--ph-space-6);
}

.ph-set__gaps {
  margin: var(--ph-space-2) 0 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 1.9;
  color: var(--ph-color-text-sub);
}
</style>
