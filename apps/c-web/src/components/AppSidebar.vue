<script setup lang="ts">
/**
 * 左侧主导航（ADR-0016：五个主页面固定在这里，取代移动稿的底部 Tab）。
 *
 * 60px 收缩态只保留图标，给窄窗口用。
 */
import { RouterLink, useRoute } from "vue-router";

interface NavItem {
  name: string;
  label: string;
  icon: string;
}

const items: NavItem[] = [
  { name: "home", label: "首页", icon: "🏠" },
  { name: "services", label: "服务", icon: "🩺" },
  { name: "ai", label: "AI 管家", icon: "💬" },
  { name: "records", label: "健康档案", icon: "📋" },
  { name: "profile", label: "我的", icon: "👤" },
  { name: "messages", label: "消息中心", icon: "🔔" },
];

const route = useRoute();
</script>

<template>
  <nav class="ph-sidebar" aria-label="主导航">
    <div class="ph-sidebar__brand">
      <span class="ph-sidebar__logo" aria-hidden="true">🐾</span>
      <span class="ph-sidebar__name">宠物健康管家</span>
    </div>
    <ul class="ph-sidebar__list">
      <li v-for="item in items" :key="item.name">
        <RouterLink
          class="ph-sidebar__item"
          :class="{ 'ph-sidebar__item--active': route.name === item.name }"
          :to="{ name: item.name }"
        >
          <span class="ph-sidebar__icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="ph-sidebar__label">{{ item.label }}</span>
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.ph-sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-6);
  padding: var(--ph-space-5) var(--ph-space-3);
  background: var(--ph-color-surface);
  border-right: 1px solid var(--ph-color-border);
}

.ph-sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  padding: 0 var(--ph-space-3);
}

.ph-sidebar__logo {
  font-size: 20px;
  line-height: 1;
}

.ph-sidebar__name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.ph-sidebar__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-sidebar__item {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  padding: var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-text);
  white-space: nowrap;
}

.ph-sidebar__item:hover {
  background: var(--ph-color-bg);
  color: var(--ph-color-text);
}

.ph-sidebar__item--active {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-sidebar__icon {
  font-size: 16px;
  line-height: 1;
}
</style>
