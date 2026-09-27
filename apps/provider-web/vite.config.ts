import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      // 后端本地跑在 8080（见 README）
      "/api/v1/provider": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
});
