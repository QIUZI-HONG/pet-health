<script setup lang="ts">
/**
 * 数据看板（交付文档 3.2 P036，路由 /admin/stats）：全平台数据。
 *
 * 与服务者后台的同名页面区别在**范围**：那边只看自己一家，这边看全平台。
 *
 * **这一屏只放契约里真有口径的数字**，每一格都标着它的来源接口（写在分组小标题上），
 * 不合成「平台总用户数」这类不存在的指标——那种数字最危险，因为没人能从接口倒推出它是怎么算的。
 * 所以这里没有用户数、没有订单数、没有 GMV：admin.yaml 里没有对应的聚合接口（缺口写在本页末尾）。
 *
 * 考核分布的做法值得说一句：契约没有「按等级计数」的接口，但分页信封的 `total` 是
 * **满足条件的总数**（不是这一页的条数），所以「带 `level=N` 各查一次、每页只要 1 条」就能拿到
 * 三档的人数——四个小请求，不必把全平台的行拉回来在浏览器里数（那既是 N 倍的流量，也是个会随
 * 数据长大而变慢的做法）。
 *
 * 数字口径按 docs/conventions.md：时区 Asia/Shanghai、金额两位小数带 ¥（本页的券面额走 formatAmount）。
 * 券池里的额度类数字是**张数**不是金额（ADR-0036：平台不经手资金），所以它们不带 ¥。
 */
import { computed, onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState } from "@pet-health/ui";
import { adminApp } from "../api/adminApi";
import { useSection } from "../composables/useSection";
import { useAdminSession } from "../session";

const { status } = useAdminSession();

/** 考核分布的账期筛选：不传 = 全部账期（口径见分组提示） */
const period = ref("");

/** 券池：GET /api/v1/admin/coupon-pool/overview */
const pool = useSection<Awaited<ReturnType<typeof adminApp.getCouponPoolOverview>>>();
/** 邀请漏斗：GET /api/v1/admin/invites/overview */
const invites = useSection<Awaited<ReturnType<typeof adminApp.getInviteOverview>>>();
/** 积分：GET /api/v1/admin/points/overview */
const pointsOverview = useSection<Awaited<ReturnType<typeof adminApp.getPointsOverview>>>();

/** 考核分布：GET /api/v1/admin/assessments（四个请求，见文件头） */
interface ExamDistribution {
  total: number;
  byLevel: { level: number; name: string; count: number }[];
}
const exams = useSection<ExamDistribution>();

const examLevels = computed(() => exams.data.value?.byLevel ?? []);

async function loadPool(): Promise<void> {
  await pool.load(() => adminApp.getCouponPoolOverview(), "券池数据加载失败，请稍后重试");
}

async function loadInvites(): Promise<void> {
  await invites.load(() => adminApp.getInviteOverview(), "邀请漏斗加载失败，请稍后重试");
}

async function loadPoints(): Promise<void> {
  await pointsOverview.load(() => adminApp.getPointsOverview(), "积分总览加载失败，请稍后重试");
}

async function loadExams(): Promise<void> {
  const periodFilter = period.value.trim() || undefined;
  await exams.load(async () => {
    // 三档各查一次 page_size=1，只读信封里的 total ——「满足条件的总数」，不是这一页的条数
    const [all, basic, preferred, strategic] = await Promise.all([
      adminApp.listAssessments({ period: periodFilter, page: 1, pageSize: 1 }),
      adminApp.listAssessments({ period: periodFilter, level: 1, page: 1, pageSize: 1 }),
      adminApp.listAssessments({ period: periodFilter, level: 2, page: 1, pageSize: 1 }),
      adminApp.listAssessments({ period: periodFilter, level: 3, page: 1, pageSize: 1 }),
    ]);
    return {
      total: all.total,
      byLevel: [
        { level: 1, name: "基础", count: basic.total },
        { level: 2, name: "优选", count: preferred.total },
        { level: 3, name: "战略合作", count: strategic.total },
      ],
    };
  }, "考核分布加载失败，请稍后重试");
}

function loadAll(): void {
  void loadPool();
  void loadInvites();
  void loadPoints();
  void loadExams();
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    loadAll();
  }
});

