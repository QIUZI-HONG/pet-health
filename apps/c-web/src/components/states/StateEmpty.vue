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
