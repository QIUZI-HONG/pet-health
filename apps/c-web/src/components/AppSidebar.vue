<script setup lang="ts">
/**
 * 左侧主导航（ADR-0016：五个主页面 + 消息中心固定在这里，取代移动稿的底部 Tab）。
 *
 * 交易与增长那一组（订单 / 券包 / 积分 / 权益 / 邀请）与主页面**共用同一个框架**：
 * 它们只是多出来的入口，不是第二套导航（ADR-0016 的信息架构不变）。
 *
 * 60px 收缩态只保留图标，给窄窗口用。
 */
import { RouterLink, useRoute } from "vue-router";

interface NavItem {
  name: string;
  label: string;
  icon: string;
}

interface NavGroup {
  /** 分组标题；主页面那组不显示标题（它是默认的一组）。 */
  title: string;
  items: NavItem[];
}

/** 主页面：五个 + 消息中心（ADR-0016 定的信息架构）。 */
const mainItems: NavItem[] = [
  { name: "home", label: "首页", icon: "🏠" },
  { name: "services", label: "服务", icon: "🩺" },
  { name: "ai", label: "AI 管家", icon: "💬" },
  { name: "records", label: "健康档案", icon: "📋" },
  { name: "profile", label: "我的", icon: "👤" },
  { name: "messages", label: "消息中心", icon: "🔔" },
];

/**
 * 交易与增长：订单与券是「我到店要用」的东西，积分与邀请是「我怎么攒到券」，
 * 权益是「我有哪些能力」——所以按这个顺序排。
 *
 * 下单页不放进导航：它需要门店与服务项（查询参数），从订单详情的「再次预约」进更自然。
 */
const groups: NavGroup[] = [
  { title: "", items: mainItems },
  {
    title: "交易与权益",
    items: [
      { name: "orders", label: "我的订单", icon: "🧾" },
      { name: "coupons", label: "我的券", icon: "🎟️" },
      { name: "points", label: "积分中心", icon: "⭐" },
      { name: "rights", label: "我的权益", icon: "🎫" },
      { name: "invites", label: "邀请有礼", icon: "🎉" },
    ],
  },
];

const route = useRoute();
</script>

<template>
  <nav class="ph-sidebar" aria-label="主导航">
    <div class="ph-sidebar__brand">
      <span class="ph-sidebar__logo" aria-hidden="true">🐾</span>
      <span class="ph-sidebar__name">宠物健康管家</span>
    </div>
    <div v-for="group in groups" :key="group.title || 'main'" class="ph-sidebar__group">
      <p v-if="group.title" class="ph-sidebar__group-title">{{ group.title }}</p>
      <ul class="ph-sidebar__list">
        <li v-for="item in group.items" :key="item.name">
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
    </div>
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

/* 分组之间用一条分隔线与留白分开，不另起一套导航（ADR-0016 的信息架构不变） */
.ph-sidebar__group + .ph-sidebar__group {
  margin-top: var(--ph-space-4);
  padding-top: var(--ph-space-4);
  border-top: 1px solid var(--ph-color-divider);
}

.ph-sidebar__group-title {
  margin: 0 0 var(--ph-space-2);
  padding: 0 var(--ph-space-3);
  font-size: 12px;
  color: var(--ph-color-text-weak);
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
