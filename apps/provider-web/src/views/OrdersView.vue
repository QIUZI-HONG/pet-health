<script setup lang="ts">
/**
 * 订单管理（交付文档 3.2 P020，路由 /b/orders）：本店订单的日常操作台。
 *
 * 状态机（ADR-0038 第一节 / ADR-0048）：`0 待接单 →接单→ 1 已预约 →核销→ 2 履约中 →报工→ 3 已完成`，
 * 另有 `4 已取消`。每条迁移在服务端都是一条带源状态的条件更新，非法迁移一律 **40900**——
 * 所以按钮的可用性按「当前状态」判（`utils/labels` 的 `can*` 一族），把不能点的原因写在旁边，
 * 而不是让用户点了再吃一个「状态冲突」（重试也还是同样的拒绝）。
 *
 * 三个口径必须写在这一页上（不做成别处的说明文字）：
 *   - **核销 ≠ 收款**：核销是履约确认，钱在门店直接付给服务者（ADR-0036 / ADR-0038 第二节）；
 *   - **取消分阶段**：待接单可直接取消；已预约阶段用户发起的取消**要门店同意**（拒绝必填理由，
 *     ADR-0038 第一节），同意 / 拒绝只在「有待处理申请」时有效；履约中不可取消，只能报工；
 *   - **报工要先过三道照片墙**：照片直传文件域（服务者侧的 `/files/presign`，切片 #107），
 *     再把 `file_id` 挂到槽位上，缺哪一道由服务端点名。照片墙与报工都在
 *     {@link OrderFulfillmentPanel} 里，这一页只在「履约中」的行上给入口。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatDate, formatDateTime, speciesLabel } from "@pet-health/shared";
import { providerApp, type OrderRow, type OrderView } from "../api/providerApi";
import { useProviderSession } from "../session";
import OrderFulfillmentPanel from "../components/OrderFulfillmentPanel.vue";
import {
  canAcceptOrder,
  canCancelOrder,
  canOpenWall,
  canRedeemOrder,
  cancelOrderHint,
  cancelRequestLabel,
  needsCancelDecision,
  orderStatusLabel,
  orderStatusTone,
} from "../utils/labels";

const { status } = useProviderSession();

const statusFilter = ref("");
const dateFilter = ref("");
/** 输入框里的关键字与**已经发给服务端**的关键字分开：边打字边发请求会把「138」打成十几次查询 */
const keyword = ref("");
const appliedKeyword = ref("");

const orders = usePagedList<OrderRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listOrders(
      {
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        appointmentDate: dateFilter.value === "" ? undefined : dateFilter.value,
        keyword: appliedKeyword.value === "" ? undefined : appliedKeyword.value,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "订单加载失败，请稍后重试" },
);

const submit = useSubmitAction("操作失败，请稍后重试");
/** 最后一次核销返回的订单详情：核销结果要看得见（状态变了、券转已核销、到店应收多少） */
const redeemed = ref<OrderView | null>(null);
/** 正在填理由的取消动作：门店取消（cancel）与拒绝用户的取消申请（reject）共用一张表单 */
const reasonTarget = ref<{ kind: "cancel" | "reject"; row: OrderRow } | null>(null);
const reason = ref("");
/** 展开照片墙与报工的订单：非空时页面下方出现那一条订单的履约面板 */
const wallTarget = ref<OrderRow | null>(null);

function reload(): void {
  void orders.reload();
}

function applySearch(): void {
  appliedKeyword.value = keyword.value.trim();
  reload();
}

/** 打开照片墙面板：三条「一步到位」的提示先清掉，免得旧提示被当成这一次的结果 */
function openWall(row: OrderRow): void {
  submit.clear();
  wallTarget.value = row;
}

/** 面板里报工成功：列表要刷新（这一行转「已完成」），面板自己显示已报工的结果 */
function onReported(): void {
  reload();
}

