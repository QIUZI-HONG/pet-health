<script setup lang="ts">
/**
 * 补贴券窗口（交付文档 3.1 模块树；页面清单里并进 P035 的「券目录 / 补贴窗口」）。
 *
 * 与服务者的券分开：补贴券是**平台出**的那一类（`cost_bearer = 2`），到点开、到点停。
 * ADR-0036 之后平台不经手资金，抵扣仍然发生在门店，所以这一页管的是「发多少、发给谁、
 * 什么时候停」——预算控制是它的主要用途。
 *
 * **契约里没有「窗口」这个实体，这一页不假装有**：没有起止时间字段，也没有独立的窗口表。
 * 现有接口能表达的窗口只有两件事，本页就只做这两件：
 *   - **额度**：模板的 `issue_limit`（发放上限），配 `/coupon-pool/overview` 与模板的
 *     `issued_count` 看还剩多少；
 *   - **开停**：模板的启停（`PUT /coupon-templates/{template_id}/status`），停用即停发。
 * 「到点自动停」需要定时任务与窗口字段，契约里都没有——所以这里不摆「起止时间」输入框：
 * 摆了没有地方存，改完也不会生效，那是比缺功能更糟的假动作。缺什么写在页面下方那句提示里。
 *
 * 券实例的有效期（`valid_days`）不是窗口：它是「发给用户之后 N 天内有效」，说的是用户那一侧。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatDateTime } from "@pet-health/shared";
import { adminApp, type CouponTemplateRow } from "../api/adminApi";
import { useAdminSession } from "../session";
import CouponIssuePanel from "../components/CouponIssuePanel.vue";

const { status } = useAdminSession();

const statusFilter = ref("");
const keyword = ref("");

const templates = usePagedList<CouponTemplateRow>(
  ({ page, pageSize }, signal) =>
    // cost_bearer=2 是这个页面的定义域：服务者成本的券是服务者自己的额度账，平台不看它的窗口
    adminApp.listCouponTemplates(
      {
        costBearer: 2,
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        keyword: keyword.value.trim() || undefined,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "平台补贴券模板加载失败，请稍后重试" },
);

const submit = useSubmitAction("停发失败，请稍后重试");

function reload(): void {
  void templates.reload();
}

/** 停用 / 启用 = 窗口的开与关。停用只挡新的发放，已经发出去的券照常能核销。 */
async function toggleWindow(row: CouponTemplateRow): Promise<void> {
  const next = row.status === 1 ? 0 : 1;
  const outcome = await submit.run(
    () => adminApp.updateCouponTemplateStatus(row.id, next as 0 | 1),
    next === 0 ? "窗口已关停：不再发新券，已发出的券照常可核销" : "窗口已打开：可以继续发放",
  );
  if (!outcome.ok) return;
  reload();
}

