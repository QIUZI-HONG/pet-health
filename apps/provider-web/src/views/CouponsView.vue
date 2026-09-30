<script setup lang="ts">
/**
 * 券管理（交付文档 3.2 P022，路由 /b/coupons）：本店在券池里的那一份贡献，以及这些券的去向。
 *
 * 边界（CONTEXT.md「券池 CouponPool」/ ADR-0037 第三节）：券模板由平台创建、券由平台定向发放，
 * **服务者不能自建券、也不负责发券**——这一页只做两件事：从池子里挑一个模板**承诺**可核销额度，
 * 以及看这份承诺的账（发出去了多少、核销了多少、还剩多少可发）。全局的券目录与发放规则在运营后台。
 *
 * 三条规则直接决定按钮与文案（都是契约里会拒绝的形状，写在頁面上省掉一次注定失败的提交）：
 *   - **承诺不是发放**：额度承诺之后立刻可用，券什么时候发给谁是平台的事；
 *   - **调不下去的部分不能收回**：`total_count` 必须 ≥ 已核销 + 占用中——已经发到用户手里的券，
 *     服务者反悔不能让它失效（`contributionFloor` 就是这条下限）；
 *   - **撤回只收回未发放的**：`DELETE` 把额度夹到「已核销 + 占用中」并把状态置为「已停止发放」，
 *     用户手里的券照常有效；要重开就用 `PUT status=1`（不新建第二条——一个模板只留一条额度账）。
 *
 * 券与钱无关（ADR-0036）：这里出现的面额只是到店抵扣的凭证值，没有任何资金流与结算。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatDateTime, toApiFailure } from "@pet-health/shared";
import {
  providerApp,
  type ContributionRow,
  type CouponContributionLogView,
  type CouponRow,
  type TemplateRow,
} from "../api/providerApi";
import { useProviderSession } from "../session";
import {
  completionRateText,
  contributionFloor,
  contributionLogActionLabel,
  contributionStatusLabel,
  contributionStatusTone,
  couponSourceLabel,
  couponStatusLabel,
  couponStatusTone,
} from "../utils/labels";

const { status } = useProviderSession();

type Tab = "contributions" | "templates";
const tab = ref<Tab>("contributions");

// ---- 我的贡献 ----
const contributionStatusFilter = ref("");
const contributions = usePagedList<ContributionRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listCouponContributions(
      { status: contributionStatusFilter.value === "" ? undefined : Number(contributionStatusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "券贡献加载失败，请稍后重试" },
);

// ---- 券池模板 ----
const templateKeyword = ref("");
const appliedTemplateKeyword = ref("");
/**
 * 模板池是否已经成功拉过一次。
 *
 * 为什么需要它：切分段时才加载（见 {@link switchTab}），判据必须是「有没有数据」而不是「切没切过」——
 * 拿「切过」当判据，第一次加载失败后再切回来就不会重试了。这里在**加载成功**时置位。
 */
let templatesLoaded = false;
const templates = usePagedList<TemplateRow>(
  async ({ page, pageSize }, signal) => {
    const result = await providerApp.listCouponTemplates(
      { keyword: appliedTemplateKeyword.value || undefined, page, pageSize },
      signal,
    );
    templatesLoaded = true;
    return result;
  },
  { failureText: "券池模板加载失败，请稍后重试" },
);

const submit = useSubmitAction("操作失败，请稍后重试");

/** 承诺 / 调整表单：新建时带模板，编辑时带那条额度账（模板不可换，PUT 仍要把 template_id 带上） */
const form = ref<{ mode: "create"; template: TemplateRow } | { mode: "edit"; row: ContributionRow } | null>(null);
const formCount = ref("");
const formRemark = ref("");
/** 撤回要二次确认：它会把可发放额度归零（已发出的券不受影响），手滑的代价是「这段时间发不出券」 */
const withdrawTarget = ref<ContributionRow | null>(null);

