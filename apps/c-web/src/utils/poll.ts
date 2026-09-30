/**
 * 可见时轮询（ADR-0040 第三节）：实时通道后置，但**保留条款是「必须保留轮询兜底」**。
 *
 * 三件事是这条兜底的要点，缺一条就会变成另一种麻烦：
 *
 *  1. **页面不可见时停表**：后台标签页继续按间隔打接口，是白白烧流量与电量（用户根本不看）。
 *     所以隐藏时 `clearInterval`，回到前台再恢复——**不是**把定时器留着转；
 *  2. **回到前台立刻补一次**：用户切回来时数据最可能是旧的（他离开的这段时间正是变化发生的时候），
 *     等一个完整周期才更新，看起来就是「页面还停在我离开前」；
 *  3. **只轮询、不弹错**（见各页的 `pollRefresh`）：后台刷新失败不该把用户正在看的页面变成错误态，
 *     下一个周期还会再试。
 *
 * 返回一个「停表 + 摘掉监听」的函数，调用方在 `onBeforeUnmount` 里调用它——
 * **组件卸载后定时器还在跑**是这类实现最经典的漏洞（它会去改一个已经不存在的页面）。
 *
 * 周期是**代码常量**而不是环境变量：它是前端的交互参数（ADR-0010 那套分层针对的是后端 AI 配置），
 * 改它就是改这一行。30 秒的选择依据：消息中心的提醒是「分钟级」的（疫苗到期、指标异常），
 * 比这更密不会让人更快看到，只会让服务端多收几倍的请求。
 */
export const POLL_INTERVAL_MS = 30_000;

/** 启动轮询；返回停止函数（务必接到 `onBeforeUnmount` 上）。 */
export function startVisiblePolling(
  task: () => void | Promise<void>,
  intervalMs: number = POLL_INTERVAL_MS,
): () => void {
  let timer: number | null = null;

  const schedule = (): void => {
    if (timer !== null) return;
    timer = window.setInterval(() => void task(), intervalMs);
  };

  const pause = (): void => {
    if (timer === null) return;
    window.clearInterval(timer);
    timer = null;
  };

  const onVisibilityChange = (): void => {
    if (document.visibilityState === "visible") {
      void task();   // 回到前台先补一次，再把表起回来
      schedule();
    } else {
      pause();
    }
  };

  document.addEventListener("visibilitychange", onVisibilityChange);
  // 打开这一页时若已经在后台（例如在新后台标签里打开链接），一开始就不该起表
  if (document.visibilityState === "visible") {
    schedule();
  }

  return () => {
    pause();
    document.removeEventListener("visibilitychange", onVisibilityChange);
  };
}
