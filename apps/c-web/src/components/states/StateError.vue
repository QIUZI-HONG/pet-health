<script setup lang="ts">
/**
 * 四态组件之三：错误态。
 *
 * 关键信息是**请求 ID**：出问题时把这个 ID 报给客服/开发，能直接定位到日志（后端每条响应都带
 * `request_id`，见 contract/common.yaml）。所以这里显示它，而不是只说一句「加载失败」。
 */
withDefaults(
  defineProps<{
    message?: string;
    requestId?: string;
    retryText?: string;
  }>(),
  {
    message: "加载失败，请稍后重试",
    requestId: "",
    retryText: "重新加载",
  },
);

defineEmits<{ retry: [] }>();
</script>

<template>
  <div class="ph-state ph-state--error" role="alert">
    <div class="ph-state__icon" aria-hidden="true">⚠️</div>
    <p class="ph-state__title">{{ message }}</p>
    <p v-if="requestId" class="ph-state__trace">请求 ID：{{ requestId }}</p>
    <div class="ph-state__actions">
      <button type="button" class="ph-button ph-button--primary" @click="$emit('retry')">
        {{ retryText }}
      </button>
      <slot />
    </div>
  </div>
</template>

<style scoped>
/* 只有「请求 ID」这一行是错误态独有的，其余骨架见 styles/app.css */
.ph-state__trace {
  margin: 0;
  font-family: var(--ph-font-numeric);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}
</style>
