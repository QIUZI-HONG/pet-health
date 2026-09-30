<script setup lang="ts">
/**
 * 结算中心（交付文档 3.2 P028，路由 /b/settle）：**对账视图**。
 *
 * ⚠️ 与交付文档的刻意偏离（ADR-0036，维持 ADR-0002）：交付文档写的是「余额 / 明细 / 提现 / 对账」，
 * 但本项目**钱在门店付、平台不经手资金**——所以这一页**没有余额、没有提现、没有退款单**。
 * ADR-0036 的 Consequences 把这件事说得最清楚：「没有资金流可对，对账退化为**履约与券核销的核对**」。
 * 改这一页时别把余额与提现加回来：摆一个余额数字（哪怕是从接口算的）就等于声称平台收了钱。
 *
 * 两段账，都是真实接口：
 *   - **券的发放与核销明细**：契约里 `GET /coupon-contributions/{id}/coupons` 的 summary 就写着
 *     这是「服务者的对账视图」——哪些券发出去了、哪张在本店核销了、哪张过期作废，
 *     并给出对账恒等式 `已发放 = 已核销 + 占用中 + 已过期`（等式不成立时页面要看得见）；
 *   - **订单流水**：按状态列本店订单与「到店应收（预估实付）」——它是**到店收款的口径**，
 *     不是平台的收款事实，所以这一列的标题必须带「到店」。
 *
 * 服务者成本券的核销只在本店发生（ADR-0037 第三节）；券只是到店抵扣凭证，不产生资金流。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList } from "@pet-health/ui";
import { formatAmount, formatDateTime } from "@pet-health/shared";
import { providerApp, type ContributionRow, type CouponRow, type OrderRow } from "../api/providerApi";
import { useProviderSession } from "../session";
import {
  completionRateText,
  contributionBalanced,
  contributionStatusLabel,
  contributionStatusTone,
  couponSourceLabel,
  couponStatusLabel,
  couponStatusTone,
  orderStatusLabel,
  orderStatusTone,
} from "../utils/labels";

const { status } = useProviderSession();

// ---- 一、券的发放与核销明细（选定一条贡献看它的账）----
const contributions = usePagedList<ContributionRow>(
  ({ page, pageSize }, signal) => providerApp.listCouponContributions({ page, pageSize }, signal),
  { pageSize: 10, failureText: "券贡献加载失败，请稍后重试" },
);
const selected = ref<ContributionRow | null>(null);
const couponStatusFilter = ref("");
const coupons = usePagedList<CouponRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listContributionCoupons(
      selected.value?.id ?? 0,
      { status: couponStatusFilter.value === "" ? undefined : Number(couponStatusFilter.value), page, pageSize },
      signal,
    ),
  { pageSize: 10, failureText: "券的发放与核销明细加载失败，请稍后重试" },
);

function selectContribution(row: ContributionRow): void {
  selected.value = row;
  couponStatusFilter.value = "";
  void coupons.reload();
}

/** 对账恒等式的三种结果：对上了 / 对不上 / 字段不全判不了——「不知道」不能显示成「已核对」 */
function balanceText(row: ContributionRow): string {
  const balanced = contributionBalanced(row);
  if (balanced === null) return "数据不全，判不了（后端缺额度字段）";
  return balanced ? "恒等式核对通过" : "恒等式对不上：已发放 ≠ 已核销 + 占用中 + 已过期，请报障";
}

function balanceTone(row: ContributionRow): string {
  const balanced = contributionBalanced(row);
  if (balanced === null) return "ph-tag--warning";
  return balanced ? "ph-tag--success" : "ph-tag--danger";
}