/** 展开的那条贡献：它的已发券明细 + 额度流水一起显示（「我的额度去哪了」的答案在流水里） */
const expandedId = ref<number | null>(null);
const issuedStatusFilter = ref("");
const issued = usePagedList<CouponRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listContributionCoupons(
      expandedId.value ?? 0,
      { status: issuedStatusFilter.value === "" ? undefined : Number(issuedStatusFilter.value), page, pageSize },
      signal,
    ),
  { pageSize: 10, failureText: "已发券明细加载失败，请稍后重试" },
);
const logs = ref<CouponContributionLogView[]>([]);
const logsLoading = ref(false);
const logsError = ref("");
const logsRequestId = ref("");

function reloadContributions(): void {
  void contributions.reload();
}

/**
 * 切分段时才拉那一段的数据（首屏只加载「我的贡献」，省一次请求）。
 *
 * 为什么不是「进页面就把两段都拉了」：模板池可能有很多页，而多数访问是来调整自己那份额度的。
 * 但**切过去必须真的拉一次**——否则会显示「券池里暂时没有可贡献的券」，把一个尚未加载的列表
 * 说成一个空的列表（这正是测试第一版抓到的 bug）。
 */
function switchTab(next: Tab): void {
  tab.value = next;
  if (next === "templates" && !templatesLoaded) {
    void templates.reload();
  }
}

function searchTemplates(): void {
  appliedTemplateKeyword.value = templateKeyword.value.trim();
  void templates.reload();
}

function openCreate(template: TemplateRow): void {
  submit.clear();
  form.value = { mode: "create", template };
  formCount.value = "";
  formRemark.value = "";
}

function openEdit(row: ContributionRow): void {
  submit.clear();
  form.value = { mode: "edit", row };
  formCount.value = String(row.total_count);
  formRemark.value = row.remark ?? "";
}

/** 正整数校验与后端 40001 同一口径（额度是「张数」，不是金额，所以不进 money 工具） */
function countValid(): boolean {
  return /^\d+$/.test(formCount.value.trim()) && Number(formCount.value.trim()) > 0;
}

async function submitForm(reopen = false): Promise<void> {
  const target = form.value;
  if (!target) return;
  const count = Number(formCount.value.trim());
  if (!countValid()) {
    submit.errorMessage.value = "额度要是正整数（承诺的是「我店愿意接多少张」）。";
    return;
  }
  // 调整时的下限：已核销 + 占用中（后端仍会再判一次，这里让用户少等一个往返）
  if (target.mode === "edit") {
    const floor = contributionFloor(target.row);
    if (count < floor) {
      submit.errorMessage.value = `额度不能低于 ${floor}（已核销 ${target.row.redeemed_count ?? 0} + 占用中 ${target.row.reserved_count ?? 0}）：已经发到用户手里的券不能被收回。`;
      return;
    }
  }

  const body = {
    template_id: target.mode === "create" ? target.template.id : target.row.template_id,
    total_count: count,
    remark: formRemark.value.trim() || null,
    ...(reopen ? { status: 1 } : {}),
  };
  const outcome = await submit.run(
    () => (target.mode === "create" ? providerApp.createCouponContribution(body) : providerApp.updateCouponContribution(target.row.id, body)),
    reopen ? "已重新启用发放" : target.mode === "create" ? "已承诺额度：可发放张数立刻生效" : "已调整额度",
  );
  if (!outcome.ok) return;
  form.value = null;
  reloadContributions();
}

async function withdraw(row: ContributionRow): Promise<void> {
  const outcome = await submit.run(
    () => providerApp.withdrawCouponContribution(row.id),
    "已撤回未发放的额度：已发出的券照常有效",
  );
  if (!outcome.ok) return;
  withdrawTarget.value = null;
  reloadContributions();
}

/** 在飞的那一次流水请求：连续展开两条贡献时，先发的那次要被作废（否则流水可能显示成上一条的） */
let logsInFlight: AbortController | null = null;

