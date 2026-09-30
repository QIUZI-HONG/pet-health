/**
 * 请求层的深测（2026-09-28 深测轮）。
 *
 * 为什么值得单独一组：请求层是**所有页面共用的那条路**——令牌怎么带、40101 怎么静默刷新、
 * 刷新失败怎么收场、什么错误重试、什么错误不重试，全在 `packages/shared/src/http/client.ts` 一个文件里。
 * 而 `packages/shared` 至今**没有自己的测试运行器**（package.json 里没有 test 脚本），
 * 这些行为此前只被「组件测试里 mock 掉的 shared」绕过去：D15 修好的「会话失效广播」
 * 在请求层里一行断言都没有。
 *
 * 放在这里是因为 C 端是当前唯一配了 runner 的包（补 shared 自己的 runner 是另一件事，见测试报告）。
 *
 * 桩打在 axios 层（与 photo-upload / ai-consult 同一手法）：`client.ts` 建两个实例，
 * 第一个是业务客户端、第二个是刷新专用（不挂请求拦截器、避免刷新失败后递归刷新）。
 * 测试直接换掉它们各自的 `request`，并**自己跑一遍请求拦截器**（否则断言不到 Authorization 头）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

interface FakeInstance {
  handler: ((config: Record<string, any>) => Promise<any>) | null;
  interceptors: Array<(config: any) => any>;
  calls: Array<Record<string, any>>;
}

const state = vi.hoisted(() => {
  const instances: FakeInstance[] = [];
  function make(): unknown {
    const self: FakeInstance = { handler: null, interceptors: [], calls: [] };
    const instance = {
      interceptors: { request: { use: (fn: (config: any) => any) => void self.interceptors.push(fn) } },
      async request(config: Record<string, any>) {
        let next = { ...config, headers: { ...(config.headers ?? {}) } };
        for (const interceptor of self.interceptors) {
          next = (await interceptor(next)) ?? next;
        }
        self.calls.push(next);
        if (!self.handler) {
          throw new Error("用例没有设置 handler");
        }
        return self.handler(next);
      },
      async post(url: string, data?: unknown) {
        return instance.request({ method: "post", url, data });
      },
    };
    instances.push(self);
    return instance;
  }
  return { instances, make };
});

vi.mock("axios", () => {
  const axios = {
    create: () => state.make(),
    // 真实实现认的是 axios 自己造的取消对象；这里用一个标记位代替
    isCancel: (error: unknown) => (error as { cancelled?: boolean })?.cancelled === true,
    put: () => Promise.resolve(),
  };
  return { default: axios, ...axios };
});

import { ApiError, http, onSessionExpired, tokenStore } from "@pet-health/shared";

/** 业务客户端（第 1 个 create）与刷新专用客户端（第 2 个 create）。 */
const business = (): FakeInstance => state.instances[0];
const refresher = (): FakeInstance => state.instances[1];

/**
 * 每个用例前的复位。
 *
 * 等一个宏任务这一步不能省：`client.ts` 的「单飞刷新」标记是用 `setTimeout(0)` 复位的，
 * 不等它，上一个用例那次刷新的结果会被下一个用例当成「刚刷新成功」——
 * 表现为「刷新明明没发生，请求却被重放了」。
 */
async function resetRequestLayer(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  tokenStore.clear();
  onSessionExpired(() => undefined); // 清掉上一个用例注册的处理器
  business().calls.length = 0;
  refresher().calls.length = 0;
  business().handler = null;
  refresher().handler = null;
}

function envelope(code: number, data: unknown = null, message = "ok") {
  return { status: 200, data: { code, message, data, request_id: "trace-1" } };
}

function tokenEnvelope(access: string, refresh: string) {
  return envelope(0, { access_token: access, refresh_token: refresh, expires_in: 7200 });
}

/** 后端返回的业务错误（HTTP 200 + 非 0 code，契约就是这么定的）。 */
const tokenExpired = () => envelope(40101, null, "登录凭证已过期");

