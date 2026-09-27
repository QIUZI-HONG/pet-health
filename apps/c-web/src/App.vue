<script setup lang="ts">
// 根组件只放路由出口；框架（左栏 + 顶栏）在 layouts/AppShell.vue，
// 登录页不套框架，所以两者是平行的兄弟路由。
</script>

<template>
  <RouterView />
</template>
