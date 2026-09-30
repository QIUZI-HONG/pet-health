<script setup lang="ts">
// 根组件只放路由出口。外壳（左栏 + 顶栏 + 内容区）在 layouts/AppShell.vue，
// 它挂在 `/b` 之下，所以将来不套外壳的页面（登录页）是它的兄弟路由。
import { RouterView } from "vue-router";
</script>

<template>
  <RouterView />
</template>
