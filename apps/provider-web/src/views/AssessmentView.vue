<script setup lang="ts">
/**
 * 考核中心（交付文档 3.2 P023，路由 /b/assess）：我的月度考核。
 *
 * 规则（ADR-0039 第三节 / ADR-0050 第四节 / ADR-0052）：总分 = **拉新 40% + 券 40% + 过程 20%**，
 * 每月 1 日算上月、保留历史（跨月可对比）。**权重与档位阈值在运营后台配置**（ADR-0010 的
 * 业务可调项那一层），服务者侧**没有规则接口**——`/assessments/rules` 不存在。所以页面上的
 * 规则说明是**文案**，不是读来的配置：读不到的东西不能摆成「当前生效规则」，那会让服务者
 * 按一个可能是错的比例去调经营动作。
 *
 * 明细里三个不看就会误判的地方（契约原话）：
 *   - **未参与（`participated=false`）的项照样下发**：它们是「这个分为什么是这么算出来的」的答案，
 *     藏起来就只剩一个说不清的总分——所以页面单列一段，并把原因（`note` / `data_source`）写出来；
 *   - **覆盖留痕对服务者可见**（谁、何时、改成多少、为什么）：ADR-0039 说得很直白，否则算法是黑箱、无法申诉；
 *   - **每项都带 `data_source`**：读不到的事实要能指名缺的是哪一条，而不是给个 0 分。
 *
 * 这一页只**看**自己那份，不改规则：服务者看别人的账期一律 40400（与不存在同码）。
 */
import { computed, onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, ConsoleState, usePagedList } from "@pet-health/ui";
import { formatDateTime, toApiFailure } from "@pet-health/shared";
import { providerApp, type AssessmentItemView, type AssessmentRow, type AssessmentView } from "../api/providerApi";
import { useProviderSession } from "../session";
import {
  assessmentItemLabel,
  assessmentLevelLabel,
  assessmentLevelTone,
  assessmentRankText,
  participatedWeightText,
  recommendPriorityLabel,
  scoreText,
} from "../utils/labels";

const { status } = useProviderSession();

const periodFilter = ref("");
const appliedPeriod = ref("");

const list = usePagedList<AssessmentRow>(
  ({ page, pageSize }, signal) => providerApp.listAssessments({ period: appliedPeriod.value || undefined, page, pageSize }, signal),
  { failureText: "考核列表加载失败，请稍后重试" },
);

/** 选中的账期与它的明细。明细要单独拉一次：列表行里没有三项与过程子项的得分 */
const selectedPeriod = ref("");
const detail = ref<AssessmentView | null>(null);
const detailLoading = ref(false);
const detailError = ref("");
const detailRequestId = ref("");

/** 三项主项（`parent_code` 为空）与过程分的五个子项分开渲染：子项等权、没有主项层的权重 */
const mainItems = computed<AssessmentItemView[]>(() => (detail.value?.items ?? []).filter((item) => !item.parent_code));
const processItems = computed<AssessmentItemView[]>(
  () => (detail.value?.items ?? []).filter((item) => item.parent_code === "PROCESS"),
);

function applyPeriodFilter(): void {
  appliedPeriod.value = periodFilter.value.trim();
  void list.reload();
}

/** 在飞的那一次：快速点两期的「看明细」时，先发的那次要被作废（否则先后回来的顺序会决定详情是谁的） */
let detailInFlight: AbortController | null = null;

async function loadDetail(period: string): Promise<void> {
  detailInFlight?.abort();
  const controller = new AbortController();
  detailInFlight = controller;
  selectedPeriod.value = period;
  detailLoading.value = true;
  detailError.value = "";
  detailRequestId.value = "";
  try {
    const view = await providerApp.getAssessment(period, controller.signal);
    if (controller.signal.aborted) return;
    detail.value = view;
  } catch (error) {
    if (controller.signal.aborted) return;
    // 40400 在这里是「该账期没有记录 / 不是你的账期」——两句都按后端那句话原样展示，再给一个重试
    detail.value = null;
    const failure = toApiFailure(error, "考核明细加载失败，请稍后重试");
    detailError.value = failure.message;
    detailRequestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      detailLoading.value = false;
    }
  }
}

