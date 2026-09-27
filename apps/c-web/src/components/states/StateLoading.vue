<script setup lang="ts">
/**
 * 四态组件之一：加载中。
 *
 * 用骨架条而不是转圈：桌面上的卡片布局里，骨架能让用户预期「这里会出现什么」，
 * 转圈只会让整页跳动。
 */
withDefaults(defineProps<{ rows?: number; title?: string }>(), {
  rows: 3,
  title: "加载中",
});
</script>

<template>
  <div class="ph-state ph-state--loading" role="status" :aria-label="title">
    <div v-for="row in rows" :key="row" class="ph-state__skeleton" :style="{ width: `${96 - row * 12}%` }" />
  </div>
</template>

<style scoped>
/* 加载态独有的部分：左对齐的骨架条；居中、间距那些骨架见 styles/app.css */
.ph-state--loading {
  align-items: stretch;
  padding: var(--ph-space-6);
}

.ph-state__skeleton {
  height: 16px;
  border-radius: var(--ph-radius-input);
  background: linear-gradient(
    90deg,
    var(--ph-color-divider) 0%,
    var(--ph-color-bg) 50%,
    var(--ph-color-divider) 100%
  );
  background-size: 200% 100%;
  animation: ph-skeleton 1.2s ease-in-out infinite;
}

@keyframes ph-skeleton {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}
</style>
