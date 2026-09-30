import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

/**
 * 服务者后台的测试配置（与 apps/c-web/vitest.config.ts 同源，别名与插件逐项对齐）。
 *
 * 为什么这一端也要测试：本轮接的这几页里，「四态齐全」「非法状态迁移不给点」「金额不带浮点」
 * 这些验收项都是**行为**——肉眼看一次不算数，而且订单状态机与券额度账这两块一旦回归，
 * 错的是门店当天能不能核销。组件测试能在每次提交上把这些再验一遍。
 *
 * `@pet-health/*` 是 workspace 里的 TS 源码包（没有构建产物），要内联让 Vite 直接编译它们。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@pet-health/shared": fileURLToPath(new URL("../../packages/shared/src/index.ts", import.meta.url)),
      "@pet-health/ui": fileURLToPath(new URL("../../packages/ui/src/index.ts", import.meta.url)),
      // workspace 下 axios 只装在 packages/shared；不归一到同一份，测试文件里的 vi.mock("axios")
      // 与 shared 源码 import 的 axios 会解析到不同路径，桩静默失效（与 c-web 同一处理）。
      axios: fileURLToPath(new URL("../../packages/shared/node_modules/axios", import.meta.url)),
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
