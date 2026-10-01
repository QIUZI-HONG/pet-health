import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5175,
    proxy: {
      // 后端本地跑在 8080（见 README）
      "/api/v1/admin": { target: "http://127.0.0.1:8080", changeOrigin: true },
      // `open` 域也要转发：审核页要**看**服务者提交的材料图与照片墙留痕
      // （file_url 指向 /api/v1/open/files/{id}?token=…）。
      // 漏了它的表现是「审核页的图全裂」——接口测试照样全绿，只在浏览器里才走到。
      "/api/v1/open": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
});