// ---- 二、订单流水 ----
const orderStatusFilter = ref("");
const orders = usePagedList<OrderRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listOrders(
      { status: orderStatusFilter.value === "" ? undefined : Number(orderStatusFilter.value), page, pageSize },
      signal,
    ),
  { failureText: "订单流水加载失败，请稍后重试" },
);

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（StandardView 踩过这个坑）。
// 两段账都要首屏就有——对账页空着会让人以为「这个月没发生什么」。
onMounted(() => {
  if (status.value !== "authenticated") return;
  void contributions.reload();
  void orders.reload();
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">结算中心</h2>
    <p class="ph-page-desc">
      对账视图：订单流水与券的核销明细，用来核对「这一天做了什么服务、用掉了哪些券」。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <p class="ph-alert ph-alert--info ph-settle__notice">
        <strong>这一页没有余额，也没有提现。</strong>
        本项目的钱在门店直接付给服务者，平台不经手资金（ADR-0036，维持 ADR-0002）——所以对账在这里的
        含义是履约与券核销的核对（订单对报工、券实例对核销记录），不是资金流水。订单上的
        「到店应收（预估实付）」只是展示与提醒，不是平台的收款事实，也永远不会有退款单。
      </p>

      <!-- ============ 一、券的发放与核销明细 ============ -->
      <section class="ph-card ph-settle__card">
        <h3 class="ph-card__title">券的发放与核销明细</h3>
        <p class="ph-text-sub">
          先选一条券贡献（一次承诺就是一条额度账），再看它的券去哪了。额度账与明细是同一份事实的两个角度：
          账上说「发出 12 张」，明细里就该数得出 12 张券。
        </p>

        <ConsoleListState
          :loading="contributions.loading.value"
          :forbidden="contributions.forbidden.value"
          :error-message="contributions.errorMessage.value"
          :request-id="contributions.requestId.value"
          :is-empty="contributions.isEmpty.value"
          loading-title="正在加载券贡献"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店的券贡献。"
          empty-title="还没有券贡献"
          empty-description="没有贡献就没有可对账的券：去「券管理 → 券池模板」挑一张服务者成本的券并承诺额度。"
          @retry="contributions.reload"
        >
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>券</th>
                  <th>承诺 / 已发放</th>
                  <th>已核销</th>
                  <th>占用中</th>
                  <th>已过期</th>
                  <th>完成率</th>
                  <th>核对</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in contributions.items.value" :key="row.id">
                  <td>
                    {{ row.template_name ?? row.template_code }}
                    <span class="ph-text-weak ph-settle__sub">
                      {{ contributionStatusLabel(row.status) }} · 面额 {{ formatAmount(row.face_value) }}
                    </span>
                  </td>
                  <td class="ph-table__num">{{ row.total_count }} / {{ row.issued_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ row.redeemed_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ row.reserved_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ row.expired_count ?? 0 }}</td>
                  <td class="ph-table__num">{{ completionRateText(row.completion_rate) }}</td>
                  <td>
                    <span class="ph-tag" :class="[contributionStatusTone(row.status), balanceTone(row)]">
                      {{ balanceText(row) }}
                    </span>
                  </td>
                  <td>
                    <button type="button" class="ph-table__action" @click="selectContribution(row)">看明细</button>
                  </td>
                </tr>
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

        <section v-if="selected" class="ph-settle__detail">
          <div class="ph-toolbar">
            <strong>明细：{{ selected.template_name ?? selected.template_code }}</strong>
            <label class="ph-field ph-settle__filter">
              <span class="ph-field__label">券状态</span>
              <select v-model="couponStatusFilter" class="ph-select" @change="coupons.reload">
                <option value="">全部</option>
                <option value="1">待使用</option>
                <option value="2">已锁定</option>
                <option value="3">已核销</option>
                <option value="4">已过期</option>
              </select>
            </label>
          </div>

          <ConsoleListState
            :loading="coupons.loading.value"
            :forbidden="coupons.forbidden.value"
            :error-message="coupons.errorMessage.value"
            :request-id="coupons.requestId.value"
            :is-empty="coupons.isEmpty.value"
            loading-title="正在加载券明细"
            forbidden-title="暂无权限"
            forbidden-description="这个账号的令牌不能读取这张券的发放明细。"
            empty-title="这一条贡献还没有发出去的券"
            empty-description="券由平台定向发放（邀请 / 打卡任务 / 积分兑换）；服务者只承诺额度，不负责发放。"
            @retry="coupons.reload"
          >
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>券码</th>
                    <th>面额 / 门槛</th>
                    <th>来源</th>
                    <th>状态</th>
                    <th>发放时间</th>
                    <th>有效期至</th>
                    <th>核销时间</th>
                    <th>领券人</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="coupon in coupons.items.value" :key="coupon.id">
                    <td class="ph-table__num">{{ coupon.code ?? "—" }}</td>
                    <td class="ph-table__num">
                      {{ formatAmount(coupon.face_value) }}
                      <span class="ph-text-weak ph-settle__sub">满 {{ formatAmount(coupon.min_amount) }} 可用</span>
                    </td>
                    <td>{{ couponSourceLabel(coupon.source) }}</td>
                    <td>
                      <span class="ph-tag" :class="couponStatusTone(coupon.status)">{{ couponStatusLabel(coupon.status) }}</span>
                    </td>
                    <td class="ph-table__num">{{ formatDateTime(coupon.issued_at) }}</td>
                    <td class="ph-table__num">{{ formatDateTime(coupon.valid_until) }}</td>
                    <td class="ph-table__num">{{ formatDateTime(coupon.redeemed_at) || "—" }}</td>
                    <td>用户 #{{ coupon.user_id ?? "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="ph-pager">
              <span>共 {{ coupons.total.value }} 张</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="coupons.page.value <= 1 || coupons.loading.value"
                @click="coupons.prevPage"
              >
                上一页
              </button>
              <span>第 {{ coupons.page.value }} 页</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="!coupons.hasMore.value || coupons.loading.value"
                @click="coupons.nextPage"
              >
                下一页
              </button>
            </div>
          </ConsoleListState>

          <p class="ph-text-weak">
            领券人的身份不在服务者的可见范围内，明细里只有 `user_id`（契约明文）——对账要的是「券到没到、用没用到」，
            不是「谁领的」。过期未核销的券会把额度释放回池（`available_count` 因此回升），已核销的不释放。
          </p>
        </section>
      </section>

      <!-- ============ 二、订单流水 ============ -->
      <section class="ph-card ph-settle__card">
        <h3 class="ph-card__title">订单流水</h3>
        <p class="ph-text-sub">
          按状态列本店订单，按履约日期核对「这一天做了什么服务」。金额列是到店应收（总额 − 券面额），
          到店收多少由门店与用户当面结清——平台不记录收款，也不产生退款单。
        </p>
        <div class="ph-toolbar">
          <label class="ph-field ph-settle__filter">
            <span class="ph-field__label">状态</span>
            <select v-model="orderStatusFilter" class="ph-select" @change="orders.reload">
              <option value="">全部</option>
              <option value="0">待接单</option>
              <option value="1">已预约</option>
              <option value="2">履约中</option>
              <option value="3">已完成</option>
              <option value="4">已取消</option>
            </select>
          </label>
        </div>

        <ConsoleListState
          :loading="orders.loading.value"
          :forbidden="orders.forbidden.value"
          :error-message="orders.errorMessage.value"
          :request-id="orders.requestId.value"
          :is-empty="orders.isEmpty.value"
          loading-title="正在加载订单流水"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店订单。"
          empty-title="这一段没有订单"
          empty-description="用户下单后，订单会按履约日期出现在这里；已完成的订单是报工完成的那一批。"
          @retry="orders.reload"
        >
          <div class="ph-table-wrap">
            <table class="ph-table">
              <thead>
                <tr>
                  <th>订单</th>
                  <th>履约日期</th>
                  <th>客户 / 宠物</th>
                  <th>服务项</th>
                  <th>总额</th>
                  <th>券抵扣</th>
                  <th>到店应收</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in orders.items.value" :key="row.id">
                  <td class="ph-table__num">{{ row.order_no }}</td>
                  <td class="ph-table__num">{{ row.appointment_date }}</td>
                  <td>{{ row.user_nickname ?? "—" }} · {{ row.pet_name ?? "—" }}</td>
                  <td>{{ row.service_name ?? "—" }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.total_amount) }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.coupon_discount) }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.estimated_pay_amount) }}</td>
                  <td><span class="ph-tag" :class="orderStatusTone(row.status)">{{ orderStatusLabel(row.status) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="ph-pager">
            <span>共 {{ orders.total.value }} 单</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="orders.page.value <= 1 || orders.loading.value"
              @click="orders.prevPage"
            >
              上一页
            </button>
            <span>第 {{ orders.page.value }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!orders.hasMore.value || orders.loading.value"
              @click="orders.nextPage"
            >
              下一页
            </button>
          </div>
        </ConsoleListState>

        <p class="ph-text-weak ph-settle__foot">
          这一页没有「导出账单」按钮：契约里没有对账单 / 导出接口。要做导出得先在契约里定义它
          （以及导出的是哪几列、按什么口径），否则导出的文件会变成一份没人能核对的野生报表。
        </p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-settle__notice {
  margin-bottom: var(--ph-space-5);
}

.ph-settle__card {
  margin-bottom: var(--ph-space-5);
}

.ph-settle__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-settle__filter .ph-select {
  width: auto;
  min-width: 120px;
}

.ph-settle__sub {
  display: block;
  font-size: 12px;
}

.ph-settle__detail {
  margin-top: var(--ph-space-5);
}

.ph-settle__foot {
  margin-top: var(--ph-space-3);
}
</style>
