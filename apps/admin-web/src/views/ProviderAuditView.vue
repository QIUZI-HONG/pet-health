<script setup lang="ts">
/**
 * 服务者审核（交付文档 3.2 P033「商家审核 /admin/merchants」——文档用词，术语与路径都按 CONTEXT.md
 * 收口成 服务者 / providers）。
 *
 * 三段在这一页，因为它们都是「平台守门」这一件事的三步（ADR-0037 的边界）：
 * 入驻与资质审核（服务者发起 → 运营审批）、服务者状态处置（清退 = 冻结，**只对超级管理员**）、
 * 服务上架审核（服务者定价 → 运营再审一眼）。
 *
 * 清退入口的可见性：ADR-0037 把它划给超级管理员，而令牌里只有登录域、没有角色
 * （ADR-0035「需要协调」），前端没有可信来源判断谁是超管——所以**不渲染**该入口，
 * 由 `ProviderListPanel` 写明原因（见那里的文件头说明）。
 */
import { ref } from "vue";
import { ConsoleGate } from "@pet-health/ui";
import { useAdminSession } from "../session";
import ApplicationReviewPanel from "../components/ApplicationReviewPanel.vue";
import ProviderListPanel from "../components/ProviderListPanel.vue";
import ServiceReviewPanel from "../components/ServiceReviewPanel.vue";

const { status } = useAdminSession();

type Tab = "applications" | "providers" | "listings";
const tab = ref<Tab>("applications");
</script>

<template>
  <section>
    <h2 class="ph-page-title">服务者审核</h2>
    <p class="ph-page-desc">
      入驻与资质审核、服务者状态处置（冻结＝清退落点）、服务上架审核。审核通过的服务者才能选品上架；清退保留历史订单与宠物档案，但不再允许新接单与新上架。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <nav class="ph-tabs" aria-label="服务者审核分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'applications' }"
          @click="tab = 'applications'"
        >
          入驻与资质审核
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'providers' }"
          @click="tab = 'providers'"
        >
          服务者列表与状态
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'listings' }"
          @click="tab = 'listings'"
        >
          服务上架审核
        </button>
      </nav>

      <ApplicationReviewPanel v-if="tab === 'applications'" />
      <ProviderListPanel v-else-if="tab === 'providers'" />
      <ServiceReviewPanel v-else />
    </ConsoleGate>
  </section>
</template>
