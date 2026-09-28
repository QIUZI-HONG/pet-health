/**
 * 「只认最新一次请求」的守卫：发新请求时**作废旧请求**。
 *
 * 解决的问题：页面上的并发加载（切宠物、切筛选、翻页）**先发的请求可能后回来**，
 * 把新数据盖成旧数据。首页那种「评分/打卡跟着当前宠物走」的地方，串一次就会显示**另一只宠物**的
 * 健康数据——比慢更难发现，也更危险（2026-09-28 测试报告 D16）。
 *
 * 用法：
 *
 * ```ts
 * const latest = createLatestGuard();
 *
 * async function load(petId: number) {
 *   const { token, signal } = latest.claim();          // 领号，并作废上一次请求
 *   const data = await cApp.getHealthScore(petId, signal);
 *   if (!latest.isCurrent(token)) return;               // 期间又发起过加载：这次的结果已经过期
 *   score.value = data;
 * }
 * ```
 *
 * 为什么放在请求层而不是每个页面各写一遍 `let seq = 0`：这是**同一套判断**，
 * 抄几处就有几处会忘（首页与档案页早先就是这样漏的）。
 *
 * 两个动作各管一件事，别混：
 * - `signal` 让**旧请求本身**停下来（省一次往返与一次等待；服务端不会因此取消处理，见下）；
 * - `isCurrent` 让**旧响应**即使回来了也不落地。
 *   只做前者不够：请求可能已经到达服务端、响应正在路上，abort 只是客户端不等了。
 *
 * 诚实说明：abort 不会让服务端停止工作（没有做请求取消的跨服务传播），
 * 所以它对 AI 咨询这种「服务端要花几秒、已经花了钱」的调用只省客户端的等待，
 * 不省额度——这类调用的正确性仍然靠 `isCurrent` 保证。
 */

/** 一次宣告：令牌 + 与它配套的取消信号。 */
export interface LatestClaim {
  token: number;
  signal: AbortSignal;
}

export interface LatestGuard {
  /** 领一个号；**同时作废上一次请求**（若还在飞）。 */
  claim(): LatestClaim;
  /** 这个号还是最新的吗？不是就说明期间又发起过请求，调用方应当丢弃这次结果。 */
  isCurrent(token: number): boolean;
}

export function createLatestGuard(): LatestGuard {
  let latest = 0;
  let inFlight: AbortController | null = null;
  return {
    claim(): LatestClaim {
      inFlight?.abort();
      inFlight = new AbortController();
      latest += 1;
      return { token: latest, signal: inFlight.signal };
    },
    isCurrent: (token: number) => token === latest,
  };
}
