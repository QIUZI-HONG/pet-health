<script setup lang="ts">
/**
 * 四态组件之二：空态。
 *
 * 「还没有数据」不是错误，所以文案要给出**下一步该做什么**，而不是只说「暂无数据」。
 * 默认插槽放操作按钮。
 */
withDefaults(
  defineProps<{
    title?: string;
    description?: string;
    /** 简单图形标记；用 emoji 是为了不引入图标依赖，视觉稿的图标位后续统一换 SVG。 */
    icon?: string;
  }>(),
  {
    title: "还没有内容",
    description: "",
    icon: "🐾",
  },
);
</script>

<template>
  <div class="ph-state ph-state--empty">
    <div class="ph-state__icon" aria-hidden="true">{{ icon }}</div>
    <p class="ph-state__title">{{ title }}</p>
    <p v-if="description" class="ph-state__desc">{{ description }}</p>
    <div class="ph-state__actions"><slot /></div>
  </div>
</template>

<style scoped>
.ph-state--empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ph-space-2);
  padding: var(--ph-space-10) var(--ph-space-6);
  text-align: center;
}

.ph-state__icon {
  font-size: 32px;
  line-height: 1;
  margin-bottom: var(--ph-space-2);
}

.ph-state__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ph-color-text);
}

.ph-state__desc {
  margin: 0;
  font-size: 13px;
  color: var(--ph-color-text-sub);
  max-width: 420px;
  line-height: 1.6;
}

.ph-state__actions {
  margin-top: var(--ph-space-4);
  display: flex;
  gap: var(--ph-space-3);
}
</style>
