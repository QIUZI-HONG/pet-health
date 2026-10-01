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
    /**
     * 兼容位：各页原先传的 emoji（📋/🔔/🐾…）。**不传时用统一的 SVG 图标**
     * （4.16.9 的四态规范），传了则照旧显示那一枚——逐个把调用方的 emoji 去掉是后续的清理项。
     */
    icon?: string;
  }>(),
  {
    title: "还没有内容",
    description: "",
    icon: "",
  },
);
import StateIcon from "./StateIcon.vue";
</script>

<template>
  <div class="ph-state ph-state--empty">
    <StateIcon v-if="!icon" tone="empty" />
    <div v-else class="ph-state__icon" aria-hidden="true">{{ icon }}</div>
    <p class="ph-state__title">{{ title }}</p>
    <p v-if="description" class="ph-state__desc">{{ description }}</p>
    <div class="ph-state__actions"><slot /></div>
  </div>
</template>
