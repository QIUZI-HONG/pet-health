/**
 * C 端入口：装配 Pinia 与路由，然后把应用挂上去。
 *
 * <p>**下面几段的顺序是有意的，别随手调**：
 *
 * <ol>
 *   <li>`rememberInviteFromUrl()` 必须在 `mount` **之前**——用户可能从 `?invite=CODE` 落地后先逛一会儿
 *       才去注册，那时地址栏早已不是落地页；
 *   <li>`onSessionExpired` 的订阅要在 `bootstrap` **之前**注册，否则「刷新令牌也换不回来」的那次广播
 *       会没人接，界面会继续显示成已登录；
 *   <li>`bootstrap()` **不 await**：拉会话失败不该阻塞首屏渲染，页面各自按状态显示空态 / 无权限态。
 * </ol>
 */
import { createApp } from "vue";
import { createPinia } from "pinia";
import "@pet-health/ui/tokens.css";
import "./styles/app.css";
import App from "./App.vue";
import { router } from "./router";
import { onSessionExpired } from "@pet-health/shared";
import { useSessionStore } from "./stores/session";
import { useMessageStore } from "./stores/messages";
import { rememberInviteFromUrl } from "./utils/invite";

const app = createApp(App);
app.use(createPinia());
app.use(router);

// 分享链接带 ?invite=CODE 时记住它（ADR-0039 第一节：落地页记住 7 天，这条是前端行为）。
// 在挂载前做：用户可能从链接落到首页、逛一会儿才去注册，那时地址栏已经不是落地页了。
rememberInviteFromUrl();

// 刷新令牌也换不回来时，请求层会广播「会话失效」：这里把身份与未读角标一起清掉，
// 界面立刻回到未登录，而不是继续显示「已登录」（测试报告 D15）
onSessionExpired(() => {
  useSessionStore().markSessionExpired();
  useMessageStore().clear();
});

// 启动时拉一次会话：有本地令牌就换出用户与宠物，没有就是匿名（不发请求）。
// 失败不阻塞渲染——页面自己按状态显示空态 / 无权限态。
void useSessionStore()
  .bootstrap()
  .catch((error: unknown) => {
    // bootstrap 内部已经把失败收敛成匿名态，这里只是不让异常静默消失
    console.warn("[session] 启动时恢复会话失败", error);
  });

app.mount("#app");
