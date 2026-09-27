<script setup lang="ts">
/**
 * 会话闸门：把「加载中 / 服务异常 / 未登录 / 正常」这一个判断收在一处。
 *
 * 五个主页面都要按同样的顺序判断这四种情况。写在每个页面里就是同一段 if 链抄五遍，
 * 而其中「服务异常」与「未登录」的区别恰恰是最容易抄错的一处——早先所有页面
 * 都只有「未登录」一支，后端一抖就骗用户去登录（见 stores/session.ts 的注释）。
 *
 * 用法：
 *   <SessionGate forbidden-description="…">
 *     …只有登录后才显示的内容…
 *   </SessionGate>
 */
import { useSessionStore } from "../stores/session";
import StateError from "./states/StateError.vue";
import StateForbidden from "./states/StateForbidden.vue";
import StateLoading from "./states/StateLoading.vue";

withDefaults(
  defineProps<{
    /** 未登录时给用户的一句话，说明这个页面为什么需要登录。 */
    forbiddenDescription?: string;
    loadingRows?: number;
  }>(),
  { forbiddenDescription: "这个页面需要登录宠物主人的账号。", loadingRows: 3 },
);

const session = useSessionStore();

function retry(): void {
  void session.reload().catch(() => undefined);
}
</script>

<template>
  <StateLoading v-if="session.status === 'loading'" :rows="loadingRows" />
  <StateError
    v-else-if="session.status === 'error'"
    :message="session.errorMessage"
    :request-id="session.errorRequestId"
    @retry="retry"
  />
  <StateForbidden v-else-if="!session.hasSession" :description="forbiddenDescription" />
  <template v-else>
    <slot />
  </template>
</template>