/** 未参与计分的项（含过程子项）：单独一段说明「为什么它不参与」，别混在得分里 */
const notParticipated = computed<AssessmentItemView[]>(() => (detail.value?.items ?? []).filter((item) => item.participated === false));

function itemScore(item: AssessmentItemView): string {
  return item.score ? scoreText(item.score) : "未参与";
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话（StandardView 踩过这个坑）
  if (status.value === "authenticated") {
    void list.reload();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">考核中心</h2>
    <p class="ph-page-desc">
      月度考核：总分 = 拉新 40% + 券 40% + 过程 20%，每月 1 日自动算上月；等级决定 AI 推荐优先级与区域保护，所以这一页要让服务者看懂「分差在哪一项、怎么补」。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-assess__filter">
          <span class="ph-field__label">账期</span>
          <input v-model="periodFilter" class="ph-input" placeholder="如 2026-08" @keyup.enter="applyPeriodFilter" />
        </label>
        <button type="button" class="ph-button ph-button--secondary" @click="applyPeriodFilter">查询</button>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" :disabled="list.loading.value" @click="list.reload">
          刷新
        </button>
      </div>

      <ConsoleListState
        :loading="list.loading.value"
        :forbidden="list.forbidden.value"
        :error-message="list.errorMessage.value"
        :request-id="list.requestId.value"
        :is-empty="list.isEmpty.value"
        loading-title="正在加载考核列表"
        forbidden-title="暂无权限"
        forbidden-description="这个账号的令牌不能读取本店的考核记录。"
        empty-title="还没有考核记录"
        empty-description="每月 1 日算上一个自然月；账期还没算到、或门店刚入驻时，这里会是空的。"
        @retry="list.reload"
      >
        <div class="ph-table-wrap">
          <table class="ph-table">
            <thead>
              <tr>
                <th>账期</th>
                <th>总分</th>
                <th>等级 / 推荐优先级</th>
                <th>参与计分</th>
                <th>计算时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in list.items.value" :key="row.id">
                <td class="ph-table__num">{{ row.period }}</td>
                <td class="ph-table__num">
                  {{ scoreText(row.total_score) }}
                  <span v-if="row.overridden" class="ph-tag ph-tag--warning ph-assess__sub">有单项分被覆盖</span>
                </td>
                <td>
                  <span class="ph-tag" :class="assessmentLevelTone(row.level)">{{ assessmentLevelLabel(row) }}</span>
                  <span class="ph-text-weak ph-assess__sub">AI 推荐优先级：{{ recommendPriorityLabel(row.recommend_priority) }}</span>
                </td>
                <td>{{ participatedWeightText(row.participated_weight) }}</td>
                <td class="ph-table__num">{{ formatDateTime(row.calculated_at) }}</td>
                <td>
                  <button type="button" class="ph-table__action" @click="loadDetail(row.period)">
                    {{ selectedPeriod === row.period ? "重新加载明细" : "看明细" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="ph-pager">
          <span>共 {{ list.total.value }} 期</span>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="list.page.value <= 1 || list.loading.value"
            @click="list.prevPage"
          >
            上一页
          </button>
          <span>第 {{ list.page.value }} 页</span>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="!list.hasMore.value || list.loading.value"
            @click="list.nextPage"
          >
            下一页
          </button>
        </div>
      </ConsoleListState>

      <section class="ph-card ph-assess__panel">
        <h3 class="ph-card__title">考核规则</h3>
        <p class="ph-text-sub">
          总分 = 拉新 40% + 券 40% + 过程 20%（权重与档位阈值由运营后台配置，这里只是说明，不是页面上读到的配置）。
          过程分由五个子项等权合成：接单响应、核销率、报工完整率、评价分、服务者取消率。
        </p>
        <p class="ph-text-sub">
          缺项两种口径：该做而没做 → 记 0 分；平台侧没有这个维度的要求（例如券池里一张可贡献的券都没有）
          → 该项不参与、权重按参与项重算，明细里会注明「未参与」与原因。等级与 AI 推荐优先级由总分映射，
          只影响推荐排序，不改推荐逻辑本身。
        </p>
      </section>

      <section v-if="detailLoading" class="ph-card ph-assess__panel">
        <ConsoleState variant="loading" title="正在加载考核明细" />
      </section>
      <section v-else-if="detailError" class="ph-card ph-assess__panel">
        <ConsoleState
          variant="error"
          :title="detailError"
          :request-id="detailRequestId"
          retry-text="重新加载明细"
          @retry="loadDetail(selectedPeriod)"
        >
          <p class="ph-text-sub">账期 {{ selectedPeriod }} 没有考核记录，或不属于本店（契约里这两种情况同码 40400）。</p>
        </ConsoleState>
      </section>

      <template v-else-if="detail">
        <section class="ph-card ph-assess__panel">
          <h3 class="ph-card__title">{{ detail.period }} 考核明细</h3>
          <p class="ph-assess__total">
            总分 <strong class="ph-table__num">{{ scoreText(detail.total_score) }}</strong>
            <span class="ph-tag" :class="assessmentLevelTone(detail.level)">{{ assessmentLevelLabel(detail) }}</span>
            <span class="ph-text-sub">{{ assessmentRankText(detail) }} · {{ participatedWeightText(detail.participated_weight) }}</span>
          </p>

          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>项</th>
                  <th>权重</th>
                  <th>得分</th>
                  <th>原始值</th>
                  <th>达标线</th>
                  <th>数据来源</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in mainItems" :key="item.item_code">
                  <td>
                    {{ assessmentItemLabel(item.item_code, item.item_name) }}
                    <span v-if="item.overridden" class="ph-tag ph-tag--warning ph-assess__sub">被覆盖</span>
                  </td>
                  <td class="ph-table__num">{{ item.weight != null ? `${item.weight}%` : "—" }}</td>
                  <td class="ph-table__num">{{ itemScore(item) }}</td>
                  <td>{{ item.raw_value ?? "—" }}</td>
                  <td>{{ item.target_value ?? "—" }}</td>
                  <td>{{ item.data_source ?? "—" }}</td>
                  <td>{{ item.note ?? "—" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="ph-card ph-assess__panel">
          <h3 class="ph-card__title">过程分（五个子项等权）</h3>
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>子项</th>
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
                  <td class="ph-table__num">{{ itemScore(item) }}</td>
                  <td>{{ item.raw_value ?? "—" }}</td>
                  <td>{{ item.target_value ?? "—" }}</td>
                  <td>{{ item.data_source ?? "—" }}</td>
                  <td>{{ item.note ?? "—" }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="ph-card ph-assess__panel">
          <h3 class="ph-card__title">未参与计分的项（{{ notParticipated.length }}）</h3>
          <p v-if="notParticipated.length === 0" class="ph-text-sub">本期所有项都参与了计分。</p>
          <ul v-else class="ph-assess__list">
            <li v-for="item in notParticipated" :key="item.item_code">
              {{ assessmentItemLabel(item.item_code, item.item_name) }}：{{ item.note ?? "未参与" }}
              <span class="ph-text-weak">（数据来源：{{ item.data_source ?? "—" }}）</span>
            </li>
          </ul>
        </section>

        <section class="ph-card ph-assess__panel">
          <h3 class="ph-card__title">单项分覆盖留痕（{{ (detail.overrides ?? []).length }}）</h3>
          <p v-if="(detail.overrides ?? []).length === 0" class="ph-text-sub">本期没有被覆盖过的单项分。</p>
          <ul v-else class="ph-assess__list">
            <li v-for="row in detail.overrides" :key="row.id">
              {{ row.item_name ?? row.item_code }}：{{ scoreText(row.before_score) }} → {{ scoreText(row.after_score) }}
              <span class="ph-text-weak">
                （{{ row.reason }} · 操作者 {{ row.operator_id ?? 0 }} · {{ formatDateTime(row.created_at) }}）
              </span>
            </li>
          </ul>
          <p class="ph-text-weak">
            覆盖留痕对服务者可见是有意的：规则要能申诉，就得看得见谁在什么时候把哪一项改成了多少。
          </p>
        </section>
      </template>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-assess__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-assess__filter .ph-input {
  width: auto;
  min-width: 140px;
}

.ph-assess__sub {
  display: block;
  font-size: 12px;
}

.ph-assess__panel {
  margin-top: var(--ph-space-5);
}

.ph-assess__total {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ph-space-3);
  margin: 0 0 var(--ph-space-4);
}

.ph-assess__list {
  margin: 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 1.8;
}
</style>
