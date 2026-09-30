<script setup lang="ts">
/**
 * 运营后台的外壳接线：把本端的模块清单与当前路由接到共享的 `ConsoleShell`（packages/ui）上。
 *
 * 布局与样式都在 ConsoleShell 里——它与服务者后台共用同一个外壳，这里只回答本端的两个问题：
 * **有哪些模块**（navigation.ts）和**现在在哪一页**（当前路由）。
 */
import { RouterView, useRoute, useRouter } from "vue-router";
import { ConsoleShell } from "@pet-health/ui";
import { adminModules } from "../navigation";
import { signOut, useAdminSession } from "../session";

const route = useRoute();
const router = useRouter();
// 解构而不是 `const session = ...`：对象属性里的 ref 在模板里不会自动解包（见 session.ts 的说明）
const { hasToken } = useAdminSession();

/** 退出：吊销本域 Refresh，然后回登录页。 */
async function leave(): Promise<void> {
  await signOut();
  await router.replace({ name: "login" });
}
</script>

<template>
  <ConsoleShell
    brand="运营后台"
    :nav="adminModules"
    :active="String(route.name ?? '')"
    :title="String(route.meta.title ?? '')"
  >
    <template #account>
      <RouterLink v-if="!hasToken" class="ph-link" :to="{ name: 'login' }">登录</RouterLink>
      <button v-else class="ph-link" type="button" @click="leave">退出登录</button>
    </template>

    <RouterView />
  </ConsoleShell>
</template>
