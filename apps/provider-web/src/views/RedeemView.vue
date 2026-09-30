<script setup lang="ts">
/**
 * 核销管理（交付文档 3.2 P021，路由 /b/redeem）：到店核销 + 核销记录。
 *
 * **契约里没有「按核销码核销」的接口**，核销码是**定位订单**的三个入口之一
 * （`GET /orders?keyword=`：订单号按包含匹配，完整手机号与 6 位核销码按精确匹配，ADR-0038 第二节）。
 * 所以这一页是「先查后核」：输入码 → 命中订单 → 对那一单调用 `POST /orders/{id}/redeem`。
 * 契约的核销接口**没有请求体**——核销码不回给服务者，门店不需要知道码本身（记下来才能复用），
 * 这也是为什么界面上不能把码当成核销参数提交。
 *
 * 三条要写在页面上的事：
 *   - **核销 ≠ 收款**（ADR-0036 / ADR-0038 第二节）：核销是履约确认，费用在门店直接付给服务者；
 *   - **核销后不可撤销**：订单进入「履约中」后既不能取消也不能退回，只能报工完成或由运营干预；
 *   - **重复核销会被拒**（40900，不是幂等成功）——结果是「这一单已经核销过了」，要如实转述。
 *
 * 【假设】「扫码」在本项目里就是**把码填进输入框**（扫码枪/手机键盘都是输入手段）：契约里没有
 * 硬件相关的接口，门店也不需要扫码枪。这里不做摄像头取景框——那是设备能力，不是业务接口。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { formatAmount, formatDate, formatDateTime, speciesLabel } from "@pet-health/shared";
import { providerApp, type OrderRow, type OrderView } from "../api/providerApi";
import { useProviderSession } from "../session";
import { canRedeemOrder, orderStatusLabel, orderStatusTone } from "../utils/labels";

const { status } = useProviderSession();

/** 输入框里的码与**已经查过**的码分开：边打字边查会把「1380」打成四次查询 */
const codeInput = ref("");
const searched = ref(false);

/**
 * 命中结果用一个 5 条上限的分页列表：同一个手机号可能有多单待履约，只显示第一条会让门店
 * 在错误的单子上核销。`usePagedList` 的四态（含 40300 的 forbidden 分支）直接用，不另写一套。
 */
const lookup = usePagedList<OrderRow>(
  ({ page, pageSize }, signal) => {
    const keyword = codeInput.value.trim();
    return providerApp.listOrders({ keyword, page, pageSize }, signal);
  },
  { pageSize: 5, failureText: "核销定位失败，请稍后重试" },
);

/** 核销记录：契约没有「核销记录」接口，只有订单状态筛选——「履约中（已核销待报工）」与「已完成」两段 */
const recordStatus = ref("2");
const records = usePagedList<OrderRow>(
  ({ page, pageSize }, signal) => providerApp.listOrders({ status: Number(recordStatus.value), page, pageSize }, signal),
  { failureText: "核销记录加载失败，请稍后重试" },
);

const submit = useSubmitAction("核销失败，请稍后重试");
/** 核销成功后服务端返回的订单详情：结果面板要能说清「这一单现在是什么状态、到店应收多少」 */
const result = ref<OrderView | null>(null);

function search(): void {
  if (codeInput.value.trim() === "") return;
  submit.clear();
  result.value = null;
  searched.value = true;
  void lookup.reload();
}

/** 换一段记录就回第一页：否则第 3 页切到只有 1 页的数据段会看到空表格 */
function switchRecord(status: string): void {
  recordStatus.value = status;
  void records.reload();
}

