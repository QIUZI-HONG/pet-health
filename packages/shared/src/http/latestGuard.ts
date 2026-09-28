/**
 * 「只认最新一次响应」的守卫。
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
 *   const mine = latest.claim();
 *   const data = await cApp.getHealthScore(petId);
 *   if (!latest.isCurrent(mine)) return;   // 期间又发起过加载：这次的结果已经过期
 *   score.value = data;
 * }
 * ```
 *
 * 为什么放在请求层而不是每个页面各写一遍 `let seq = 0`：这是**同一套判断**，
 * 抄几处就有几处会忘（首页与档案页早先就是这样漏的）。这里只有两个方法：
 * 领一个号、问一句「还是最新的吗」——守卫本身不碰请求与状态，页面照旧自己决定怎么落地。
 */
export interface LatestGuard {
  /** 领一个号（每次开始一次加载都调）。 */
  claim(): number;
  /** 这个号还是最新的吗？不是就说明期间又发起过加载，调用方应当丢弃这次结果。 */
  isCurrent(token: number): boolean;
}

export function createLatestGuard(): LatestGuard {
  let latest = 0;
  return {
    claim: () => (latest += 1),
    isCurrent: (token: number) => token === latest,
  };
}
