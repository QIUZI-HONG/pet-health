/**
 * 「会话失效」的广播口。
 *
 * 为什么需要它：请求层只清得掉**自己**的令牌（localStorage），而「用户是谁、宠物有哪些、
 * 界面该按哪种身份渲染」在 pinia 的会话 store 里。刷新令牌也换不回来时，如果只有令牌被清掉，
 * 界面会继续显示「已登录」——顶栏有昵称与退出按钮、页面按登录态渲染，但每个请求都 401，
 * 用户得靠刷新页面自救（测试报告 D15）。
 *
 * 分层上的取舍：请求层（`packages/shared`）**不认识**会话 store（那是应用代码），
 * 所以这里只提供注册口——由应用在启动时把自己的「清会话」动作挂上来。
 * 一个实现、一处注册，比在每个页面各写一遍「401 就跳登录」可靠。
 */

type SessionExpiredHandler = () => void;

let handler: SessionExpiredHandler | null = null;

/** 注册会话失效的处理（应用启动时调一次）。重复注册会覆盖。 */
export function onSessionExpired(next: SessionExpiredHandler): void {
  handler = next;
}

/** 请求层内部用：确认「刷新也换不回来」时通知一次。 */
export function notifySessionExpired(): void {
  if (handler) {
    handler();
  }
}
