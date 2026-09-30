<script setup lang="ts">
/**
 * 列表四态的收口：加载中 / 无权限 / 错误（带重试 + 请求 ID）/ 空 / 有内容。
 *
 * 判断顺序写在这一处（加载 → 无权限 → 错误 → 空），页面只负责渲染表格与工具条。
 * 为什么值得收：三个后台页面各写一遍这套 v-if 链，最先分叉的一定是顺序——把「错误」排在
 * 「无权限」前面，后端返回 40300 时用户看到的是「加载失败 + 重试」，点了还是失败，
 * 而真正该说的是「你的身份不能做这件事」。
 *
 * 与 `ConsoleGate.vue` 的分工：闸门看的是**有没有身份**（发请求之前就知道），这里看的是
 * **这次请求的结果**（服务端拒绝了，或者服务不可用）。两者都要有——只有闸门会漏掉
 * 「令牌在但已被作废」，只有这里会让未登录的页面白跑一趟请求。
 */
import ConsoleState from "./ConsoleState.vue";

withDefaults(
  defineProps<{
    /** 这一次请求还在飞 */
    loading?: boolean;
    /** 服务端按「没权限」处理了（40100 / 40101 / 40300）——重试没有意义 */
    forbidden?: boolean;
    /** 加载失败的文案；空串表示没失败 */
    errorMessage?: string;
    /** 失败那次的请求 ID（ADR-0029） */
    requestId?: string;
    /** 请求成功但一条都没有 */
    isEmpty?: boolean;
    emptyTitle?: string;
    emptyDescription?: string;
    forbiddenTitle?: string;
    forbiddenDescription?: string;
    loadingTitle?: string;
  }>(),
  {
    loading: false,
    forbidden: false,
    errorMessage: "",
    requestId: "",
    isEmpty: false,
    emptyTitle: "",
    emptyDescription: "",
    forbiddenTitle: "暂无权限",
    forbiddenDescription: "",
    loadingTitle: "",
  },
);

defineEmits<{ retry: [] }>();
</script>

<template>
  <ConsoleState v-if="loading" variant="loading" :title="loadingTitle" />
  <ConsoleState
    v-else-if="forbidden"
    variant="forbidden"
    :title="forbiddenTitle"
    :description="forbiddenDescription"
  >
    <slot name="forbidden-actions" />
  </ConsoleState>
  <ConsoleState
    v-else-if="errorMessage"
    variant="error"
    :title="errorMessage"
    :request-id="requestId"
    @retry="$emit('retry')"
  />
  <ConsoleState v-else-if="isEmpty" variant="empty" :title="emptyTitle" :description="emptyDescription">
    <slot name="empty-actions" />
  </ConsoleState>
  <slot v-else />
</template>
