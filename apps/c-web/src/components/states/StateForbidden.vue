<script setup lang="ts">
/**
 * 四态组件之四：无权限 / 未登录。
 *
 * 与错误态的区别很重要：错误是「系统没做好」，无权限是「你需要先登录/换个身份」——
 * 所以这里的默认动作是去登录，而不是重试。
 *
 * 桌面 Web 上用户可能直接打开一个深链接，所以**不把他弹走**，就地提示并提供登录入口
 * （ADR-0016 的决定）。
 */
import { useRouter } from "vue-router";

withDefaults(
  defineProps<{
    title?: string;
    description?: string;
    loginText?: string;
  }>(),
  {
    title: "登录后查看",
    description: "这个页面需要登录宠物主人的账号。",
    loginText: "去登录",
  },
);

const router = useRouter();

function goLogin(): void {
  void router.push({ name: "login", query: { redirect: router.currentRoute.value.fullPath } });
}
</script>

<template>
  <div class="ph-state ph-state--forbidden">
    <div class="ph-state__icon" aria-hidden="true">🔒</div>
    <p class="ph-state__title">{{ title }}</p>
    <p class="ph-state__desc">{{ description }}</p>
    <div class="ph-state__actions">
      <button type="button" class="ph-button ph-button--primary" @click="goLogin">{{ loginText }}</button>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.ph-state--forbidden {
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
}

.ph-state__actions {
  margin-top: var(--ph-space-4);
  display: flex;
  gap: var(--ph-space-3);
}
</style>
