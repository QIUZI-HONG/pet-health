<script setup lang="ts">
/**
 * 数据看板（交付文档 3.2 P025，路由 /b/stats）：经营数据的趋势与结构。
 *
 * **契约里没有经营统计接口**（没有 `/orders/stats`，也没有「核销率」这类聚合）。所以这一页只呈现
 * **真实存在**的三类数据，并把每一类的口径写在它自己那一格上——数字旁边不写口径，读的人会以为
 * 它是「按周聚合的经营报表」：
 *   1. **订单量**：对五个状态各取一次列表、读信封里的 `total`（可选按履约日期筛某一天）。
 *      「日」这一档能做（`appointment_date` 是单日参数），**「周 / 月」做不了**——契约没有区间参数，
 *      前端不自己拼区间（拼出来的是假趋势）；
 *   2. **过程分指标**（接单响应 / 核销率 / 报工完整率 / 评价分 / 服务者取消率）：它们是考核明细里
 *      过程分的五个子项，每项带 `raw_value`（人话的原始值）、`target_value`（达标线快照）与
 *      `data_source`（这条事实从哪来）。**直接读那一份**，不在前端另算一遍：两处各算一遍必然分叉，
 *      而分叉之后没人知道该信哪个；
 *   3. **券**：来自券贡献的额度账（`completion_rate` 是契约给的现成值）。
 *
 * 数据来源与考核中心是同一份接口，所以这一页**没有「日 / 周 / 月」切换**：那不是 UI 偷懒，
 * 是后端还没有按区间聚合的接口（缺口写在页面上）。
 */
import { computed, onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, ConsoleState, usePagedList } from "@pet-health/ui";
import { formatDate, isIdentityError, toApiFailure, todayIso } from "@pet-health/shared";
import { providerApp, type AssessmentItemView, type ContributionRow } from "../api/providerApi";
import { useProviderSession } from "../session";
import { assessmentItemLabel, completionRateText, orderStatusLabel, orderStatusTone, scoreText } from "../utils/labels";

const { status } = useProviderSession();

// ---- 一、订单量（按状态；可选某一天）----
const ORDER_STATUSES = [0, 1, 2, 3, 4] as const;
/** 空 = 累计（本店全部订单）；填了日期 = 只数那一天要履约的 */
const dateFilter = ref("");
const counts = ref<Record<number, number>>({});
const countsLoading = ref(false);
const countsError = ref("");
const countsRequestId = ref("");
const countsForbidden = ref(false);

const countsTotal = computed(() => ORDER_STATUSES.reduce<number>((sum, code) => sum + (counts.value[code] ?? 0), 0));

/** 在飞的那一次：换日期/点重试时先作废它（旧响应后回来会把新结果盖掉，与 usePagedList 同一手法） */
let countsInFlight: AbortController | null = null;

async function loadCounts(): Promise<void> {
  countsInFlight?.abort();
  const controller = new AbortController();
  countsInFlight = controller;
  countsLoading.value = true;
  countsError.value = "";
  countsRequestId.value = "";
  countsForbidden.value = false;
  const appointmentDate = dateFilter.value === "" ? undefined : dateFilter.value;
  try {
    const results = await Promise.all(
      ORDER_STATUSES.map((code) =>
        providerApp.listOrders({ status: code, appointmentDate, page: 1, pageSize: 1 }, controller.signal),
      ),
    );
    if (controller.signal.aborted) return;
    counts.value = Object.fromEntries(ORDER_STATUSES.map((code, index) => [code, results[index]?.total ?? 0]));
  } catch (error) {
    if (controller.signal.aborted) return;
    if (isIdentityError(error)) {
      countsForbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "订单量加载失败，请稍后重试");
    countsError.value = failure.message;
    countsRequestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      countsLoading.value = false;
    }
  }
}

// ---- 二、过程分指标（最近一期的考核明细）----
const processItems = ref<AssessmentItemView[]>([]);
const processPeriod = ref("");
const processLoading = ref(false);
const processError = ref("");
const processRequestId = ref("");
const processForbidden = ref(false);

