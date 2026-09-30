<script setup lang="ts">
/**
 * 券池总览与对账（`GET /api/v1/admin/coupon-pool/overview`）。
 *
 * 这张表是**券池唯一的一句话账**：模板几个、服务者承诺了多少张、发出去多少、核销多少、
 * 还剩多少在用户手里、多少已过期作废。之所以能当账看，因为券在这里是**张数与额度**，
 * 不是钱——ADR-0036 之后平台不经手资金，所以「券的对账」就是本体，没有另一本资金账可对。
 *
 * `reconciliation.balanced` 是那个恒等式的结论：**实例数 = 已发放 = 已核销 + 未过期未核销 +
 * 已过期未核销**。不平（false）要有人查，所以它在这页上必须有颜色，不能混在同样大小的数字里
 * ——这是一屏只读指标里唯一一个「需要动作」的字段。
 *
 * `by_source` 按来源拆开的原因：**「补贴花了多少」与「任务发出去多少」是两笔不同的账**
 * （一笔平台出、一笔服务者出），合起来看只会得到一个谁也解释不了的总额。
 */
import { onMounted } from "vue";
import { couponSourceLabel as sourceLabel } from "@pet-health/shared";
import { ConsoleListState } from "@pet-health/ui";
import { adminApp } from "../api/adminApi";
import { useSection } from "../composables/useSection";
import { useAdminSession } from "../session";

const { status } = useAdminSession();
const overview = useSection<Awaited<ReturnType<typeof adminApp.getCouponPoolOverview>>>();

async function load(): Promise<void> {
  await overview.load(() => adminApp.getCouponPoolOverview(), "券池总览加载失败，请稍后重试");
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    void load();
  }
});
</script>

<template>
  <ConsoleListState
    :loading="overview.loading.value"
    :forbidden="overview.forbidden.value"
    :error-message="overview.errorMessage.value"
    :request-id="overview.requestId.value"
    :is-empty="false"
    loading-title="正在加载券池总览"
    forbidden-title="暂无权限"
    forbidden-description="这个运营账号的令牌不能读取券池总览。"
    @retry="load"
  >
    <template v-if="overview.data.value">
      <div class="ph-pool__grid">
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">模板总数</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.template_count ?? 0 }}</span>
          <span class="ph-field__hint">启用中 {{ overview.data.value.template_active_count ?? 0 }} 个</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">服务者成本模板</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.provider_cost_template_count ?? 0 }}</span>
          <span class="ph-field__hint">额度来自服务者的贡献</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">平台补贴模板</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.platform_subsidy_template_count ?? 0 }}</span>
          <span class="ph-field__hint">额度由平台出，受发放上限约束</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">生效中的贡献</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.contribution_count ?? 0 }}</span>
          <span class="ph-field__hint">
            承诺 {{ overview.data.value.committed_total ?? 0 }} 张 · 还可发 {{ overview.data.value.available_total ?? 0 }} 张
          </span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">已发放</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.issued_total ?? 0 }}</span>
          <span class="ph-field__hint">券实例总数</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">已核销</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.redeemed_total ?? 0 }}</span>
          <span class="ph-field__hint">到店用掉的那部分</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">在用户手里</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.reserved_total ?? 0 }}</span>
          <span class="ph-field__hint">未过期未核销</span>
        </div>
        <div class="ph-card ph-pool__stat">
          <span class="ph-pool__stat-label">已过期作废</span>
          <span class="ph-pool__stat-value">{{ overview.data.value.expired_total ?? 0 }}</span>
          <span class="ph-field__hint">额度已释放回池</span>
        </div>
      </div>

      <div class="ph-card ph-pool__recon" :class="{ 'ph-pool__recon--broken': overview.data.value.reconciliation?.balanced === false }">
        <h4 class="ph-card__title">对账口径（ADR-0037 第三节）</h4>
        <p v-if="overview.data.value.reconciliation" class="ph-pool__equation">
          实例数 {{ overview.data.value.reconciliation.issued ?? 0 }} =
          已核销 {{ overview.data.value.reconciliation.redeemed ?? 0 }} +
          在用户手里 {{ overview.data.value.reconciliation.reserved ?? 0 }} +
          已过期 {{ overview.data.value.reconciliation.expired ?? 0 }}
        </p>
        <p class="ph-pool__verdict">
          <span
            class="ph-tag"
            :class="overview.data.value.reconciliation?.balanced ? 'ph-tag--success' : 'ph-tag--danger'"
          >
            {{ overview.data.value.reconciliation?.balanced ? "账平" : "不平，要人工查" }}
          </span>
          <span class="ph-text-weak">{{ overview.data.value.reconciliation?.note ?? "" }}</span>
        </p>
        <p class="ph-field__hint">
          本项目没有资金可对（ADR-0036）：券的成本归属只影响核销统计与考核，不产生资金流。所以这张表就是券的对账。
        </p>
      </div>

      <h4 class="ph-card__title ph-pool__sub">按来源拆开</h4>
      <p class="ph-field__hint ph-pool__hint">
        「补贴花了多少」与「任务发出去多少」是两笔不同的账（一笔平台出、一笔服务者出），所以来源要分开看。
      </p>
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>来源</th>
              <th>已发放</th>
              <th>已核销</th>
              <th>在用户手里</th>
              <th>已过期</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in overview.data.value.by_source ?? []" :key="row.source ?? 0">
              <td>{{ sourceLabel(row.source ?? 0) }}</td>
              <td class="ph-table__num">{{ row.issued ?? 0 }}</td>
              <td class="ph-table__num">{{ row.redeemed ?? 0 }}</td>
              <td class="ph-table__num">{{ row.reserved ?? 0 }}</td>
              <td class="ph-table__num">{{ row.expired ?? 0 }}</td>
            </tr>
            <tr v-if="(overview.data.value.by_source ?? []).length === 0">
              <td colspan="5">还没有发出过券：各来源都由各自的动作触发（邀请 / 打卡 / 兑换 / 平台补贴）。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </ConsoleListState>
</template>

<style scoped>
.ph-pool__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ph-space-4);
  margin-bottom: var(--ph-space-5);
}

.ph-pool__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-pool__stat-label {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-pool__stat-value {
  font-family: var(--ph-font-numeric);
  font-size: 24px;
  font-weight: 600;
}

.ph-pool__recon {
  margin-bottom: var(--ph-space-5);
}

.ph-pool__recon--broken {
  border-color: var(--ph-color-danger);
}

.ph-pool__equation {
  margin: 0 0 var(--ph-space-2);
  font-family: var(--ph-font-numeric);
}

.ph-pool__verdict {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  margin: 0 0 var(--ph-space-2);
}

.ph-pool__sub {
  margin: 0 0 var(--ph-space-2);
  font-size: 14px;
  font-weight: 600;
}

.ph-pool__hint {
  margin: 0 0 var(--ph-space-3);
}
</style>
