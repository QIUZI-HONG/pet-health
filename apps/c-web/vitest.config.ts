import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

/**
 * 前端测试配置。
 *
 * 为什么 C 端要有测试：验收标准里「五个主页面在桌面布局下可导航」「四态组件可复用」
 * 都是**行为**，靠肉眼看一次不算数——组件测试能在 CI 上每次提交都验一遍。
 *
 * `@pet-health/*` 是 workspace 里的 TS 源码包（没有构建产物），所以要内联让 Vite 直接编译它们。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@pet-health/shared": fileURLToPath(new URL("../../packages/shared/src/index.ts", import.meta.url)),
      "@pet-health/ui": fileURLToPath(new URL("../../packages/ui/src/index.ts", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
    server: {
      deps: {
        inline: [/@pet-health\//],
      },
    },
  },
});