/** 已发 / 上限。上限为空 = 不限——平台补贴券不设上限就等于没有预算约束，运营要看得见这件事。 */
function progress(row: CouponTemplateRow): { text: string; percent: number | null } {
  const issued = row.issued_count ?? 0;
  if (row.issue_limit === null || row.issue_limit === undefined) return { text: `${issued} / 不限`, percent: null };
  const percent = row.issue_limit <= 0 ? 100 : Math.min(100, Math.round((issued / row.issue_limit) * 100));
  return { text: `${issued} / ${row.issue_limit}`, percent };
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（也不白跑一次网络）
  if (status.value === "authenticated") {
    void templates.load();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">补贴券窗口</h2>
    <p class="ph-page-desc">
      平台补贴券的发放控制：额度剩多少、现在开还是停，以及定向发放。窗口在这里由两件事表达——模板的发放上限与模板启停，没有「起止时间」这种字段（见下方的缺口说明）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-subsidy__filter">
          <span class="ph-field__label">窗口状态</span>
          <select v-model="statusFilter" class="ph-select" @change="reload">
            <option value="">全部</option>
            <option value="1">开着（启用）</option>
            <option value="0">已关停（停用）</option>
          </select>
        </label>
        <input v-model="keyword" class="ph-input ph-subsidy__search" maxlength="64" placeholder="按券名搜索" @keyup.enter="reload" />
        <button type="button" class="ph-button ph-button--secondary" :disabled="templates.loading.value" @click="reload">刷新</button>
        <span class="ph-toolbar__spacer" />
        <router-link :to="{ name: 'coupon-pool' }" class="ph-subsidy__link">新建模板去「券池管理」</router-link>
      </div>

      <p class="ph-field__hint ph-subsidy__hint">
        「窗口」= 发放上限（`issue_limit`）+ 模板启停：契约里没有起止时间字段，也没有独立的窗口表，
        所以这一页不摆「起止时间」——填了没有地方存，改完也不会生效。到点自动停需要定时任务与窗口字段，
        两者都还没有（缺口写在报告里）。用户手上那张券的有效期是另一件事：`valid_days` 自发放之日起算。
      </p>

      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-subsidy__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-subsidy__alert">{{ submit.doneMessage.value }}</p>

      <ConsoleListState
        :loading="templates.loading.value"
        :forbidden="templates.forbidden.value"
        :error-message="templates.errorMessage.value"
        :request-id="templates.requestId.value"
        :is-empty="templates.isEmpty.value"
        loading-title="正在加载平台补贴券模板"
        forbidden-title="暂无权限"
        forbidden-description="这个运营账号的令牌不能读取券模板。"
        empty-title="还没有平台补贴券模板"
        empty-description="补贴券的定义就是一个成本归属为「平台补贴」的券模板：去「券池管理」建一个，这里才会出现窗口。"
        @retry="reload"
      >
        <div class="ph-table-wrap">
          <table class="ph-table">
            <thead>
              <tr>
                <th>编码</th>
                <th>名称</th>
                <th>面额 / 门槛</th>
                <th>适用范围</th>
                <th>已发 / 上限</th>
                <th>额度进度</th>
                <th>窗口状态</th>
                <th>更新时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in templates.items.value" :key="row.id">
                <td class="ph-table__num">{{ row.code }}</td>
                <td>{{ row.name }}</td>
                <td class="ph-table__num">{{ formatAmount(row.face_value) }} / 满 {{ formatAmount(row.min_amount) }}</td>
                <td>{{ row.scope_desc ?? "不限" }}</td>
                <td class="ph-table__num">{{ progress(row).text }}</td>
                <td class="ph-subsidy__progress">
                  <span v-if="progress(row).percent === null" class="ph-field__hint">不限量</span>
                  <template v-else>
                    <span class="ph-subsidy__bar"><span class="ph-subsidy__bar-fill" :style="{ width: `${progress(row).percent}%` }" /></span>
                    <span class="ph-table__num">{{ progress(row).percent }}%</span>
                  </template>
                </td>
                <td>
                  <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                    {{ row.status === 1 ? "开着" : "已关停" }}
                  </span>
                </td>
                <td class="ph-table__num">{{ formatDateTime(row.updated_at) }}</td>
                <td>
                  <div class="ph-table__actions">
                    <button
                      type="button"
                      class="ph-table__action"
                      :disabled="submit.submitting.value"
                      @click="toggleWindow(row)"
                    >
                      {{ row.status === 1 ? "关停窗口" : "打开窗口" }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="ph-pager">
          <span>共 {{ templates.total.value }} 个补贴券模板</span>
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

      <h4 class="ph-card__title ph-subsidy__sub">定向发放</h4>
      <CouponIssuePanel />
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-subsidy__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-subsidy__filter .ph-select {
  width: auto;
  min-width: 130px;
}

.ph-subsidy__search {
  width: 180px;
}

.ph-subsidy__link {
  font-size: 13px;
}

.ph-subsidy__hint {
  margin: 0 0 var(--ph-space-4);
  max-width: 880px;
}

.ph-subsidy__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-subsidy__progress {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  min-width: 140px;
}

.ph-subsidy__bar {
  display: inline-block;
  width: 72px;
  height: 6px;
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-bg);
}

.ph-subsidy__bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-primary);
}

.ph-subsidy__sub {
  margin: var(--ph-space-6) 0 var(--ph-space-3);
  font-size: 15px;
  font-weight: 600;
}
</style>
