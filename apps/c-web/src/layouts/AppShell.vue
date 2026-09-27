<script setup lang="ts">
/**
 * 五个主页面共用的框架：左栏 + 顶栏 + 内容区（ADR-0016）。
 *
 * 页面只管内容区，导航与宠物切换在这里统一——后续新页面挂进 router 就是这个框架的一部分，
 * 不需要各自再画一遍导航。
 */
import AppSidebar from "../components/AppSidebar.vue";
import AppTopbar from "../components/AppTopbar.vue";
</script>

<template>
  <div class="ph-shell">
    <AppSidebar />
    <div class="ph-shell__main">
      <AppTopbar />
      <main class="ph-shell__content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
