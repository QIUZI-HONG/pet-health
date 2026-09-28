<script setup lang="ts">
/**
 * 顶栏：当前宠物 + 通知 + 账号（ADR-0016 定的三段）。
 *
 * 通知入口先留着不做跳转——提醒体系（#56 / #99）还没实现，做成假的红点会骗人。
 */
import { onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../stores/session";
import { useMessageStore } from "../stores/messages";
import PetSwitcher from "./PetSwitcher.vue";

const session = useSessionStore();
const messages = useMessageStore();
const route = useRoute();
const router = useRouter();

onMounted(() => {
  if (session.hasSession) {
    void messages.refresh();
  }
});
// 路由变化时刷一次（看完消息回来角标要跟着掉）；登录态变化时重置
watch(
  () => route.fullPath,
  () => {
    if (session.hasSession) {
      void messages.refresh();
    }
  },
);
watch(
  () => session.hasSession,
  (hasSession) => {
    if (hasSession) {
      void messages.refresh();
    } else {
      messages.clear();
    }
  },
);

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
      <RouterLink class="ph-topbar__notice" :to="{ name: 'messages' }" title="消息中心">
        🔔
        <span v-if="messages.unread > 0" class="ph-topbar__badge">
          {{ messages.unread > 99 ? "99+" : messages.unread }}
        </span>
      </RouterLink>
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
  position: relative;
  font-size: 16px;
  color: var(--ph-color-text);
  text-decoration: none;
}

.ph-topbar__badge {
  position: absolute;
  top: -6px;
  right: -10px;
  min-width: 16px;
  padding: 0 4px;
  background: var(--ph-color-danger);
  border-radius: 999px;
  color: var(--ph-color-surface);
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.ph-topbar__user {
  color: var(--ph-color-text-sub);
}
</style>