/** 同上：过程指标要拉两次（列表 + 明细），作废时两次一起作废 */
let processInFlight: AbortController | null = null;

async function loadProcess(): Promise<void> {
  processInFlight?.abort();
  const controller = new AbortController();
  processInFlight = controller;
  processLoading.value = true;
  processError.value = "";
  processRequestId.value = "";
  processForbidden.value = false;
  try {
    const page = await providerApp.listAssessments({ page: 1, pageSize: 1 }, controller.signal);
    if (controller.signal.aborted) return;
    const latest = page.list[0];
    if (!latest) {
      processItems.value = [];
      processPeriod.value = "";
      return;
    }
    processPeriod.value = latest.period;
    const detail = await providerApp.getAssessment(latest.period, controller.signal);
    if (controller.signal.aborted) return;
    processItems.value = (detail.items ?? []).filter((item) => item.parent_code === "PROCESS");
  } catch (error) {
    if (controller.signal.aborted) return;
    if (isIdentityError(error)) {
      processForbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "过程分指标加载失败，请稍后重试");
    processError.value = failure.message;
    processRequestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      processLoading.value = false;
    }
  }
}

// ---- 三、券（逐条的额度账）----
const contributions = usePagedList<ContributionRow>(
  ({ page, pageSize }, signal) => providerApp.listCouponContributions({ page, pageSize }, signal),
  { pageSize: 5, failureText: "券的额度账加载失败，请稍后重试" },
);

