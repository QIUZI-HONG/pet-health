/**
 * 「只认最新响应」的守卫（测试报告 D16）。
 *
 * 真实场景：切宠物/切筛选时先发的请求后回来，把新数据盖成旧的——首页那种地方会让用户看到
 * **另一只宠物**的健康数据。这里把一个「乱序返回」的时序钉住：守卫只放行最后一次加载的结果。
 */
import { describe, expect, it } from "vitest";
import { createLatestGuard } from "@pet-health/shared";

describe("并发守卫", () => {
  it("先发的请求后回来时，它的结果要被丢弃", () => {
    const latest = createLatestGuard();

    const first = latest.claim();     // 切到宠物 A
    const second = latest.claim();    // 立刻又切到宠物 B

    expect(latest.isCurrent(first)).toBe(false);   // A 的响应回来了：丢掉
    expect(latest.isCurrent(second)).toBe(true);   // B 的响应回来了：落地
  });

  it("只有一次加载时结果照常落地（别把正常路径也拦掉）", () => {
    const latest = createLatestGuard();
    const only = latest.claim();
    expect(latest.isCurrent(only)).toBe(true);
  });

  it("拿到号之后又领了新号，旧号一律失效（多轮切换）", () => {
    const latest = createLatestGuard();
    const a = latest.claim();
    const b = latest.claim();
    const c = latest.claim();

    expect([latest.isCurrent(a), latest.isCurrent(b), latest.isCurrent(c)]).toEqual([false, false, true]);
  });
});
