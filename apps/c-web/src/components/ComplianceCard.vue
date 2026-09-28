<script setup lang="ts">
/**
 * 账号与条款（切片 #74）：三份合规文档入口 + 数据导出 + 账号注销。
 *
 * **注销必须二次确认**，而且是界面的事（接口是幂等的，它不做确认）：注销会匿名化手机号、
 * 软删全部宠物，不可撤销——一次点击就执行是不可接受的。
 *
 * 导出拿到的是 JSON 副本，用 Blob 下载：不引第三方库，也不把数据塞进 URL。
 */
import { ref } from "vue";
import { useRouter } from "vue-router";
import { cApp, ApiError } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import { useMessageStore } from "../stores/messages";

const session = useSessionStore();
const messageStore = useMessageStore();
const router = useRouter();

const busy = ref(false);
const message = ref("");
const errorMessage = ref("");
const confirmingDeactivation = ref(false);

const DOCUMENTS = [
  { code: "privacy_policy", label: "隐私政策" },
  { code: "user_agreement", label: "用户协议" },
  { code: "ai_disclaimer", label: "AI 免责声明" },
];

async function exportData(): Promise<void> {
  busy.value = true;
  errorMessage.value = "";
  message.value = "";
  try {
    const data = await cApp.exportAccount();
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "pet-health-account-export.json";
    link.click();
    URL.revokeObjectURL(url);
    message.value = "已开始下载。文件是你的账号数据副本。";
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "导出失败，请稍后重试";
  } finally {
    busy.value = false;
  }
}

async function deactivate(): Promise<void> {
  busy.value = true;
  errorMessage.value = "";
  try {
    await cApp.deactivateAccount();
    // 注销后本地会话要**整套**清掉（会话 store + 令牌）：只清令牌的话，store 里仍留着
    // user / pets 与「已登录」标记，用户从登录页后退、或直接输地址回来，还能看到上一个账号的资料
    await session.logout();
    messageStore.clear();
    await router.push({ name: "login" });
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "注销失败，请稍后重试";
  } finally {
    busy.value = false;
    confirmingDeactivation.value = false;
  }
}
</script>

<template>
  <article class="ph-card ph-compliance">
    <h3 class="ph-card__title">账号与条款</h3>

    <ul class="ph-compliance__links">
      <li v-for="doc in DOCUMENTS" :key="doc.code">
        <RouterLink :to="`/legal/${doc.code}`">{{ doc.label }}</RouterLink>
      </li>
    </ul>

    <p class="ph-text-sub">
      你可以随时导出账号数据，或注销账号。注销会匿名化手机号并停用全部宠物档案，不可撤销。
    </p>

    <div class="ph-compliance__actions">
      <button type="button" class="ph-button ph-button--secondary" :disabled="busy" @click="exportData">
        {{ busy ? "处理中…" : "导出我的数据" }}
      </button>
      <button
        v-if="!confirmingDeactivation"
        type="button"
        class="ph-button ph-button--text"
        :disabled="busy"
        @click="confirmingDeactivation = true"
      >
        注销账号
      </button>
    </div>

    <!-- 二次确认：注销不可撤销，一次点击就执行是不可接受的 -->
    <div v-if="confirmingDeactivation" class="ph-compliance__confirm">
      <p>
        确认注销「{{ session.user?.nickname ?? "当前账号" }}」？<strong>此操作不可撤销</strong>：
        宠物档案会被停用，手机号会被匿名化（之后可以用同一手机号重新注册）。
      </p>
      <div class="ph-compliance__actions">
        <button type="button" class="ph-button ph-button--secondary" :disabled="busy" @click="deactivate">
          确认注销
        </button>
        <button type="button" class="ph-button ph-button--text" @click="confirmingDeactivation = false">
          取消
        </button>
      </div>
    </div>

    <p v-if="message" class="ph-text-sub">{{ message }}</p>
    <p v-if="errorMessage" class="ph-compliance__error">{{ errorMessage }}</p>
  </article>
</template>

<style scoped>
.ph-compliance {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-compliance__links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-4);
}

.ph-compliance__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
}

.ph-compliance__confirm {
  padding: var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  border: 1px solid var(--ph-color-danger);
  background: var(--ph-color-danger-light);
}

.ph-compliance__confirm p {
  margin: 0 0 var(--ph-space-3);
  font-size: 14px;
}

.ph-compliance__error {
  margin: 0;
  font-size: 14px;
  color: var(--ph-color-danger);
}
</style>
