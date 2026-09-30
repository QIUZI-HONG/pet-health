<script setup lang="ts">
/**
 * 今日概览（交付文档 3.2 P019，路由 /b/dashboard）：服务者登录后的第一屏。
 *
 * 验收口径是「每天 5 分钟」（PRD F021）：一屏看清今天要做什么 —— 今日要履约的订单按状态、
 * 待接单的预览、券的额度账、最近一期的考核分。所以这一页是**只读 + 跳转**：
 * 写操作（接单 / 核销 / 取消）只在订单管理一页实现，同一条状态机不做两份实现
 * （两份开始就会长成两种口径：一边按状态禁用按钮、一边让用户点了才吃 40900）。
 *
 * **契约里没有订单统计接口**（`provider.yaml` 没有 `/orders/stats` 这类聚合），所以「今日按状态计数」
 * 是这样拼出来的：对五个状态各请求一次列表、读信封里的 `total`（把 `page_size` 压到 1，
 * 不要拉回不需要的数据）。5 次小请求换准确计数，比在前端把一页 20 条数一遍诚实——后者在订单多时
 * 会安静地给出错的数字。同理，券那一格不做前端合计（合计会随分页而不完整），只列出生效中的贡献。
 *
 * 三个数据块各自独立加载：券接口挂了不该把当天最要紧的待接单一起藏起来（每一格自己显示四态）。
 */
import { computed, onMounted, ref, type Ref } from "vue";
import { RouterLink } from "vue-router";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import { formatAmount, formatDate, isIdentityError, speciesLabel, toApiFailure, todayIso } from "@pet-health/shared";
import { providerApp, type AssessmentRow, type ContributionRow, type OrderRow } from "../api/providerApi";
import { useProviderSession } from "../session";
import { assessmentLevelLabel, assessmentLevelTone, completionRateText, orderStatusLabel, orderStatusTone, scoreText } from "../utils/labels";

const { status } = useProviderSession();

/** 今天（履约日期口径）：概览回答的是「今天要做什么」，所以计数与待接单都按今天筛 */
const today = todayIso();
const todayText = formatDate(today);

interface Block {
  loading: Ref<boolean>;
  error: Ref<string>;
  requestId: Ref<string>;
  forbidden: Ref<boolean>;
  /** 当前在飞的那一次请求：新的一次加载先作废它（见 {@link runBlock}） */
  inFlight: AbortController | null;
}

/** 每一格的三个状态（加载 / 失败 / 无权限）都长一样，所以只有一份实现：分类规则也就能收在一处 */
function useBlock(): Block {
  return { loading: ref(false), error: ref(""), requestId: ref(""), forbidden: ref(false), inFlight: null };
}

/**
 * 跑一次加载：先作废上一轮（点重试时旧响应可能后回来，把新数据盖成旧的），再按结果落状态。
 * 请求带 `signal` 是能作废的前提——`providerApp` 的读接口都收 AbortSignal（与 usePagedList 同一手法）。
 */
async function runBlock(block: Block, task: (signal: AbortSignal) => Promise<void>): Promise<void> {
  block.inFlight?.abort();
  const controller = new AbortController();
  block.inFlight = controller;
  block.loading.value = true;
  block.error.value = "";
  block.requestId.value = "";
  block.forbidden.value = false;
  try {
    await task(controller.signal);
    if (controller.signal.aborted) return;
  } catch (error) {
    if (controller.signal.aborted) return;
    // 身份类错误（40100 / 40101 / 40300）进「无权限」态：重试还是同样的拒绝
    if (isIdentityError(error)) {
      block.forbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "数据加载失败，请稍后重试");
    block.error.value = failure.message;
    block.requestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      block.loading.value = false;
    }
  }
}

// ---- 一、今日订单 ----
const ordersBlock = useBlock();
/** 五个状态按序排列，与服务端的 0–4 一致（`orderStatusLabel` 就是这套码的字典） */
const ORDER_STATUSES = [0, 1, 2, 3, 4] as const;
const counts = ref<Record<number, number>>({});
const pending = ref<OrderRow[]>([]);

