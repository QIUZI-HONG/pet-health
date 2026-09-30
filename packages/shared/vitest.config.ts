import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * `packages/shared` 自己的测试配置。
 *
 * <p>这个包原先**没有 runner**，它的行为测试（请求层的令牌、40101 静默刷新、会话失效广播）
 * 只能寄居在 C 端——因为当时只有 C 端配了 vitest。那份测试一行 c-web 的东西都不 import，
 * 纯粹是 shared 的测试，放在别人家里只是历史原因。现在收回来。
 *
 * <p>环境用 `jsdom`：`tokenStore` 读写 `localStorage`（刷新页面不该掉登录）。
 *
 * <p>别名把包名指回自己的源码入口——测试里写 `@pet-health/shared`，与使用方的写法一致，
 * 免得测试和真实用法漂移成两种 import。
 */
export default defineConfig({
  resolve: {
    alias: {
      "@pet-health/shared": fileURLToPath(new URL("./src/index.ts", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
  },
});
