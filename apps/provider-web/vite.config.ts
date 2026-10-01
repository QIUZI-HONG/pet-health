import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      // 后端本地跑在 8080（见 README）
      "/api/v1/provider": { target: "http://127.0.0.1:8080", changeOrigin: true },
      // `open` 域也要转发：资质材料图与服务留痕照片的**直传与读图**都落在它上面——
      // presign 返回的 upload_url 就是 /api/v1/open/files/{id}/content。
      // 漏了它的表现是「选完图一直传不上去、文件停在待落定」——而接口测试照样全绿，
      // 因为这条路径只在浏览器里才走到（c-web 在切片 #95 踩过同一个坑，见其 vite.config.ts）。
      "/api/v1/open": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
});