/** 三个「一步到位」的动作共用一个收尾：成功就刷新列表，并让核销的返回详情留在页面上 */
async function runAction(action: () => Promise<OrderView>, successText: string): Promise<void> {
  const outcome = await submit.run(action, successText);
  if (!outcome.ok) return;
  redeemed.value = outcome.value;
  reload();
}

function accept(row: OrderRow): void {
  // 接单即承诺：这个号源被这一单占住，之后用户再取消需要门店同意 —— 这件事要在提示里说出来
  void runAction(() => providerApp.acceptOrder(row.id), `已接单：${row.order_no}（接单即承诺，用户此后取消需门店同意）`);
}

function redeem(row: OrderRow): void {
  redeemed.value = null;
  void runAction(() => providerApp.redeemOrder(row.id), `已核销：${row.order_no}`);
}

function openReason(row: OrderRow, kind: "cancel" | "reject"): void {
  submit.clear();
  reason.value = "";
  reasonTarget.value = { kind, row };
}

/** 门店取消与拒绝取消申请都**必须填理由**（前者展示给用户并计入考核，后者 ADR 点名必填） */
async function submitReason(): Promise<void> {
  const target = reasonTarget.value;
  if (!target) return;
  if (reason.value.trim() === "") {
    submit.errorMessage.value =
      target.kind === "cancel"
        ? "取消要填理由：它会展示给用户，并计入考核的过程分。"
        : "拒绝取消申请要填理由：它会展示给用户。";
    return;
  }
  const outcome = await submit.run(
    () =>
      target.kind === "cancel"
        ? providerApp.cancelOrder(target.row.id, reason.value.trim())
        : providerApp.rejectOrderCancel(target.row.id, reason.value.trim()),
    target.kind === "cancel" ? "已取消订单" : "已拒绝用户的取消申请：订单仍在「已预约」",
  );
  if (!outcome.ok) return;
  reasonTarget.value = null;
  reload();
}