function ladderText(): string {
  const stats = invites.data.value?.ladder_stats ?? [];
  if (stats.length === 0) return "还没有人达成任何档位";
  return stats.map((item) => `${item.threshold} 人档：${item.achieved_count ?? 0} 人`).join(" · ");
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">数据看板</h2>
    <p class="ph-page-desc">
      全平台的只读指标：券池的账、邀请漏斗、积分总览、服务者考核分布。每一格的来源接口写在分组标题上——这一屏没有用户数、订单数与金额流水，因为契约里没有它们的聚合口径（缺口见页面末尾）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-stats__filter">
          <span class="ph-field__label">考核账期</span>
          <input v-model="period" class="ph-input ph-stats__period" placeholder="yyyy-MM，留空 = 全部账期" @keyup.enter="loadExams" />
        </label>
        <button type="button" class="ph-button ph-button--secondary" @click="loadAll">刷新全部</button>
      </div>

      <!-- 券池（来源：GET /api/v1/admin/coupon-pool/overview） -->
      <h4 class="ph-card__title ph-stats__sub">
        券池
        <span class="ph-text-weak">来源：GET /api/v1/admin/coupon-pool/overview</span>
      </h4>
      <ConsoleListState
        :loading="pool.loading.value"
        :forbidden="pool.forbidden.value"
        :error-message="pool.errorMessage.value"
        :request-id="pool.requestId.value"
        :is-empty="false"
        loading-title="正在加载券池数据"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取券池总览。"
        @retry="loadPool"
      >
        <div v-if="pool.data.value" class="ph-stats__grid">
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">模板总数 / 启用中</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.template_count ?? 0 }} / {{ pool.data.value.template_active_count ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">已发放券（张）</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.issued_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">已核销（张）</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.redeemed_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">在用户手里（张）</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.reserved_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">已过期作废（张）</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.expired_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">服务者承诺额度（张）</span>
            <span class="ph-stats__stat-value">{{ pool.data.value.committed_total ?? 0 }}</span>
            <span class="ph-field__hint">还可发 {{ pool.data.value.available_total ?? 0 }} 张 · 生效贡献 {{ pool.data.value.contribution_count ?? 0 }} 条</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">对账</span>
            <span class="ph-tag" :class="pool.data.value.reconciliation?.balanced ? 'ph-tag--success' : 'ph-tag--danger'">
              {{ pool.data.value.reconciliation?.balanced ? "平" : "不平，要查" }}
            </span>
            <span class="ph-field__hint">实例数 = 已核销 + 在用户手里 + 已过期（ADR-0037 第三节）</span>
          </div>
        </div>
        <p class="ph-field__hint ph-stats__note">
          这里的额度与张数是**张数不是金额**：成本归属只影响核销统计与考核，不产生资金（ADR-0036）。
          券面额（元）在券实例与模板上，走 ¥ 展示。
        </p>
      </ConsoleListState>

      <!-- 邀请漏斗（来源：GET /api/v1/admin/invites/overview） -->
      <h4 class="ph-card__title ph-stats__sub">
        邀请漏斗
        <span class="ph-text-weak">来源：GET /api/v1/admin/invites/overview</span>
      </h4>
      <ConsoleListState
        :loading="invites.loading.value"
        :forbidden="invites.forbidden.value"
        :error-message="invites.errorMessage.value"
        :request-id="invites.requestId.value"
        :is-empty="false"
        loading-title="正在加载邀请漏斗"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取邀请总览。"
        @retry="loadInvites"
      >
        <div v-if="invites.data.value" class="ph-stats__grid">
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">已生成的邀请码</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.invite_code_count ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">通过邀请码注册</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.registered_count ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">待生效</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.pending_count ?? 0 }}</span>
            <span class="ph-field__hint">已注册未结算</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">有效邀请</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.effective_count ?? 0 }}</span>
            <span class="ph-field__hint">被邀请人完成建档 + 24 小时内有行为</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">无效邀请</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.invalid_count ?? 0 }}</span>
            <span class="ph-field__hint">反作弊拦下或 24 小时无行为</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">有效邀请转化率</span>
            <span class="ph-stats__stat-value">{{ invites.data.value.valid_rate ?? "—" }}</span>
            <span class="ph-field__hint">有效 ÷ 注册；它替代了交付文档的 K 因子（ADR-0039）</span>
          </div>
          <div class="ph-card ph-stats__stat ph-stats__stat--wide">
            <span class="ph-stats__stat-label">阶梯达成</span>
            <span class="ph-stats__stat-detail">{{ ladderText() }}</span>
            <span class="ph-field__hint">档位门槛是固定的五档（1 / 3 / 5 / 10 / 15），运营能改的是每档发什么</span>
          </div>
        </div>
      </ConsoleListState>

      <!-- 积分（来源：GET /api/v1/admin/points/overview） -->
      <h4 class="ph-card__title ph-stats__sub">
        积分
        <span class="ph-text-weak">来源：GET /api/v1/admin/points/overview</span>
      </h4>
      <ConsoleListState
        :loading="pointsOverview.loading.value"
        :forbidden="pointsOverview.forbidden.value"
        :error-message="pointsOverview.errorMessage.value"
        :request-id="pointsOverview.requestId.value"
        :is-empty="false"
        loading-title="正在加载积分总览"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取积分总览。"
        @retry="loadPoints"
      >
        <div v-if="pointsOverview.data.value" class="ph-stats__grid">
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">有积分账户的用户</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.account_count ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">当前余额合计</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.balance_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">累计发放</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.earned_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">累计消耗（兑换）</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.spent_total ?? 0 }}</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">今日发放</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.today_earned ?? 0 }}</span>
            <span class="ph-field__hint">东八区业务日；上限 {{ pointsOverview.data.value.daily_earn_limit ?? 0 }} 分</span>
          </div>
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">今日获得过分的用户</span>
            <span class="ph-stats__stat-value">{{ pointsOverview.data.value.today_awarded_users ?? 0 }}</span>
            <span class="ph-field__hint">盯每日上限的实际命中情况（上限是被刷的重点）</span>
          </div>
        </div>
      </ConsoleListState>

      <!-- 考核分布（来源：GET /api/v1/admin/assessments） -->
      <h4 class="ph-card__title ph-stats__sub">
        服务者考核分布
        <span class="ph-text-weak">来源：GET /api/v1/admin/assessments（按 level 过滤读 total）</span>
      </h4>
      <ConsoleListState
        :loading="exams.loading.value"
        :forbidden="exams.forbidden.value"
        :error-message="exams.errorMessage.value"
        :request-id="exams.requestId.value"
        :is-empty="false"
        loading-title="正在加载考核分布"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取考核列表。"
        @retry="loadExams"
      >
        <div v-if="exams.data.value" class="ph-stats__grid">
          <div class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">考核记录总数</span>
            <span class="ph-stats__stat-value">{{ exams.data.value.total }}</span>
            <span class="ph-field__hint">{{ period.trim() === "" ? "全部账期" : `${period.trim()} 账期` }}</span>
          </div>
          <div v-for="item in examLevels" :key="item.level" class="ph-card ph-stats__stat">
            <span class="ph-stats__stat-label">{{ item.name }}档（level={{ item.level }}）</span>
            <span class="ph-stats__stat-value">{{ item.count }}</span>
            <span class="ph-field__hint">只按等级计数</span>
          </div>
        </div>
        <p class="ph-field__hint ph-stats__note">
          只有**已经算过的账期**才有行（每月 1 日算上月），所以「0」的常见原因是这个账期还没算。
          分档阈值与推荐优先级在「考核规则配置」页；推荐优先级只存映射结果，推荐逻辑不在本切片。
        </p>
      </ConsoleListState>

      <div class="ph-card ph-stats__gap">
        <h4 class="ph-card__title">这一屏没有的指标（缺口）</h4>
        <p>
          订单与履约、AI 用量与降级次数、平台的用户总数与注册趋势——**契约里没有这些聚合口径**，
          所以这一屏不编：造一个说不清来源的数字，比留一格空白危险得多。
        </p>
        <ul class="ph-stats__gaps">
          <li>订单 / 履约：admin.yaml 里没有订单相关的路径（订单在 ph-order，管理端的聚合未进契约）。</li>
          <li>
            AI 用量与降级：只有抽检明细 <code>/ai/consults</code>（可按 <code>degraded</code> 过滤数条数），
            它的口径是「留痕条数」而不是咨询总量，也不含预算消耗——等聚合接口进契约再补。
          </li>
          <li>用户总数 / 新增：没有用户资源（没有 `/users`），见「用户管理」页的文件头。</li>
        </ul>
      </div>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-stats__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-stats__period {
  width: 240px;
}

.ph-stats__sub {
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-3);
  margin: var(--ph-space-6) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}

.ph-stats__sub .ph-text-weak {
  font-size: 12px;
  font-weight: 400;
}

.ph-stats__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ph-space-4);
}

.ph-stats__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-stats__stat--wide {
  grid-column: span 2;
}

.ph-stats__stat-label {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-stats__stat-value {
  font-family: var(--ph-font-numeric);
  font-size: 24px;
  font-weight: 600;
}

.ph-stats__stat-detail {
  font-size: 13px;
}

.ph-stats__note {
  margin: var(--ph-space-4) 0 0;
  max-width: 880px;
}

.ph-stats__gap {
  margin-top: var(--ph-space-6);
}

.ph-stats__gaps {
  margin: var(--ph-space-2) 0 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 1.9;
  color: var(--ph-color-text-sub);
}

code {
  font-family: var(--ph-font-numeric);
}
</style>