/** 今日的订单量单独标出来：看板最常被问的第一句是「今天怎么样」 */
const today = todayIso();
const todayCount = computed(() => (dateFilter.value === today ? countsTotal.value : null));

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value !== "authenticated") return;
  void loadCounts();
  void loadProcess();
  void contributions.reload();
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">数据看板</h2>
    <p class="ph-page-desc">
      订单量与过程指标。这一页的数字来自订单列表与考核明细两份真实接口；没有独立的经营统计接口，所以每格的取数口径都写在它下面。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <!-- ============ 一、订单量 ============ -->
      <section class="ph-card ph-stats__card">
        <h3 class="ph-card__title">订单量</h3>
        <div class="ph-toolbar">
          <label class="ph-field ph-stats__filter">
            <span class="ph-field__label">履约日期（留空 = 累计）</span>
            <input v-model="dateFilter" type="date" class="ph-input" @change="loadCounts" />
          </label>
          <button type="button" class="ph-button ph-button--secondary" @click="dateFilter = ''; loadCounts()">
            看累计
          </button>
        </div>

        <ConsoleState v-if="countsLoading" variant="loading" title="正在统计订单量" />
        <ConsoleState
          v-else-if="countsForbidden"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店订单。"
        />
        <ConsoleState
          v-else-if="countsError"
          variant="error"
          :title="countsError"
          :request-id="countsRequestId"
          @retry="loadCounts"
        />
        <template v-else>
          <p v-if="countsTotal === 0" class="ph-text-sub">这一段没有订单。</p>
          <ul v-else class="ph-stats__stats">
            <li v-for="code in ORDER_STATUSES" :key="code" class="ph-stats__stat">
              <span class="ph-stats__num">{{ counts[code] ?? 0 }}</span>
              <span class="ph-tag" :class="orderStatusTone(code)">{{ orderStatusLabel(code) }}</span>
            </li>
          </ul>
          <p class="ph-text-weak">
            {{ dateFilter ? `${formatDate(dateFilter)} 要履约的订单` : "累计（本店全部订单）" }}，共 {{ countsTotal }} 单。
            口径：对五个状态各取一次列表、读信封的 `total`（`page_size=1`）——契约里没有订单统计接口，
            所以「周 / 月」这种时间切片这一版做不了（没有区间参数，前端不自己拼）。
            <template v-if="todayCount !== null">今天（{{ formatDate(today) }}）共 {{ todayCount }} 单。</template>
          </p>
        </template>
      </section>

      <!-- ============ 二、过程指标 ============ -->
      <section class="ph-card ph-stats__card">
        <h3 class="ph-card__title">过程指标{{ processPeriod ? `（账期 ${processPeriod}）` : "" }}</h3>

        <ConsoleState v-if="processLoading" variant="loading" title="正在加载过程指标" />
        <ConsoleState
          v-else-if="processForbidden"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店考核明细。"
        />
        <ConsoleState
          v-else-if="processError"
          variant="error"
          :title="processError"
          :request-id="processRequestId"
          @retry="loadProcess"
        />
        <template v-else>
          <p v-if="processItems.length === 0" class="ph-text-sub">
            还没有考核记录：过程分（含核销率、报工完整率）随月度考核一起算，每月 1 日算上一个自然月。
          </p>
          <div v-else class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>指标</th>
                  <th>得分</th>
                  <th>原始值</th>
                  <th>达标线</th>
                  <th>数据来源</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in processItems" :key="item.item_code">
                  <td>{{ assessmentItemLabel(item.item_code, item.item_name) }}</td>
                  <td class="ph-table__num">{{ item.score ? scoreText(item.score) : "未参与" }}</td>
                  <td>{{ item.raw_value ?? "—" }}</td>
                  <td>{{ item.target_value ?? "—" }}</td>
                  <td>{{ item.data_source ?? "—" }}</td>
                  <td>{{ item.note ?? "—" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-text-weak">
            核销率、报工完整率这些指标不在这里另算：它们就是考核过程分的五个子项，分数与原始值都以
            考核明细为准（运营侧配置的达标线也在那一份里），两处各算一遍必然分叉。
          </p>
        </template>
      </section>

      <!-- ============ 三、券 ============ -->
      <section class="ph-card ph-stats__card">
        <h3 class="ph-card__title">券的额度账</h3>

        <ConsoleListState
          :loading="contributions.loading.value"
          :forbidden="contributions.forbidden.value"
          :error-message="contributions.errorMessage.value"
          :request-id="contributions.requestId.value"
          :is-empty="contributions.isEmpty.value"
          loading-title="正在加载券的额度账"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店的券贡献。"
          empty-title="还没有券贡献"
          empty-description="去「券管理 → 券池模板」挑一张服务者成本的券，承诺一个可核销额度；这张表会记下它被用掉多少。"
          @retry="contributions.reload"
        >
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>券</th>
                  <th>承诺额度</th>
                  <th>已核销</th>
                  <th>占用中</th>
                  <th>已过期</th>
                  <th>完成率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in contributions.items.value" :key="row.id">
                  <td>{{ row.template_name ?? row.template_code }}</td>
                  <td class="ph-table__num">{{ row.total_count }}</td>
                  <td class="ph-table__num">{{ row.redeemed_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ row.reserved_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ row.expired_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ completionRateText(row.completion_rate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="ph-text-weak">
            完成率 = 已核销 ÷ 承诺额度，由服务端算好（契约字段说明），这里只做展示换算。
            「券」在考核里是 40% 的一项：完成率与核销数一起看（ADR-0039 第三节），单看完成率会奖励「只承诺一张」。
          </p>
          <p v-if="contributions.total.value > contributions.items.value.length" class="ph-text-weak">
            共 {{ contributions.total.value }} 条贡献，这里只列前 {{ contributions.items.value.length }} 条。
          </p>
        </ConsoleListState>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-stats__card {
  margin-bottom: var(--ph-space-5);
}

.ph-stats__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-stats__filter .ph-input {
  width: auto;
  min-width: 160px;
}

.ph-stats__stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-4);
  margin: 0 0 var(--ph-space-3);
  padding: 0;
  list-style: none;
}

.ph-stats__stat {
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-2);
}

.ph-stats__num {
  font-family: var(--ph-font-numeric);
  font-size: 22px;
  font-weight: 600;
}
</style>
