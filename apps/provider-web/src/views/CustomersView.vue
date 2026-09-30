<script setup lang="ts">
/**
 * 客户管理（交付文档 3.2 P024，路由 /b/customers）：到店客户的列表与画像，以及档案调取。
 *
 * ⚠️ **契约里没有客户接口**（`contract/provider.yaml` 的 provider 侧只有 orders 一条线能带出
 * 预约人与宠物）。所以这一页**不编一个 `/customers`**，而是把「已经有数据的那部分」做出来：
 * **按订单看到店的客户与宠物**——按手机号 / 订单号 / 核销码定位，看这位客户这次带哪只宠物、
 * 做什么服务、什么时候到店。
 *
 * 缺口（页面下方那段也写着，接上接口后这一页再长出真正的客户维度）：
 *   - **客户画像与聚合**（来过几次、累计到店、上次做了什么、按客户去重的列表）需要一个客户接口，
 *     契约里没有；前端不做「拉一页订单再自己聚合」——那只能聚合到手里这一页，数字会是错的；
 *   - **宠物健康档案调取**：档案是**用户的**数据，调取要带用户授权（ADR-0030 与
 *     docs/conventions.md 的越权口径），边界只能在接口侧落地——不做「先拉全量再挡显示」的假授权。
 *
 * 手机号一律用服务端返回的脱敏值（`138****8888`，ADR-0013）：完整号只在查询参数里过一趟，
 * 本页不存明文、也不在前端做脱敏（那是服务端的责任，前端再脱一次会给「已脱敏」的错觉）。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleListState, usePagedList } from "@pet-health/ui";
import { formatAmount, formatDate, formatDateTime, speciesLabel } from "@pet-health/shared";
import { providerApp, type OrderRow } from "../api/providerApi";
import { useProviderSession } from "../session";
import { orderStatusLabel, orderStatusTone } from "../utils/labels";

const { status } = useProviderSession();

/** 输入框里的关键字与已经查过的分开：边打字边发请求会把「138」打成十几次查询 */
const keyword = ref("");
const appliedKeyword = ref("");

const customers = usePagedList<OrderRow>(
  ({ page, pageSize }, signal) =>
    providerApp.listOrders({ keyword: appliedKeyword.value || undefined, page, pageSize }, signal),
  { pageSize: 10, failureText: "到店记录加载失败，请稍后重试" },
);

function search(): void {
  appliedKeyword.value = keyword.value.trim();
  void customers.reload();
}

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（StandardView 踩过这个坑）
onMounted(() => {
  if (status.value === "authenticated") {
    void customers.reload();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">客户管理</h2>
    <p class="ph-page-desc">
      到店客户：按手机号 / 订单号 / 核销码定位订单，看到店的是谁、带哪只宠物、做了什么服务。客户画像与档案调取需要客户端接口（见下方说明）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-customers__search">
          <span class="ph-field__label">按手机号 / 订单号 / 核销码定位</span>
          <input
            v-model="keyword"
            class="ph-input"
            maxlength="32"
            placeholder="如 13800008888"
            @keyup.enter="search"
          />
        </label>
        <button type="button" class="ph-button ph-button--secondary" @click="search">查询</button>
      </div>

      <ConsoleListState
        :loading="customers.loading.value"
        :forbidden="customers.forbidden.value"
        :error-message="customers.errorMessage.value"
        :request-id="customers.requestId.value"
        :is-empty="customers.isEmpty.value"
        loading-title="正在加载到店记录"
        forbidden-title="暂无权限"
        forbidden-description="这个账号的令牌不能读取本店订单。"
        empty-title="还没有到店记录"
        empty-description="用户下单并到店履约后，这里能按订单看到预约人与宠物；要按客户去重或看历史次数，需要客户接口（契约里还没有）。"
        @retry="search"
      >
        <div class="ph-table-wrap">
          <table class="ph-table">
            <thead>
              <tr>
                <th>到店时间</th>
                <th>客户</th>
                <th>宠物</th>
                <th>服务项</th>
                <th>到店应收</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in customers.items.value" :key="row.id">
                <td class="ph-table__num">
                  {{ formatDate(row.appointment_date) }}
                  <span class="ph-text-weak ph-customers__sub">{{ row.start_time }}–{{ row.end_time }}</span>
                </td>
                <td>
                  {{ row.user_nickname ?? "—" }}
                  <span class="ph-text-weak ph-customers__sub">{{ row.user_phone ?? "—" }}（服务端脱敏）</span>
                </td>
                <td>
                  {{ row.pet_name ?? "—" }}
                  <span class="ph-text-weak ph-customers__sub">{{ speciesLabel(row.pet_species) }}</span>
                </td>
                <td>{{ row.service_name ?? "—" }}</td>
                <td class="ph-table__num">{{ formatAmount(row.estimated_pay_amount) }}</td>
                <td>
                  <span class="ph-tag" :class="orderStatusTone(row.status)">{{ orderStatusLabel(row.status) }}</span>
                  <span class="ph-text-weak ph-customers__sub">下单 {{ formatDateTime(row.created_at) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="ph-pager">
          <span>共 {{ customers.total.value }} 条</span>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="customers.page.value <= 1 || customers.loading.value"
            @click="customers.prevPage"
          >
            上一页
          </button>
          <span>第 {{ customers.page.value }} 页</span>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="!customers.hasMore.value || customers.loading.value"
            @click="customers.nextPage"
          >
            下一页
          </button>
        </div>
      </ConsoleListState>

      <section class="ph-card ph-customers__note">
        <h3 class="ph-card__title">缺的接口（这一页暂时做不到的）</h3>
        <p class="ph-text-sub">
          这一页目前只能按订单看到店的人与宠物。真正的「客户管理」还差两个接口，契约里都没有，
          所以不摆出来：一是客户维度（按客户去重、来过几次、累计到店、上次做了什么）需要一个
          客户接口；二是宠物健康档案调取，档案属于用户数据、调取要带用户授权（ADR-0030），
          这条授权只能在接口侧落地——前端「先拉全量再挡显示」不是授权，是泄漏。
        </p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-customers__search {
  min-width: 280px;
}

.ph-customers__sub {
  display: block;
  font-size: 12px;
}

.ph-customers__note {
  margin-top: var(--ph-space-6);
}
</style>
