<script setup lang="ts">
/**
 * 服务者后台的外壳接线：把本端的模块清单与当前路由接到共享的 `ConsoleShell`（packages/ui）上。
 *
 * 布局、左栏、顶栏的样式都在 ConsoleShell 里——那个外壳与运营后台共用（两个端只有端名与模块清单不同），
 * 这里只回答两个本端问题：**有哪些模块**（navigation.ts）和**现在在哪一页**（当前路由）。
 */
import { RouterView, useRoute, useRouter } from "vue-router";
import { ConsoleShell } from "@pet-health/ui";
import { providerModules } from "../navigation";
import { signOut, useProviderSession } from "../session";

const route = useRoute();
const router = useRouter();
// 解构而不是 `const session = ...`：对象属性里的 ref 在模板里不会自动解包（见 session.ts 的说明）
const { hasToken } = useProviderSession();

/**
 * 退出：吊销本域的 Refresh，然后回登录页。
 *
 * 顶栏的账号区（`ConsoleShell` 的 `account` 插槽）就是登录入口所在的唯一位置——
 * 后台每一页都套这个外壳，所以放这里等于 11 个页面都有了。
 */
async function leave(): Promise<void> {
  await signOut();
  await router.replace({ name: "login" });
}
</script>

<template>
  <ConsoleShell
    brand="服务者后台"
    :nav="providerModules"
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
