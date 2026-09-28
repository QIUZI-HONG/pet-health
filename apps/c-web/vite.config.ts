import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 后端本地跑在 8080（见 README）
      "/api/v1/app": { target: "http://127.0.0.1:8080", changeOrigin: true },
      // `open` 域也要转发：文件直传与读图都在这里（切片 #95）。漏了它的表现是
      // 「照片传不上去、缩略图全裂」，但后端接口测试照样全绿——因为它只在浏览器里才走到。
      "/api/v1/open": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
});