async function loadDetail(row: ContributionRow): Promise<void> {
  if (expandedId.value === row.id) {
    expandedId.value = null;
    logsInFlight?.abort();
    return;
  }
  expandedId.value = row.id;
  issuedStatusFilter.value = "";
  void issued.reload();
  logsInFlight?.abort();
  const controller = new AbortController();
  logsInFlight = controller;
  logsLoading.value = true;
  logsError.value = "";
  logsRequestId.value = "";
  try {
    const detail = await providerApp.getCouponContribution(row.id, controller.signal);
    if (controller.signal.aborted) return;
    logs.value = detail.logs ?? [];
  } catch (error) {
    if (controller.signal.aborted) return;
    // 详情读不到不影响上面的额度账（列表那份数据是刚拿到的），所以只让流水那一格报错
    logs.value = [];
    const failure = toApiFailure(error, "额度流水加载失败，请稍后重试");
    logsError.value = failure.message;
    logsRequestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      logsLoading.value = false;
    }
  }
}

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（StandardView 踩过这个坑）。
// 只加载当前分段的数据——模板池在服务者点过去时再拉，省一次请求。
onMounted(() => {
  if (status.value === "authenticated") {
    reloadContributions();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">券管理</h2>
    <p class="ph-page-desc">
      本店在平台券池里的贡献额度与这些券的去向。券模板由平台创建、券由平台发放——这里只管「我店愿意接多少张」以及这份承诺的账。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <nav class="ph-tabs" aria-label="券管理分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'contributions' }"
          @click="switchTab('contributions')"
        >
          我的贡献
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'templates' }"
          @click="switchTab('templates')"
        >
          券池模板
        </button>
      </nav>

      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-coupons__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-else-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-coupons__alert">
        {{ submit.doneMessage.value }}
      </p>
      <p v-if="submit.forbidden.value" class="ph-alert ph-alert--warn ph-coupons__alert">
        当前账号不能改券贡献（40300）：入驻未通过或门店被冻结时不能新增 / 调整承诺额度（契约的 403 说明）。
      </p>

      <!-- ============ 我的贡献 ============ -->
      <template v-if="tab === 'contributions'">
        <div class="ph-toolbar">
          <label class="ph-field ph-coupons__filter">
            <span class="ph-field__label">状态</span>
            <select v-model="contributionStatusFilter" class="ph-select" @change="reloadContributions">
              <option value="">全部</option>
              <option value="1">生效中</option>
              <option value="2">已停止发放</option>
            </select>
          </label>
          <span class="ph-toolbar__spacer" />
          <button type="button" class="ph-button ph-button--secondary" @click="switchTab('templates')">去券池挑一张券</button>
        </div>

        <ConsoleListState
          :loading="contributions.loading.value"
          :forbidden="contributions.forbidden.value"
          :error-message="contributions.errorMessage.value"
          :request-id="contributions.requestId.value"
          :is-empty="contributions.isEmpty.value"
          loading-title="正在加载券贡献"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店的券贡献。"
          empty-title="还没有贡献任何券"
          empty-description="去「券池模板」里挑一张服务者成本的券，承诺一个可核销的张数——承诺之后额度立刻可用。"
          @retry="reloadContributions"
        >
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>券</th>
                  <th>适用范围</th>
                  <th>额度账（承诺 / 已发 / 已核销 / 占用中 / 过期 / 可发）</th>
                  <th>完成率</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="row in contributions.items.value" :key="row.id">
                  <tr>
                    <td>
                      {{ row.template_name ?? row.template_code }}
                      <span class="ph-text-weak ph-coupons__sub">
                        面额 {{ formatAmount(row.face_value) }} · 门槛 {{ formatAmount(row.min_amount) }}
                      </span>
                    </td>
                    <td>{{ row.scope_desc ?? "—" }}</td>
                    <td class="ph-table__num">
                      {{ row.total_count }} / {{ row.issued_count ?? 0 }} / {{ row.redeemed_count ?? 0 }} /
                      {{ row.reserved_count ?? 0 }} / {{ row.expired_count ?? 0 }} / {{ row.available_count ?? 0 }}
                      <span class="ph-text-weak ph-coupons__sub">{{ row.remark ?? "" }}</span>
                    </td>
                    <td class="ph-table__num">{{ completionRateText(row.completion_rate) }}</td>
                    <td>
                      <span class="ph-tag" :class="contributionStatusTone(row.status)">
                        {{ contributionStatusLabel(row.status) }}
                      </span>
                    </td>
                    <td>
                      <div class="ph-table__actions">
                        <button type="button" class="ph-table__action" @click="loadDetail(row)">
                          {{ expandedId === row.id ? "收起明细" : "明细 / 流水" }}
                        </button>
                        <button type="button" class="ph-table__action" @click="openEdit(row)">调整额度</button>
                        <button
                          type="button"
                          class="ph-table__action"
                          :disabled="row.status === 2"
                          :title="row.status === 2 ? '已经停止发放了' : ''"
                          @click="withdrawTarget = row"
                        >
                          撤回
                        </button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="expandedId === row.id">
                    <td colspan="6" class="ph-coupons__detail">
                      <div class="ph-toolbar">
                        <strong>已发券明细</strong>
                        <label class="ph-field ph-coupons__filter">
                          <span class="ph-field__label">券状态</span>
                          <select v-model="issuedStatusFilter" class="ph-select" @change="issued.reload">
                            <option value="">全部</option>
                            <option value="1">待使用</option>
                            <option value="2">已锁定</option>
                            <option value="3">已核销</option>
                            <option value="4">已过期</option>
                          </select>
                        </label>
                      </div>

                      <ConsoleListState
                        :loading="issued.loading.value"
                        :forbidden="issued.forbidden.value"
                        :error-message="issued.errorMessage.value"
                        :request-id="issued.requestId.value"
                        :is-empty="issued.isEmpty.value"
                        loading-title="正在加载已发券"
                        forbidden-title="暂无权限"
                        forbidden-description="这个账号的令牌不能读取这张券的发放明细。"
                        empty-title="这张券还没有发出去"
                        empty-description="券由平台定向发放（邀请 / 打卡任务 / 积分兑换），服务者这边只承诺额度。"
                        @retry="issued.reload"
                      >
                        <div class="ph-table-wrap">
                          <table class="ph-table">
                            <thead>
                              <tr>
                                <th>券码</th>
                                <th>面额</th>
                                <th>来源</th>
                                <th>状态</th>
                                <th>发放 / 有效期</th>
                                <th>核销时间</th>
                                <th>领券人</th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr v-for="coupon in issued.items.value" :key="coupon.id">
                                <td class="ph-table__num">{{ coupon.code ?? "—" }}</td>
                                <td class="ph-table__num">{{ formatAmount(coupon.face_value) }}</td>
                                <td>{{ couponSourceLabel(coupon.source) }}</td>
                                <td>
                                  <span class="ph-tag" :class="couponStatusTone(coupon.status)">
                                    {{ couponStatusLabel(coupon.status) }}
                                  </span>
                                </td>
                                <td class="ph-table__num">
                                  {{ formatDateTime(coupon.issued_at) }}
                                  <span class="ph-text-weak ph-coupons__sub">至 {{ formatDateTime(coupon.valid_until) }}</span>
                                </td>
                                <td class="ph-table__num">{{ formatDateTime(coupon.redeemed_at) || "—" }}</td>
                                <td>用户 #{{ coupon.user_id ?? "—" }}</td>
                              </tr>
                            </tbody>
                          </table>
                        </div>
                        <div class="ph-pager">
                          <span>共 {{ issued.total.value }} 张</span>
                          <button
                            type="button"
                            class="ph-button ph-button--secondary"
                            :disabled="issued.page.value <= 1 || issued.loading.value"
                            @click="issued.prevPage"
                          >
                            上一页
                          </button>
                          <span>第 {{ issued.page.value }} 页</span>
                          <button
                            type="button"
                            class="ph-button ph-button--secondary"
                            :disabled="!issued.hasMore.value || issued.loading.value"
                            @click="issued.nextPage"
                          >
                            下一页
                          </button>
                        </div>
                      </ConsoleListState>

                      <h4 class="ph-coupons__logs-title">额度流水（append-only：承诺 / 调整 / 撤回 / 过期释放）</h4>
                      <p v-if="logsError" class="ph-alert ph-alert--error">
                        {{ logsError }}
                        <span v-if="logsRequestId" class="ph-text-weak">（请求 ID：{{ logsRequestId }}）</span>
                        <button type="button" class="ph-table__action ph-coupons__retry" @click="loadDetail(row)">重新加载</button>
                      </p>
                      <p v-else-if="logsLoading" class="ph-text-sub">正在加载流水…</p>
                      <p v-else-if="logs.length === 0" class="ph-text-sub">暂无流水。</p>
                      <ul v-else class="ph-coupons__logs">
                        <li v-for="log in logs" :key="log.id">
                          {{ formatDateTime(log.created_at) }} · {{ contributionLogActionLabel(log.action) }} →
                          承诺额度 {{ log.total_count }}
                          <span class="ph-text-weak">{{ log.remark ?? "" }}（操作者 {{ log.operator_id ?? 0 }}）</span>
                        </li>
                      </ul>
                      <p class="ph-text-weak">领券人的身份不在服务者的可见范围内，这里只有 `user_id`（契约明文）。</p>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>

          <div class="ph-pager">
            <span>共 {{ contributions.total.value }} 条</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="contributions.page.value <= 1 || contributions.loading.value"
              @click="contributions.prevPage"
            >
              上一页
            </button>
            <span>第 {{ contributions.page.value }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!contributions.hasMore.value || contributions.loading.value"
              @click="contributions.nextPage"
            >
              下一页
            </button>
          </div>
        </ConsoleListState>
      </template>

      <!-- ============ 券池模板 ============ -->
      <template v-else>
        <div class="ph-toolbar">
          <label class="ph-field ph-coupons__search">
            <span class="ph-field__label">按券名查找</span>
            <input v-model="templateKeyword" class="ph-input" maxlength="64" @keyup.enter="searchTemplates" />
          </label>
          <button type="button" class="ph-button ph-button--secondary" @click="searchTemplates">查询</button>
        </div>
        <p class="ph-text-sub ph-coupons__hint">
          池子里只列服务者成本的券：平台补贴券的成本归平台，服务者既不需要、也不允许为它承诺额度（ADR-0044 第一节）。
          「全平台已发放」是券池的热度，不是本店的数。
        </p>

        <ConsoleListState
          :loading="templates.loading.value"
          :forbidden="templates.forbidden.value"
          :error-message="templates.errorMessage.value"
          :request-id="templates.requestId.value"
          :is-empty="templates.isEmpty.value"
          loading-title="正在加载券池模板"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取券池模板。"
          empty-title="券池里暂时没有可贡献的券"
          empty-description="平台还没有创建服务者成本的券模板，或它们都被停用了。这一段时间无需承诺额度——考核的「券」维度在这种情况下按「无该维度要求」不参与（ADR-0050 第四节）。"
          @retry="templates.reload"
        >
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>券</th>
                  <th>面额 / 门槛</th>
                  <th>适用范围</th>
                  <th>有效期</th>
                  <th>全平台已发放</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in templates.items.value" :key="row.id">
                  <td>
                    {{ row.name }}
                    <span class="ph-text-weak ph-coupons__sub">{{ row.code }}</span>
                  </td>
                  <td class="ph-table__num">
                    {{ formatAmount(row.face_value) }}
                    <span class="ph-text-weak ph-coupons__sub">满 {{ formatAmount(row.min_amount) }} 可用</span>
                  </td>
                  <td>{{ row.scope_desc ?? "不限" }}</td>
                  <td>发放后 {{ row.valid_days ?? "—" }} 天内有效</td>
                  <td class="ph-table__num">{{ row.issued_count ?? 0 }}</td>
                  <td>
                    <button type="button" class="ph-table__action" @click="openCreate(row)">贡献这张券</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="ph-pager">
            <span>共 {{ templates.total.value }} 个模板</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="templates.page.value <= 1 || templates.loading.value"
              @click="templates.prevPage"
            >
              上一页
            </button>
            <span>第 {{ templates.page.value }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!templates.hasMore.value || templates.loading.value"
              @click="templates.nextPage"
            >
              下一页
            </button>
          </div>
        </ConsoleListState>
      </template>

      <form v-if="form" class="ph-card ph-coupons__panel" @submit.prevent="submitForm(false)">
        <h3 class="ph-card__title">
          {{ form.mode === "create" ? `承诺额度：${form.template.name}` : `调整额度：${form.row.template_name ?? form.row.template_code}` }}
        </h3>
        <p class="ph-alert ph-alert--info">
          承诺的是「我店愿意接多少张」，不是发放；可发放张数承诺后立刻可用，券由平台定向发给用户。
        </p>
        <label class="ph-field ph-coupons__count">
          <span class="ph-field__label">承诺可核销额度（张）</span>
          <input v-model="formCount" class="ph-input" inputmode="numeric" />
          <span class="ph-field__hint">
            <template v-if="form.mode === 'edit'">
              不能低于 {{ contributionFloor(form.row) }}（已核销 {{ form.row.redeemed_count ?? 0 }} + 占用中
              {{ form.row.reserved_count ?? 0 }}）：已经发到用户手里的券不能被收回。
            </template>
            <template v-else>正整数；之后可以调高，也可以撤回未发放的那部分。</template>
          </span>
        </label>
        <label class="ph-field ph-coupons__remark">
          <span class="ph-field__label">说明（进额度流水，可选）</span>
          <input v-model="formRemark" class="ph-input" maxlength="255" />
        </label>
        <div class="ph-coupons__actions">
          <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
            {{ submit.submitting.value ? "提交中…" : form.mode === "create" ? "承诺额度" : "保存额度" }}
          </button>
          <button
            v-if="form.mode === 'edit' && form.row.status === 2"
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="submit.submitting.value"
            @click="submitForm(true)"
          >
            保存并重新启用发放
          </button>
          <button type="button" class="ph-button ph-button--secondary" @click="form = null">放弃</button>
        </div>
      </form>

      <section v-if="withdrawTarget" class="ph-card ph-coupons__panel">
        <h3 class="ph-card__title">撤回未发放的额度：{{ withdrawTarget.template_name ?? withdrawTarget.template_code }}</h3>
        <p class="ph-alert ph-alert--warn">
          撤回把承诺额度夹到「已核销 {{ withdrawTarget.redeemed_count ?? 0 }} + 占用中 {{ withdrawTarget.reserved_count ?? 0 }}」，
          可发放张数归零、状态转为「已停止发放」。已经发到用户手里的券照常有效、照常能核销——
          用户的券不是服务者的可撤销承诺。
        </p>
        <div class="ph-coupons__actions">
          <button type="button" class="ph-button ph-button--primary" :disabled="submit.submitting.value" @click="withdraw(withdrawTarget)">
            {{ submit.submitting.value ? "提交中…" : "确认撤回" }}
          </button>
          <button type="button" class="ph-button ph-button--secondary" @click="withdrawTarget = null">放弃</button>
        </div>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-coupons__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-coupons__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-coupons__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-coupons__search {
  min-width: 240px;
}

.ph-coupons__sub {
  display: block;
  font-size: 12px;
}

.ph-coupons__hint {
  margin-bottom: var(--ph-space-4);
}

.ph-coupons__detail {
  background: var(--ph-color-bg);
}

.ph-coupons__logs-title {
  margin: var(--ph-space-4) 0 var(--ph-space-2);
  font-size: 13px;
}

.ph-coupons__logs {
  margin: 0 0 var(--ph-space-3);
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 1.8;
}

.ph-coupons__retry {
  margin-left: var(--ph-space-3);
}

.ph-coupons__panel {
  margin-top: var(--ph-space-5);
}

.ph-coupons__count {
  max-width: 320px;
  margin-top: var(--ph-space-4);
}

.ph-coupons__remark {
  max-width: 480px;
  margin-top: var(--ph-space-4);
}

.ph-coupons__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
