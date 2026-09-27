<script setup lang="ts">
/**
 * 顶栏：当前宠物 + 通知 + 账号（ADR-0016 定的三段）。
 *
 * 通知入口先留着不做跳转——提醒体系（#56 / #99）还没实现，做成假的红点会骗人。
 */
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../stores/session";
import PetSwitcher from "./PetSwitcher.vue";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();

async function onLogout(): Promise<void> {
  await session.logout();
  void router.push({ name: "login" });
}
</script>

<template>
  <header class="ph-topbar">
    <div class="ph-topbar__left">
      <h1 class="ph-topbar__title">{{ route.meta.title }}</h1>
      <PetSwitcher v-if="session.hasSession" />
    </div>

    <div class="ph-topbar__right">
      <span class="ph-topbar__notice" title="提醒体系尚未实现（#56 / #99）">🔔</span>
      <!-- 按 hasSession 而不是 isLoggedIn：后端不可用时（status=error）会话还在，
           这时显示「登录」会与内容区的「服务异常，请重新加载」自相矛盾 -->
      <template v-if="session.hasSession">
        <span class="ph-topbar__user">{{ session.user?.nickname ?? "已登录" }}</span>
        <button type="button" class="ph-button ph-button--text" @click="onLogout">退出</button>
      </template>
      <RouterLink v-else class="ph-button ph-button--primary" :to="{ name: 'login' }">登录</RouterLink>
    </div>
  </header>
</template>

<style scoped>
.ph-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
  height: 64px;
  padding: 0 var(--ph-space-8);
  background: var(--ph-color-surface);
  border-bottom: 1px solid var(--ph-color-border);
}

.ph-topbar__left,
.ph-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--ph-space-4);
  min-width: 0;
}

.ph-topbar__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  white-space: nowrap;
}

.ph-topbar__notice {
  font-size: 16px;
  opacity: 0.45;
  cursor: not-allowed;
}

.ph-topbar__user {
  color: var(--ph-color-text-sub);
}
</style>