describe("请求层：信封、鉴权头、错误分类", () => {
  beforeEach(resetRequestLayer);

  it("code=0：把 data 交给调用方，调用方看不到信封", async () => {
    business().handler = async () => envelope(0, { name: "豆豆" });

    await expect(http.get("/pets")).resolves.toEqual({ name: "豆豆" });
  });

  it("带上 Authorization 与 X-Request-Id：前后端日志靠它串起来", async () => {
    tokenStore.save({ accessToken: "at-1", refreshToken: "rt-1" });
    business().handler = async () => envelope(0, null);

    await http.get("/pets", { page: 1 }, { traceId: "t-9" });

    const sent = business().calls[0];
    expect(sent.headers.Authorization).toBe("Bearer at-1");
    expect(sent.headers["X-Request-Id"]).toBe("t-9");
  });

  it("没登录时不带 Authorization 头（不是带一个空的 Bearer）", async () => {
    business().handler = async () => envelope(0, null);

    await http.get("/legal/privacy_policy");

    expect(business().calls[0].headers.Authorization).toBeUndefined();
  });

  it("写操作带 Idempotency-Key；一切默认请求都不带（ADR-0028：不带它的行为完全不变）", async () => {
    business().handler = async () => envelope(0, null);

    await http.post("/orders", { pet_id: 1 }, { idempotencyKey: "key-1" });
    expect(business().calls[0].headers["Idempotency-Key"]).toBe("key-1");

    // 不能给所有写请求都塞一个空串或随机值：契约说「不带这个头的请求行为不变」，
    // 而随机键等于把每次重试都当成一次新的写操作
    await http.post("/orders", { pet_id: 1 });
    expect(business().calls[1].headers["Idempotency-Key"]).toBeUndefined();
  });

  it("业务错误（40001）不重试：重试只会重复同样的失败", async () => {
    business().handler = async () => envelope(40001, null, "参数不合法");

    await expect(http.get("/pets?page=0")).rejects.toMatchObject({ code: 40001 });
    expect(business().calls).toHaveLength(1);
    expect(refresher().calls).toHaveLength(0);
  });

  it("响应不符合契约（缺 code）：按结构错误抛出，不当成成功", async () => {
    business().handler = async () => ({ status: 200, data: { message: "网关说的" } });

    await expect(http.get("/pets")).rejects.toMatchObject({ code: -1 });
  });

  it("网关返回 HTML 的 500：文案给用户看的那句，内部细节不往外抛", async () => {
    business().handler = async () => {
      throw { isAxiosError: true, response: { status: 500, data: "<html>502 Bad Gateway</html>" } };
    };

    const error = await http.get("/pets").catch((e: unknown) => e as ApiError);

    expect((error as ApiError).httpStatus).toBe(500);
    expect((error as ApiError).message).toContain("HTTP 500");
    expect((error as ApiError).message).not.toContain("html");
  });
});

