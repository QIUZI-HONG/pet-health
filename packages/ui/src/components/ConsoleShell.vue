<script setup lang="ts">
/**
 * 后台外壳：左侧固定导航 + 顶栏 + 右侧内容区（交付文档 4.16.8 对后台 PC 端的布局要求）。
 *
 * 服务者后台与运营后台共用这一个外壳——它是纯布局、没有业务语义，两个端只有「端名 + 模块清单」不同。
 * 组件库分家那条（ADR-0015）管的是业务组件，外壳不属于业务组件。
 *
 * 两条约束：
 * 1. **本包不声明 vue-router 依赖**（packages/ui 至今零依赖）：导航用**全局注册**的 `RouterLink`，
 *    调用方必须已经 `app.use(router)`。各端自己的源码反过来要显式 import（避免这种隐式依赖扩散）。
 * 2. 外壳**不认识各端的路由表**：当前项与顶栏标题由调用方传进来（`active` / `title`），
 *    这样同一个外壳能服务两个模块清单完全不同的端。
 */
import type { ConsoleNavItem } from "./types";

withDefaults(
  defineProps<{
    /** 端名，显示在左栏顶部：如「服务者后台」 */
    brand: string;
    /** 模块清单，按左栏顺序排列 */
    nav: ConsoleNavItem[];
    /** 当前路由名（一般传 `route.name`），与 nav[].name 相等的一项高亮 */
    active?: string;
    /** 顶栏标题（一般传 `route.meta.title`） */
    title?: string;
  }>(),
  { active: "", title: "" },
);
</script>

<template>
  <div class="ph-console ph-console__viewport">
    <aside class="ph-console__aside">
      <div class="ph-console__brand">
        <span class="ph-console__logo" aria-hidden="true">🐾</span>
        <span class="ph-console__brand-text">
          <span class="ph-console__brand-name">{{ brand }}</span>
          <span class="ph-console__brand-sub">宠物 AI 健康管理平台</span>
        </span>
      </div>

      <nav class="ph-console__nav" aria-label="模块导航">
        <RouterLink
          v-for="item in nav"
          :key="item.name"
          class="ph-console__nav-item"
          :class="{ 'ph-console__nav-item--active': active === item.name }"
          :to="{ name: item.name }"
        >
          <span class="ph-console__nav-icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="ph-console__nav-label">{{ item.label }}</span>
        </RouterLink>
      </nav>
    </aside>

    <div class="ph-console__main">
      <header class="ph-console__topbar">
        <h1 class="ph-console__title">{{ title }}</h1>
        <!-- 账号区：两个后台是独立登录域（CONTEXT.md「服务者后台 / 运营后台」），登录与账号信息
             各端自己接。这一波不联网，所以默认给一句占位而不是画一个假的用户名。 -->
        <slot name="account">
          <span class="ph-console__account">账号信息待接入</span>
        </slot>
      </header>

      <main class="ph-console__content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 左栏宽度与 C 端一致取 208px（ADR-0016 定的 208px 是 C 端的决策，这里沿用同一个值，
   免得三个端各有一个左栏宽度）。内容区上限 1440px = 交付文档 4.16.8 的设计基准。 */
.ph-console {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  min-height: 100vh;
}

.ph-console__aside {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-6);
  padding: var(--ph-space-5) var(--ph-space-3);
  background: var(--ph-color-surface);
  border-right: 1px solid var(--ph-color-border);
}

.ph-console__brand {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  padding: 0 var(--ph-space-3);
}

.ph-console__logo {
  font-size: 20px;
  line-height: 1;
}

.ph-console__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ph-console__brand-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.ph-console__brand-sub {
  font-size: 11px;
  color: var(--ph-color-text-weak);
  white-space: nowrap;
}

.ph-console__nav {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-console__nav-item {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  padding: var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-text);
  white-space: nowrap;
}

.ph-console__nav-item:hover {
  background: var(--ph-color-bg);
  color: var(--ph-color-text);
}

.ph-console__nav-item--active {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-console__nav-icon {
  font-size: 16px;
  line-height: 1;
}

.ph-console__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ph-console__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
  height: 64px;
  padding: 0 var(--ph-space-8);
  background: var(--ph-color-surface);
  border-bottom: 1px solid var(--ph-color-border);
}

.ph-console__title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  white-space: nowrap;
}

.ph-console__account {
  font-size: 13px;
  color: var(--ph-color-text-weak);
}

.ph-console__content {
  flex: 1;
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: var(--ph-space-6) var(--ph-space-8) var(--ph-space-10);
}

@media (max-width: 1280px) {
  .ph-console__topbar {
    padding: 0 var(--ph-space-6);
  }

  .ph-console__content {
    padding: var(--ph-space-6) var(--ph-space-6) var(--ph-space-8);
  }
}
</style>
