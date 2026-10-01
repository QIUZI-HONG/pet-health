<script setup lang="ts">
/**
 * 四态的图标（交付文档 4.16.9）：**52px 圆形底 + 居中图标 + 语义色**，替换原先的 emoji。
 *
 * 为什么换掉 emoji：稿子给的是「灰=暂无数据 / 红=网络或加载失败 / 橙=无权限」的**色点语义**，
 * 而 emoji 三端各长一个样、也不带色（`StateEmpty.vue` 自己注释过「后续统一换 SVG」）。
 * 这里只做图标本身，色类由调用方的 `tone` 决定——同一个图标在不同状态里颜色不同。
 */
type Tone = "empty" | "error" | "forbidden";

withDefaults(defineProps<{ tone?: Tone }>(), { tone: "empty" });
</script>

<template>
  <span class="ph-state-icon" :class="`ph-state-icon--${tone}`" aria-hidden="true">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round">
      <!-- 暂无数据：一个空盒子（灰） -->
      <template v-if="tone === 'empty'">
        <path d="M3 8l9-5 9 5v8l-9 5-9-5z" />
        <path d="M3 8l9 5 9-5" />
        <path d="M12 13v8" />
      </template>
      <!-- 加载失败 / 网络异常：感叹号（红） -->
      <template v-else-if="tone === 'error'">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 8v5" />
        <path d="M12 16.5h.01" />
      </template>
      <!-- 无权限：锁（橙） -->
      <template v-else>
        <rect x="4" y="10" width="16" height="11" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
        <path d="M12 15v2" />
      </template>
    </svg>
  </span>
</template>

<style scoped>
/* 52px 圆形底，图标居中：底色的透明度按 4.16.9「圆底透明度 40」的口径用浅色 token 承担 */
.ph-state-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  margin-bottom: var(--ph-space-3);
}

.ph-state-icon svg {
  width: 24px;
  height: 24px;
}

.ph-state-icon--empty {
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

.ph-state-icon--error {
  background: var(--ph-color-danger-light);
  color: var(--ph-color-danger);
}

.ph-state-icon--forbidden {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}
</style>
