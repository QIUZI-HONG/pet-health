/**
 * 「只认最新一次请求」的守卫（测试报告 D16）。
 *
 * 真实场景：切宠物/切筛选时先发的请求后回来，把新数据盖成旧的——首页那种地方会让用户看到
 * **另一只宠物**的健康数据。守卫用两招防它：abort 掉旧请求（省一次等待），
 * 并让旧响应即使回来了也不落地（isCurrent）。
 */
import { describe, expect, it } from "vitest";
import { createLatestGuard } from "@pet-health/shared";

describe("并发守卫", () => {
  it("先发的请求后回来时，它的结果要被丢弃", () => {
    const latest = createLatestGuard();

    const first = latest.claim();     // 切到宠物 A
    const second = latest.claim();    // 立刻又切到宠物 B

    expect(latest.isCurrent(first.token)).toBe(false);   // A 的响应回来了：丢掉
    expect(latest.isCurrent(second.token)).toBe(true);   // B 的响应回来了：落地
  });

  it("发新请求时把旧请求 abort 掉（取消不只是「不看结果」）", () => {
    const latest = createLatestGuard();

    const first = latest.claim();
    expect(first.signal.aborted).toBe(false);

    latest.claim();                       // 新请求发出

    expect(first.signal.aborted).toBe(true);   // 旧请求被叫停，不再白等
  });

  it("没有新请求时不会自己 abort（正常路径不受影响）", () => {
    const latest = createLatestGuard();
    const only = latest.claim();

    expect(only.signal.aborted).toBe(false);
    expect(latest.isCurrent(only.token)).toBe(true);
  });

  it("拿到号之后又领了新号，旧号一律失效（多轮切换）", () => {
    const latest = createLatestGuard();
    const a = latest.claim();
    const b = latest.claim();
    const c = latest.claim();

    expect([latest.isCurrent(a.token), latest.isCurrent(b.token), latest.isCurrent(c.token)])
      .toEqual([false, false, true]);
  });
});