async function redeem(row: OrderRow): Promise<void> {
  const outcome = await submit.run(() => providerApp.redeemOrder(row.id), `已核销：${row.order_no}`);
  if (!outcome.ok) return;
  result.value = outcome.value;
  void lookup.reload();
  void records.reload();
}

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（StandardView 踩过这个坑）。
// 只有核销记录要首屏加载；上面的定位区必须先等用户输入（空关键字发出去等于查全部订单）。
onMounted(() => {
  if (status.value === "authenticated") {
    void records.reload();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">核销管理</h2>
    <p class="ph-page-desc">
      用户到店后凭核销码确认本次预约已被使用。输入核销码 / 完整手机号 / 订单号即可定位订单，不需要扫码枪。核销与收款无关——费用在门店直接付给服务者（ADR-0036）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <section class="ph-card ph-redeem__lookup">
        <h3 class="ph-card__title">到店核销</h3>
        <p class="ph-text-sub">
          核销码不回给服务者，门店凭用户出示的码定位订单即可；同一手机号可能有多单待履约，命中多条时要看清是哪一单再核销。
        </p>
        <form class="ph-toolbar ph-redeem__form" @submit.prevent="search">
          <label class="ph-field ph-redeem__input">
            <span class="ph-field__label">核销码 / 手机号 / 订单号</span>
            <input
              v-model="codeInput"
              class="ph-input"
              placeholder="如 483920 或 13800008888"
              maxlength="32"
              autocomplete="off"
            />
          </label>
          <button type="submit" class="ph-button ph-button--primary" :disabled="lookup.loading.value || codeInput.trim() === ''">
            {{ lookup.loading.value ? "查询中…" : "查询订单" }}
          </button>
        </form>

        <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-redeem__alert">
          {{ submit.errorMessage.value }}
          <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
        </p>
        <p v-if="submit.forbidden.value" class="ph-alert ph-alert--warn ph-redeem__alert">
          当前账号不能核销（40300）：核销归「服务者管理员 + 技师」（ADR-0037 第一节）。
        </p>

        <!-- 还没查过时不摆空态：空态会被读成「这个码没有对应订单」 -->
        <p v-if="!searched" class="ph-text-weak">输入核销码后，命中的订单会显示在这里。</p>
        <ConsoleListState
          v-else
          :loading="lookup.loading.value"
          :forbidden="lookup.forbidden.value"
          :error-message="lookup.errorMessage.value"
          :request-id="lookup.requestId.value"
          :is-empty="lookup.isEmpty.value"
          loading-title="正在定位订单"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店订单。"
          empty-title="没有命中的订单"
          empty-description="核销码 / 手机号按精确匹配、订单号按包含匹配。确认码是否输错，或让用户在 App 的订单详情里重新出示。"
          @retry="lookup.reload"
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
                <tr v-for="row in lookup.items.value" :key="row.id">
                  <td class="ph-table__num">{{ row.order_no }}</td>
                  <td class="ph-table__num">
                    {{ formatDate(row.appointment_date) }}
                    <span class="ph-text-weak ph-redeem__sub">{{ row.start_time }}–{{ row.end_time }}</span>
                  </td>
                  <td>
                    {{ row.user_nickname ?? "—" }}
                    <span class="ph-text-weak ph-redeem__sub">
                      {{ row.user_phone ?? "—" }} · {{ row.pet_name ?? "—" }}（{{ speciesLabel(row.pet_species) }}）
                    </span>
                  </td>
                  <td>{{ row.service_name ?? "—" }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.estimated_pay_amount) }}</td>
                  <td><span class="ph-tag" :class="orderStatusTone(row.status)">{{ orderStatusLabel(row.status) }}</span></td>
                  <td>
                    <button
                      type="button"
                      class="ph-table__action"
                      :disabled="!canRedeemOrder(row.status) || submit.submitting.value"
                      :title="canRedeemOrder(row.status) ? '' : '只有「已预约」能核销'"
                      @click="redeem(row)"
                    >
                      {{ submit.submitting.value ? "核销中…" : "核销" }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </ConsoleListState>

        <section v-if="result" class="ph-alert ph-alert--info ph-redeem__result">
          <strong>核销成功：{{ result.order_no }}</strong>
          <p class="ph-text-sub">
            订单进入「{{ orderStatusLabel(result.status) }}」（{{ formatDateTime(result.redeemed_at) || "时间待服务端返回" }}），
            本单锁定的券已转「已核销」。核销后不可撤销：履约中既不能取消也不能退回，只能报工完成或由运营干预。
          </p>
          <dl class="ph-kv">
            <dt>服务总额</dt>
            <dd>{{ formatAmount(result.total_amount) }}</dd>
            <dt>券抵扣</dt>
            <dd>{{ formatAmount(result.coupon_discount) }}</dd>
            <dt>到店应收（预估实付）</dt>
            <dd>{{ formatAmount(result.estimated_pay_amount) }}</dd>
          </dl>
          <p class="ph-redeem__money">本次服务费用请在门店直接付给服务者——核销只是履约确认，平台不经手资金（ADR-0036）。</p>
        </section>
      </section>

      <section class="ph-card ph-redeem__records">
        <h3 class="ph-card__title">核销记录</h3>
        <p class="ph-text-sub">
          契约没有单独的「核销记录」接口，核销走的是订单状态（已预约 → 履约中 → 已完成），所以这一段按状态列：
          核销时间只在订单详情里，列表接口不返回它。
        </p>
        <nav class="ph-tabs" aria-label="核销记录分段">
          <button
            type="button"
            class="ph-tabs__item"
            :class="{ 'ph-tabs__item--active': recordStatus === '2' }"
            @click="switchRecord('2')"
          >
            已核销（履约中）
          </button>
          <button
            type="button"
            class="ph-tabs__item"
            :class="{ 'ph-tabs__item--active': recordStatus === '3' }"
            @click="switchRecord('3')"
          >
            已完成
          </button>
        </nav>

        <ConsoleListState
          :loading="records.loading.value"
          :forbidden="records.forbidden.value"
          :error-message="records.errorMessage.value"
          :request-id="records.requestId.value"
          :is-empty="records.isEmpty.value"
          loading-title="正在加载核销记录"
          forbidden-title="暂无权限"
          forbidden-description="这个账号的令牌不能读取本店订单。"
          empty-title="这一段还没有记录"
          empty-description="用户到店核销后，订单会出现在「已核销（履约中）」；报工完成后移到「已完成」。"
          @retry="records.reload"
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
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in records.items.value" :key="row.id">
                  <td class="ph-table__num">{{ row.order_no }}</td>
                  <td class="ph-table__num">{{ formatDate(row.appointment_date) }}</td>
                  <td>{{ row.user_nickname ?? "—" }} · {{ row.pet_name ?? "—" }}</td>
                  <td>{{ row.service_name ?? "—" }}</td>
                  <td class="ph-table__num">{{ formatAmount(row.estimated_pay_amount) }}</td>
                  <td><span class="ph-tag" :class="orderStatusTone(row.status)">{{ orderStatusLabel(row.status) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="ph-pager">
            <span>共 {{ records.total.value }} 单</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="records.page.value <= 1 || records.loading.value"
              @click="records.prevPage"
            >
              上一页
            </button>
            <span>第 {{ records.page.value }} 页</span>
            <button
              type="button"
              class="ph-button ph-button--secondary"
              :disabled="!records.hasMore.value || records.loading.value"
              @click="records.nextPage"
            >
              下一页
            </button>
          </div>
        </ConsoleListState>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-redeem__lookup {
  margin-bottom: var(--ph-space-5);
}

.ph-redeem__form {
  margin-top: var(--ph-space-4);
}

.ph-redeem__input {
  min-width: 280px;
}

.ph-redeem__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-redeem__sub {
  display: block;
  font-size: 12px;
}

.ph-redeem__result {
  margin-top: var(--ph-space-4);
}

.ph-redeem__money {
  margin: var(--ph-space-3) 0 0;
}
</style>