async function loadOrders(): Promise<void> {
  await runBlock(ordersBlock, async (signal) => {
    // 计数用 page_size=1：只要信封里的 total，不需要行；预览另发一次（要 5 行）
    const [countResults, preview] = await Promise.all([
      Promise.all(
        ORDER_STATUSES.map((code) =>
          providerApp.listOrders({ status: code, appointmentDate: today, page: 1, pageSize: 1 }, signal),
        ),
      ),
      providerApp.listOrders({ status: 0, appointmentDate: today, page: 1, pageSize: 5 }, signal),
    ]);
    counts.value = Object.fromEntries(ORDER_STATUSES.map((code, index) => [code, countResults[index]?.total ?? 0]));
    pending.value = preview.list;
  });
}

// ---- 二、券（生效中的贡献）----
const couponBlock = useBlock();
const contributions = ref<ContributionRow[]>([]);

async function loadCoupons(): Promise<void> {
  await runBlock(couponBlock, async (signal) => {
    const page = await providerApp.listCouponContributions({ status: 1, page: 1, pageSize: 3 }, signal);
    contributions.value = page.list;
  });
}

// ---- 三、考核（最近一期）----
const assessBlock = useBlock();
const latestAssessment = ref<AssessmentRow | null>(null);

async function loadAssessment(): Promise<void> {
  await runBlock(assessBlock, async (signal) => {
    // 列表按账期倒序（契约），第一条就是最近一期；历史在考核中心翻
    const page = await providerApp.listAssessments({ page: 1, pageSize: 1 }, signal);
    latestAssessment.value = page.list[0] ?? null;
  });
}

const todayTotal = computed(() => ORDER_STATUSES.reduce<number>((sum, code) => sum + (counts.value[code] ?? 0), 0));

