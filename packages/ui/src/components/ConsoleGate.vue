<script setup lang="ts">
/**
 * 会话闸门：接数据的页面用它包住内容，统一处理「会话未确认 / 未登录」两种状态。
 *
 * 与 C 端的 `components/SessionGate.vue` 是**两套**（ADR-0015 让三端组件分家）：那边判断的是
 * pinia 里的会话 store，这里只认一个由调用方传进来的 `status`——本包不认识各端的会话实现，
 * 也不 import vue（见包说明），所以状态从外面进、内容从插槽出。
 *
 * 为什么值得单独一个组件：两个后台加起来十几个页面都要这同一条判断，写在页面里就是
 * 「未登录也照发请求 → 拿回 40100 → 错误态盖在闸门前面」那类先后顺序错误的高发地
 * （C 端 MessagesView 的注释记着这个坑）。而且未登录的文案必须**说清为什么**：
 * 后台是独立登录域，C 端登录了也没用。
 *
 * 用法：
 *   <ConsoleGate :status="session.status" forbidden-description="…">
 *     …有会话才渲染的内容…
 *     <template #forbidden-actions>…（可选）登录入口等…</template>
 *   </ConsoleGate>
 */
import type { ConsoleSessionStatus } from "./types";
import ConsoleState from "./ConsoleState.vue";

withDefaults(
  defineProps<{
    /** 会话状态，由各端自己的 session 模块给出 */
    status: ConsoleSessionStatus;
    /** 未登录时的主文案 */
    forbiddenTitle?: string;
    /** 未登录时的说明：**写清「为什么看不到」**，比一句「无权限」有用 */
    forbiddenDescription?: string;
    /** 会话尚未确认时的文案 */
    loadingTitle?: string;
  }>(),
  {
    forbiddenTitle: "尚未登录",
    forbiddenDescription: "",
    loadingTitle: "正在确认登录状态",
  },
);
</script>

<template>
  <ConsoleState
    v-if="status === 'unknown'"
    variant="loading"
    :title="loadingTitle"
  />
  <ConsoleState
    v-else-if="status === 'anonymous'"
    variant="forbidden"
    :title="forbiddenTitle"
    :description="forbiddenDescription"
  >
    <slot name="forbidden-actions" />
  </ConsoleState>
  <slot v-else />
</template>
