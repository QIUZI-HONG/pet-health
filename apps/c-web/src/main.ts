import { createApp } from "vue";
import { createPinia } from "pinia";
import "@pet-health/ui/tokens.css";
import "./styles/app.css";
import App from "./App.vue";
import { router } from "./router";
import { useSessionStore } from "./stores/session";

const app = createApp(App);
app.use(createPinia());
app.use(router);

// 启动时拉一次会话：有本地令牌就换出用户与宠物，没有就是匿名（不发请求）。
// 失败不阻塞渲染——页面自己按状态显示空态 / 无权限态。
void useSessionStore()
  .bootstrap()
  .catch((error: unknown) => {
    // bootstrap 内部已经把失败收敛成匿名态，这里只是不让异常静默消失
    console.warn("[session] 启动时恢复会话失败", error);
  });

app.mount("#app");