async function approveCancel(row: OrderRow): Promise<void> {
  const outcome = await submit.run(() => providerApp.approveOrderCancel(row.id), `已同意取消：${row.order_no}`);
  if (!outcome.ok) return;
  reload();
}

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（StandardView 踩过这个坑）
onMounted(() => {
  if (status.value === "authenticated") {
    reload();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">订单管理</h2>
    <p class="ph-page-desc">
      本店订单的接单、核销、取消与履约状态。核销只确认「这次预约已被使用」，与收款无关——费用在门店直接付给服务者（ADR-0036）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-orders__filter">
          <span class="ph-field__label">状态</span>
          <select v-model="statusFilter" class="ph-select" @change="reload">
            <option value="">全部</option>
            <option value="0">待接单</option>
            <option value="1">已预约</option>
            <option value="2">履约中</option>
            <option value="3">已完成</option>
            <option value="4">已取消</option>
          </select>
        </label>
        <label class="ph-field ph-orders__filter">
          <span class="ph-field__label">履约日期</span>
          <input v-model="dateFilter" type="date" class="ph-input" @change="reload" />
        </label>
        <label class="ph-field ph-orders__search">
          <span class="ph-field__label">定位订单</span>
          <input
            v-model="keyword"
            class="ph-input"
            placeholder="核销码 / 完整手机号 / 订单号"
            maxlength="32"
            @keyup.enter="applySearch"
          />
        </label>
        <button type="button" class="ph-button ph-button--secondary" @click="applySearch">查询</button>
        <span class="ph-toolbar__spacer" />
        <button type="button" class="ph-button ph-button--secondary" :disabled="orders.loading.value" @click="reload">
          刷新
        </button>
      </div>

      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-orders__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-else-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-orders__alert">
        {{ submit.doneMessage.value }}
      </p>
      <p v-if="submit.forbidden.value" class="ph-alert ph-alert--warn ph-orders__alert">
        当前账号不能执行这个操作（40300）：接单 / 核销 / 报工归「服务者管理员 + 技师」（ADR-0037 第一节）。
      </p>

      <ConsoleListState
        :loading="orders.loading.value"
        :forbidden="orders.forbidden.value"
        :error-message="orders.errorMessage.value"
        :request-id="orders.requestId.value"
        :is-empty="orders.isEmpty.value"
        loading-title="正在加载订单"
        forbidden-title="暂无权限"
        forbidden-description="这个账号的令牌不能读取本店订单。"
        empty-title="还没有订单"
        empty-description="用户在小程序端下单并选好时段后，订单会出现在这里；待接单的要先接单，接单之后用户再取消就需要门店同意。"
        @retry="reload"
      >
        <div class="ph-table-wrap">
          <table class="ph-table">
            <thead>
              <tr>
                <th>订单</th>
                <th>履约时间</th>
                <th>客户 / 宠物</th>
                <th>服务项</th>
                <th>到店应收</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in orders.items.value" :key="row.id">
                <td class="ph-table__num">
                  {{ row.order_no }}
                  <span class="ph-text-weak ph-orders__sub">下单 {{ formatDateTime(row.created_at) }}</span>
                </td>
                <td class="ph-table__num">
                  {{ formatDate(row.appointment_date) }}
                  <span class="ph-text-weak ph-orders__sub">{{ row.start_time }}–{{ row.end_time }}</span>
                </td>
                <td>
                  {{ row.user_nickname ?? "—" }}
                  <span class="ph-text-weak ph-orders__sub">
                    {{ row.user_phone ?? "—" }} · {{ row.pet_name ?? "—" }}（{{ speciesLabel(row.pet_species) }}）
                  </span>
                </td>
                <td>{{ row.service_name ?? "—" }}</td>
                <td class="ph-table__num">
                  {{ formatAmount(row.estimated_pay_amount) }}
                  <span class="ph-text-weak ph-orders__sub">
                    总额 {{ formatAmount(row.total_amount) }} · 券抵 {{ formatAmount(row.coupon_discount) }}
                  </span>
                </td>
                <td>
                  <span class="ph-tag" :class="orderStatusTone(row.status)">{{ orderStatusLabel(row.status) }}</span>
                  <span
                    v-if="cancelRequestLabel(row.cancel_request_status)"
                    class="ph-orders__sub ph-tag ph-tag--warning"
                  >
                    {{ cancelRequestLabel(row.cancel_request_status) }}
                  </span>
                </td>
                <td>
                  <div class="ph-table__actions">
                    <button
                      type="button"
                      class="ph-table__action"
                      :disabled="!canAcceptOrder(row.status) || submit.submitting.value"
                      :title="canAcceptOrder(row.status) ? '' : '只有「待接单」能接单'"
                      @click="accept(row)"
                    >
                      接单
                    </button>
                    <button
                      type="button"
                      class="ph-table__action"
                      :disabled="!canRedeemOrder(row.status) || submit.submitting.value"
                      :title="canRedeemOrder(row.status) ? '' : '只有「已预约」能核销（重复核销会被拒）'"
                      @click="redeem(row)"
                    >
                      核销
                    </button>
                    <button
                      type="button"
                      class="ph-table__action"
                      :disabled="!canCancelOrder(row.status) || submit.submitting.value"
                      :title="cancelOrderHint(row.status)"
                      @click="openReason(row, 'cancel')"
                    >
                      取消
                    </button>
                    <template v-if="needsCancelDecision(row.cancel_request_status)">
                      <button
                        type="button"
                        class="ph-table__action"
                        :disabled="submit.submitting.value"
                        @click="approveCancel(row)"
                      >
                        同意取消
                      </button>
                      <button
                        type="button"
                        class="ph-table__action"
                        :disabled="submit.submitting.value"
                        @click="openReason(row, 'reject')"
                      >
                        拒绝取消
                      </button>
                    </template>
                    <button
                      v-if="canOpenWall(row.status)"
                      type="button"
                      class="ph-table__action"
                      @click="openWall(row)"
                    >
                      {{ row.status === 3 ? "看照片墙" : "照片墙 / 报工" }}
                    </button>
                  </div>
                  <span v-if="cancelOrderHint(row.status)" class="ph-text-weak ph-orders__sub">
                    {{ cancelOrderHint(row.status) }}
                  </span>
                </td>
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

      <form v-if="reasonTarget" class="ph-card ph-orders__panel" @submit.prevent="submitReason">
        <h3 class="ph-card__title">
          {{ reasonTarget.kind === "cancel" ? "门店取消订单" : "拒绝用户的取消申请" }}：{{ reasonTarget.row.order_no }}
        </h3>
        <p class="ph-alert ph-alert--warn">
          {{
            reasonTarget.kind === "cancel"
              ? "取消后号源与本单锁定的券一并释放；理由会展示给用户，并计入考核的过程分（ADR-0049）。"
              : "拒绝后订单仍是「已预约」，门店要按约履约；理由会展示给用户。"
          }}
        </p>
        <label class="ph-field ph-orders__reason">
          <span class="ph-field__label">理由（必填）</span>
          <input v-model="reason" class="ph-input" maxlength="255" />
        </label>
        <div class="ph-orders__actions">
          <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
            {{ submit.submitting.value ? "提交中…" : "提交" }}
          </button>
          <button type="button" class="ph-button ph-button--secondary" @click="reasonTarget = null">放弃</button>
        </div>
      </form>

      <section v-if="redeemed" class="ph-card ph-orders__panel">
        <h3 class="ph-card__title">核销结果：{{ redeemed.order_no }}</h3>
        <p class="ph-text-sub">
          订单已进入「{{ orderStatusLabel(redeemed.status) }}」；本单锁定的券在同一事务里转「已核销」（ADR-0038 第二节）。
        </p>
        <dl class="ph-kv">
          <dt>服务总额</dt>
          <dd>{{ formatAmount(redeemed.total_amount) }}</dd>
          <dt>券抵扣</dt>
          <dd>{{ formatAmount(redeemed.coupon_discount) }}</dd>
          <dt>到店应收（预估实付）</dt>
          <dd>{{ formatAmount(redeemed.estimated_pay_amount) }}</dd>
          <dt>核销时间</dt>
          <dd>{{ formatDateTime(redeemed.redeemed_at) || "—" }}</dd>
        </dl>
        <p class="ph-alert ph-alert--info ph-orders__money">
          本次服务费用请在门店直接付给服务者：核销只是履约确认，平台不经手资金（ADR-0036）。
        </p>
      </section>

      <OrderFulfillmentPanel
        v-if="wallTarget"
        :order-id="wallTarget.id"
        :order-no="wallTarget.order_no"
        @done="onReported"
        @close="wallTarget = null"
      />

      <section class="ph-card ph-orders__note">
        <h3 class="ph-card__title">报工与三道照片墙</h3>
        <p class="ph-text-sub">
          「履约中」的订单在这里拍照留痕：接宠检查 / 服务防护 / 取宠对比，每道可传多张、写备注
          （照片先直传文件域，再把 file_id 挂到订单上，字节不经业务接口——ADR-0020）。
          三道各至少一张才允许报工，这条硬约束在服务端：缺一道时后端会拒绝并点名缺的是哪一道，
          界面只是把同一句话转达出来（ADR-0040 第四节）。
        </p>
        <p class="ph-text-sub">
          报工成功后订单转「已完成」，这次服务会以「服务者报工」为来源写入宠物的健康档案
          （F004 的「用户录入 / AI 建议 / 服务者报工」三源之一，ADR-0030）。报工之后照片与备注固化，
          修正只能走运营干预（40901）。
        </p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-orders__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-orders__filter .ph-select,
.ph-orders__filter .ph-input {
  width: auto;
  min-width: 140px;
}

.ph-orders__search {
  min-width: 220px;
}

.ph-orders__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-orders__sub {
  display: block;
  font-size: 12px;
}

.ph-orders__panel {
  margin-top: var(--ph-space-5);
}

.ph-orders__reason {
  max-width: 480px;
  margin-top: var(--ph-space-4);
}

.ph-orders__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

.ph-orders__money {
  margin-top: var(--ph-space-4);
}

.ph-orders__note {
  margin-top: var(--ph-space-6);
}
</style>
