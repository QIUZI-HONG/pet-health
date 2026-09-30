import { createApp } from "vue";
import "@pet-health/ui/tokens.css";
// 后台两端共用的基础样式（reset / 页面标题 / 卡片 / 表格 / 按钮），与 token 分开两个文件：
// token 三个端都要，这份只有后台要。
import "@pet-health/ui/console.css";
import App from "./App.vue";
import { router } from "./router";
import { initProviderSession } from "./session";

// 会话状态是一个极小的单例（就是「本地有没有本域令牌」），用一个模块的 ref 就够，不引 pinia。
// 初始化要排在挂载之前：闸门组件在首帧就按它决定渲染内容还是「尚未登录」，
// 晚一步会先闪一下错误态（ADR-0015 的技术栈里仍有 pinia，等后台有真正的多模块状态时再引）。
initProviderSession();

createApp(App).use(router).mount("#app");
