<script setup lang="ts">
/**
 * 设置（交付文档 3.1 商家端模块树的「设置（营业时间/资质/团队）」——文档用词，本项目说服务者）：
 * **门店信息与营业时间、资质材料与到期提示、入驻申请与进度**三件事。
 *
 * 为什么三件事同页：它们共用同一个身份判断（这个账号绑定了哪个服务者、状态是什么），
 * 分开三页就要各自拉一次 `/profile` 并各自解释一次「为什么现在不能改」。契约里
 * `GET /profile` 在未绑定服务者时回 40400，所以这一页要能把「还没提交申请」（流程起点）
 * 与「读不到」（故障）分开。
 *
 * 选品与定价不在这里——它在「服务标准」页（选品要从标准目录里勾，两件事同屏才对得上）。
 *
 * 团队（技师账号）**没接**：契约里没有服务者侧的账号与角色接口（ADR-0037 的角色矩阵尚未落成），
 * 所以那一格给一句说明，不摆一个点不动的假表格。
 */
import { onMounted, ref } from "vue";
import { ApiError, toApiFailure } from "@pet-health/shared";
import { ConsoleGate } from "@pet-health/ui";
import { providerApp, type ProviderProfileView } from "../api/providerApi";
import { useProviderSession } from "../session";
import OnboardingPanel from "../components/OnboardingPanel.vue";
import ProfilePanel from "../components/ProfilePanel.vue";
import QualificationPanel from "../components/QualificationPanel.vue";

const { status } = useProviderSession();

const profile = ref<ProviderProfileView | null>(null);
const profileLoading = ref(false);
const profileError = ref("");
const profileRequestId = ref("");
const notLinked = ref(false);
/** 申请提交 / 重提后 +1：资质面板据此重读材料清单（材料随申请单走） */
const materialVersion = ref(0);

async function loadProfile(): Promise<void> {
  profileLoading.value = true;
  profileError.value = "";
  profileRequestId.value = "";
  notLinked.value = false;
  try {
    profile.value = await providerApp.getProfile();
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null;
    // 40400 = 这个账号还没有绑定服务者：这是流程的起点（去提交入驻申请），不是故障
    if (apiError?.code === 40400) {
      profile.value = null;
      notLinked.value = true;
      return;
    }
    const failure = toApiFailure(error, "门店信息加载失败，请稍后重试");
    profileError.value = failure.message;
    profileRequestId.value = failure.requestId;
  } finally {
    profileLoading.value = false;
  }
}

function onProfileUpdated(next: ProviderProfileView): void {
  profile.value = next;
}

function onOnboardingChanged(): void {
  materialVersion.value += 1;
  void loadProfile();
}

// 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它（c-web MessagesView 踩过这个坑）
onMounted(() => {
  if (status.value === "authenticated") {
    void loadProfile();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">设置</h2>
    <p class="ph-page-desc">
      门店信息与营业时间、资质材料（含到期提示）、入驻申请与进度。材料过期不注销门店，但会下架全部服务项——补交后即可重新上架。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <section class="ph-card ph-section">
        <h3 class="ph-card__title">入驻申请与进度</h3>
        <OnboardingPanel :approved="profile?.status === 1" @changed="onOnboardingChanged" />
      </section>

      <section class="ph-card ph-section">
        <h3 class="ph-card__title">门店信息与营业时间</h3>
        <p v-if="profileError" class="ph-alert ph-alert--error ph-settings__alert">
          {{ profileError }}
          <span v-if="profileRequestId" class="ph-text-weak">（请求 ID：{{ profileRequestId }}）</span>
          <button type="button" class="ph-table__action ph-settings__retry" @click="loadProfile">重新加载</button>
        </p>
        <ProfilePanel
          :profile="profile"
          :loading="profileLoading"
          :error-message="profileError"
          :request-id="profileRequestId"
          :not-linked="notLinked"
          @retry="loadProfile"
          @updated="onProfileUpdated"
        />
      </section>

      <section class="ph-card">
        <h3 class="ph-card__title">资质材料</h3>
        <QualificationPanel :refresh-key="materialVersion" :writable="profile?.status === 1" />
      </section>

      <section class="ph-card ph-settings__note">
        <h3 class="ph-card__title">团队（技师账号）</h3>
        <p class="ph-text-sub">
          待实现：服务者侧的账号与角色接口还没进契约（ADR-0037 的角色矩阵里，技师是服务者管理员的权限子集），
          所以这一格先不摆表格。技师的实际操作（接宠检查、服务防护、取宠对比、报工）落在订单履约流程里。
        </p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-settings__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-settings__retry {
  margin-left: var(--ph-space-3);
}

.ph-settings__note {
  margin-top: var(--ph-space-6);
}
</style>