function reload(): void {
  void loadOrders();
  void loadCoupons();
  void loadAssessment();
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    reload();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">今日概览</h2>
    <p class="ph-page-desc">
      每天的起点：今天要履约的订单、待接单的预约、券的额度账与最近一期的考核分。动手的地方在「订单管理」与「券管理」，这一页只负责让你 5 分钟看完。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <!-- ============ 今日订单 ============ -->
      <section class="ph-card ph-dash__card">
        <h3 class="ph-card__title">今日订单（{{ todayText }} 履约）</h3>

        <ConsoleState
          v-if="ordersBlock.loading.value"
          variant="loading"
          title="正在统计今日订单"
        />
        <ConsoleState
          v-else-if="ordersBlock.forbidden.value"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店订单。"
        />
        <ConsoleState
          v-else-if="ordersBlock.error.value"
          variant="error"
          :title="ordersBlock.error.value"
          :request-id="ordersBlock.requestId.value"
          @retry="loadOrders"
        />
        <template v-else>
          <p v-if="todayTotal === 0" class="ph-text-sub">
            今天没有要履约的订单（计数按预约日期筛，不含明天与更远的预约）。新的预约会落在这里。
          </p>
          <ul v-else class="ph-dash__stats">
            <li v-for="code in ORDER_STATUSES" :key="code" class="ph-dash__stat">
              <span class="ph-dash__num">{{ counts[code] ?? 0 }}</span>
              <span class="ph-tag" :class="orderStatusTone(code)">{{ orderStatusLabel(code) }}</span>
            </li>
          </ul>

          <h4 class="ph-dash__sub">待接单（最多 5 条）</h4>
          <p v-if="pending.length === 0" class="ph-text-sub">今天没有待接单的预约。</p>
          <div v-else class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>时段</th>
                  <th>客户 / 宠物</th>
                  <th>服务项</th>
                  <th>到店应收</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pending" :key="row.id">
                  <td class="ph-table__num">{{ row.start_time }}–{{ row.end_time }}</td>
                  <td>
                    {{ row.user_nickname ?? "—" }}
                    <span class="ph-text-weak ph-dash__sub-line">
                      {{ row.pet_name ?? "—" }}（{{ speciesLabel(row.pet_species) }}）
                    </span>
                  </td>
                  <td>{{ row.service_name ?? "—" }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.estimated_pay_amount) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-dash__link">
            <RouterLink :to="{ name: 'orders' }">去订单管理接单 / 核销 / 取消 →</RouterLink>
          </p>
          <p class="ph-text-weak">
            计数口径：对五个状态各取一次列表、读信封的 `total`——契约里没有订单统计接口，这是不编接口又拿到准确计数的唯一办法。
          </p>
        </template>
      </section>

      <!-- ============ 券 ============ -->
      <section class="ph-card ph-dash__card">
        <h3 class="ph-card__title">券：生效中的贡献</h3>

        <ConsoleState v-if="couponBlock.loading.value" variant="loading" title="正在加载券贡献" />
        <ConsoleState
          v-else-if="couponBlock.forbidden.value"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店的券贡献。"
        />
        <ConsoleState
          v-else-if="couponBlock.error.value"
          variant="error"
          :title="couponBlock.error.value"
          :request-id="couponBlock.requestId.value"
          @retry="loadCoupons"
        />
        <template v-else>
          <p v-if="contributions.length === 0" class="ph-text-sub">
            还没有生效中的券贡献。去「券管理 → 券池模板」挑一张服务者成本的券，承诺一个可核销额度。
          </p>
          <div v-else class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>券</th>
                  <th>可发放</th>
                  <th>已核销</th>
                  <th>完成率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in contributions" :key="row.id">
                  <td>
                    {{ row.template_name ?? row.template_code }}
                    <span class="ph-text-weak ph-dash__sub-line">面额 {{ formatAmount(row.face_value) }}</span>
                  </td>
                  <td class="ph-table__num">{{ row.available_count ?? 0 }} / {{ row.total_count }}</td>
                  <td class="ph-table__num">{{ row.redeemed_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ completionRateText(row.completion_rate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-dash__link"><RouterLink :to="{ name: 'coupons' }">去券管理调整额度 / 看明细 →</RouterLink></p>
          <p class="ph-text-weak">
            这里只列生效中的前几条，不做前端合计：券的接口给的是逐条的额度账，没有汇总接口，合计会随分页而不完整。
          </p>
        </template>
      </section>

      <!-- ============ 考核 ============ -->
      <section class="ph-card ph-dash__card">
        <h3 class="ph-card__title">考核：最近一期</h3>

        <ConsoleState v-if="assessBlock.loading.value" variant="loading" title="正在加载考核" />
        <ConsoleState
          v-else-if="assessBlock.forbidden.value"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店的考核记录。"
        />
        <ConsoleState
          v-else-if="assessBlock.error.value"
          variant="error"
          :title="assessBlock.error.value"
          :request-id="assessBlock.requestId.value"
          @retry="loadAssessment"
        />
        <template v-else>
          <p v-if="!latestAssessment" class="ph-text-sub">
            还没有考核记录：每月 1 日算上一个自然月，算出来之后这里会显示总分与等级。
          </p>
          <p v-else class="ph-dash__assess">
            <span class="ph-dash__num">{{ scoreText(latestAssessment.total_score) }}</span>
            <span class="ph-tag" :class="assessmentLevelTone(latestAssessment.level)">
              {{ assessmentLevelLabel(latestAssessment) }}
            </span>
            <span class="ph-text-sub">账期 {{ latestAssessment.period }}</span>
            <span v-if="latestAssessment.overridden" class="ph-tag ph-tag--warning">有单项分被覆盖</span>
          </p>
          <p class="ph-dash__link"><RouterLink :to="{ name: 'assessment' }">去考核中心看三项明细 →</RouterLink></p>
          <p class="ph-text-weak">总分 = 拉新 40% + 券 40% + 过程 20%；等级决定 AI 推荐优先级，缺项会在明细里注明。</p>
        </template>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-dash__card {
  margin-bottom: var(--ph-space-5);
}

.ph-dash__stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-4);
  margin: 0 0 var(--ph-space-4);
  padding: 0;
  list-style: none;
}

.ph-dash__stat {
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-2);
}

.ph-dash__num {
  font-family: var(--ph-font-numeric);
  font-size: 22px;
  font-weight: 600;
}

.ph-dash__sub {
  margin: 0 0 var(--ph-space-2);
  font-size: 13px;
  font-weight: 600;
}

.ph-dash__sub-line {
  display: block;
  font-size: 12px;
}

.ph-dash__link {
  margin: var(--ph-space-3) 0 0;
}

.ph-dash__assess {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ph-space-3);
  margin: 0;
}
</style>
