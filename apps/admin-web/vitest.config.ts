import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

/**
 * 运营后台的测试配置（与 `apps/c-web/vitest.config.ts` 同源）。
 *
 * 为什么后台也要有测试：这一波新增的七个页面都要「四态齐全」——加载 / 空 / 错误（带请求 ID 与重试）/
 * 无权限，还有「写操作真的调了接口」这类**肉眼看不出来、串一次就骗人**的行为。页面里每一段
 * v-if 链的顺序、每一条 `@retry` 接的是哪个加载器，只能靠测试每次都验一遍。
 *
 * `@pet-health/*` 是 workspace 里的 TS 源码包（没有构建产物），所以要内联让 Vite 直接编译它们。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@pet-health/shared": fileURLToPath(new URL("../../packages/shared/src/index.ts", import.meta.url)),
      "@pet-health/ui": fileURLToPath(new URL("../../packages/ui/src/index.ts", import.meta.url)),
      // 单测要能把网络出口打桩：workspace 下 axios 只装在 packages/shared，不归一到同一份，
      // 测试里的 vi.mock("axios") 与 shared 源码 import 的 axios 会解析到不同路径，桩静默失效。
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