describe("请求层：40101 的静默刷新（ADR-0012）", () => {
  beforeEach(resetRequestLayer);

  it("40101：换一次令牌后重放原请求，用户无感", async () => {
    tokenStore.save({ accessToken: "old", refreshToken: "rt" });
    let attempts = 0;
    business().handler = async () => (++attempts === 1 ? tokenExpired() : envelope(0, "拿到数据"));
    refresher().handler = async () => tokenEnvelope("new-at", "new-rt");

    await expect(http.get("/pets")).resolves.toBe("拿到数据");

    expect(business().calls).toHaveLength(2); // 原请求 + 重放
    expect(refresher().calls).toHaveLength(1);
    // 重放时带的是**新**令牌：Refresh 一次性换发，重放还用旧的会立刻再 40101
    expect(business().calls[1].headers.Authorization).toBe("Bearer new-at");
    expect(tokenStore.refreshToken).toBe("new-rt");
  });

  it("并发两个 40101 只换一次令牌：各换各的会把用户挤下线", async () => {
    tokenStore.save({ accessToken: "old", refreshToken: "rt" });
    let attempts = 0;
    business().handler = async () => (++attempts <= 2 ? tokenExpired() : envelope(0, `ok-${attempts}`));
    refresher().handler = async () => {
      await new Promise((resolve) => setTimeout(resolve, 5)); // 让两个请求都进到「等刷新」这一步
      return tokenEnvelope("new-at", "new-rt");
    };

    await Promise.all([http.get("/a"), http.get("/b")]);

    expect(refresher().calls).toHaveLength(1);
    expect(business().calls).toHaveLength(4); // 两个原请求 + 两个重放
  });

  it("刷新也换不回来：清掉令牌并广播会话失效（D15：界面不能停在「看起来已登录」）", async () => {
    tokenStore.save({ accessToken: "old", refreshToken: "rt" });
    const expired = vi.fn();
    onSessionExpired(expired);
    business().handler = async () => tokenExpired();
    refresher().handler = async () => envelope(40101, null, "登录凭证已失效");

    await expect(http.get("/pets")).rejects.toMatchObject({ code: 40101 });

    expect(tokenStore.get()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it("重放后仍然 40101：只重放一次，随后结束会话（不陷入刷新循环）", async () => {
    tokenStore.save({ accessToken: "old", refreshToken: "rt" });
    const expired = vi.fn();
    onSessionExpired(expired);
    business().handler = async () => tokenExpired();
    refresher().handler = async () => tokenEnvelope("new-at", "new-rt");

    await expect(http.get("/pets")).rejects.toMatchObject({ code: 40101 });

    expect(refresher().calls).toHaveLength(1);
    expect(business().calls).toHaveLength(2);
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it("没有 refresh token 时不去刷新（避免一次必然失败的往返）", async () => {
    business().handler = async () => tokenExpired();

    await expect(http.get("/pets")).rejects.toMatchObject({ code: 40101 });

    expect(refresher().calls).toHaveLength(0);
    expect(business().calls).toHaveLength(1);
  });
});

describe("请求层：重试与取消", () => {
  beforeEach(resetRequestLayer);

  it("网络类失败自动重试一次：后端重启的 30 秒里用户不该看到失败", async () => {
    let attempts = 0;
    business().handler = async () => {
      if (++attempts === 1) {
        throw { isAxiosError: true, message: "Network Error" };
      }
      return envelope(0, "恢复了");
    };

    await expect(http.get("/pets")).resolves.toBe("恢复了");
    expect(business().calls).toHaveLength(2);
  });

  it("服务端临时故障（50000）重试一次", async () => {
    let attempts = 0;
    business().handler = async () =>
      ++attempts === 1 ? envelope(50000, null, "服务器内部错误") : envelope(0, "恢复了");

    await expect(http.get("/pets")).resolves.toBe("恢复了");
    expect(business().calls).toHaveLength(2);
  });

  it("取消（守卫作废的请求）：不重试、不刷新，原样抛出让调用方丢弃", async () => {
    const cancelled = { cancelled: true };
    business().handler = async () => {
      throw cancelled;
    };

    await expect(http.get("/pets")).rejects.toBe(cancelled);
    expect(business().calls).toHaveLength(1);
    expect(refresher().calls).toHaveLength(0);
  });

  it("**已知风险**：非幂等的 POST 遇到 50000 也会被自动重试一次", async () => {
    // 5xx 重试对 GET 是安全的，对 POST 不是：后端可能在写完之后才报 50000
    //（或者响应在回程丢了），重试就会产生第二次写。当前各写入接口都靠业务上的幂等
    //（打卡是 upsert、注销幂等、消息已读可重复）兜住，所以没有暴露出来；
    // 但 AI 咨询这种「按次计费/计额度」的接口没有幂等键——重试 = 可能多花一次钱。
    // 这个用例把现状钉住：真要做「只对幂等方法重试」，先让它红。
    let attempts = 0;
    business().handler = async () =>
      ++attempts === 1 ? envelope(50000, null, "服务器内部错误") : envelope(0, { id: 1 });

    await expect(http.post("/pets/1/ai-consults", { question: "它今天不吃东西" })).resolves.toEqual({ id: 1 });
    expect(business().calls).toHaveLength(2);
  });
});
