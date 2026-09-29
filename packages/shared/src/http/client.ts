/**
 * 请求层：统一响应解包、鉴权头、40101 静默刷新、网络错误重试一次。
 *
 * 一次调用的完整路径（ADR-0012 定的行为）：
 *
 *   1. 带上 `Authorization: Bearer <access_token>`（如果有）；
 *   2. 响应体 `code === 0` → **直接把 `data` 交给调用方**，调用方看不到信封；
 *   3. `code !== 0` → 抛 `ApiError`，业务错误**不重试**（用户点了重试才重试）；
 *   4. `code === 40101`（Token 过期）→ 用 Refresh 换一对新令牌后**重发原请求一次**；
 *      换不到就清掉令牌并广播「会话失效」（见 sessionEvents.ts），页面回到未登录；
 *   5. 网络层失败（断网、后端没起来）→ 自动重试一次。
 *
 * 刷新是**单飞的**：并发请求同时收到 40101 时只会发一次 refresh，其余等它——否则多个请求
 * 会各换一次令牌，而 Refresh 是一次性的（ADR-0012），后换的会把先换的挤掉，用户直接被登出。
 */
import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from "axios";
import type { components } from "../api/common";
import { ApiError } from "./errors";
import { notifySessionExpired } from "./sessionEvents";
import { tokenStore } from "./tokenStore";

/**
 * 统一响应的信封类型——**直接来自契约生成物**（contract/common.yaml 的 ApiResponse），
 * 前端不手写接口类型（AGENTS.md / ADR-0005）。
 */
type Envelope = components["schemas"]["ApiResponse"];

const REQUEST_TIMEOUT_MS = 15_000;
/** 上传超时比普通请求长：一张 10MB 的图在慢网络上 15 秒不够（后端的上传凭证有效期 30 分钟）。 */
const UPLOAD_TIMEOUT_MS = 120_000;
const TRACE_HEADER = "X-Request-Id";

const client: AxiosInstance = axios.create({
  timeout: REQUEST_TIMEOUT_MS,
  headers: { "Content-Type": "application/json" },
});

client.interceptors.request.use((config) => {
  const token = tokenStore.accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 换令牌专用：不挂上面的拦截器，否则刷新失败会递归刷新。 */
const refreshClient: AxiosInstance = axios.create({ timeout: REQUEST_TIMEOUT_MS });

let refreshing: Promise<boolean> | null = null;

async function refreshSession(): Promise<boolean> {
  if (refreshing) {
    return refreshing;
  }
  refreshing = (async () => {
    const refreshToken = tokenStore.refreshToken;
    if (!refreshToken) {
      return false;
    }
    try {
      const response = await refreshClient.post<Envelope>("/api/v1/app/auth/refresh", {
        refresh_token: refreshToken,
      });
      const payload = response.data?.data as
        | { access_token?: string; refresh_token?: string }
        | undefined;
      if (response.data?.code !== 0 || !payload?.access_token || !payload.refresh_token) {
        return false;
      }
      tokenStore.save({ accessToken: payload.access_token, refreshToken: payload.refresh_token });
      return true;
    } catch (error) {
      // 不吞异常：换令牌失败会直接导致用户被登出，日志里必须留下原因
      console.warn("[auth] 刷新令牌失败，本次会话将结束", error);
      return false;
    } finally {
      // 交回给下一个调用者前先清空，保证「单飞」只覆盖同一批并发请求
      setTimeout(() => {
        refreshing = null;
      }, 0);
    }
  })();
  return refreshing;
}

function toApiError(error: AxiosError<Envelope>): ApiError {
  const body = error.response?.data;
  if (body && typeof body.code === "number") {
    return new ApiError(body.message || "请求失败", {
      code: body.code,
      requestId: body.request_id,
      httpStatus: error.response?.status,
    });
  }
  if (!error.response) {
    return new ApiError("网络连接失败，请检查网络后重试", { code: 0, network: true });
  }
  // 这种响应不是我们后端的信封（多半是网关/代理报的错），所以没有 request_id。
  // 文案先给人看的那句，技术细节放括号里——用户不需要先读懂 HTTP 500。
  return new ApiError(`服务暂时不可用，请稍后重试（HTTP ${error.response.status}）`, {
    code: -1,
    httpStatus: error.response.status,
  });
}

/**
 * 会话结束：清掉令牌，并**通知上层**把会话状态一起清掉。
 *
 * 只清令牌是不够的——界面读的是会话 store（user / pets / 状态标记），
 * 不同步就会停在「看起来已登录、实际每个请求都 401」的错位上（测试报告 D15）。
 */
function endSession(): void {
  tokenStore.clear();
  notifySessionExpired();
}

/** 可重试的是「这次没成，下次可能成」的失败：网络类，以及 50000/50300 这种服务端临时故障。
 *  参数错误、越权、冲突这些业务错误重试只会重复同样的失败，所以不重试。 */
function isRetryable(error: ApiError): boolean {
  return error.network || error.code === 50000 || error.code === 50300;
}

/**
 * 一次重发的结果。
 *
 * **不能用 `null` 表示「没有重发」**：响应信封里的 `data` 本来就可以是 null，
 * 用 null 当哨兵会把「重发成功且数据为空」误判成「没重发」，然后白抛一个错。
 */
type RetryOutcome<T> = { retried: true; value: T } | { retried: false };

const NOT_RETRIED: RetryOutcome<never> = { retried: false };

/**
 * Token 过期 → 换一次令牌 → 重发原请求一次；换不到就清掉会话。
 *
 * 形如 `code === 40101` 的两种到达方式（响应体里的业务码、以及 HTTP 层就被判成过期的）
 * 都走这里。原先两段代码各写了一份「刷新成功就重发、失败就清会话」，而这类重复
 * 最容易出现的后果是改了一处忘了另一处——同一个令牌过期场景在两条路径上行为不一致。
 *
 * @param attempt 已经重发过几次。只在第一次（0）时刷新，避免 40101 反复触发刷令牌
 * @returns 重发过就带值返回；否则 `retried: false`，由调用方继续自己的错误处理
 */
async function retryAfterTokenExpiry<T>(
  config: AxiosRequestConfig,
  attempt: number,
  error: ApiError,
): Promise<RetryOutcome<T>> {
  if (!error.isTokenExpired) {
    return NOT_RETRIED;
  }
  if (attempt === 0 && (await refreshSession())) {
    return { retried: true, value: await send<T>(config, attempt + 1) };
  }
  // 走到这里只有两种可能：refresh 失败，或者已经重发过一次仍然 40101——
  // 两种都说明这个会话救不回来了
  endSession();
  return NOT_RETRIED;
}

/**
 * 网络类 / 服务端临时故障 → 重发一次。
 *
 * **只重发一次**：`attempt` 已经大于 0 就不再重试，否则后端持续 500 会变成无限递归。
 * 注意这里不区分 HTTP 方法：非幂等的 POST 也会被重发，这是现状（测试报告 14.2 第 4 条
 * 用用例钉着），改成「只对幂等方法重试」要先让那条用例红。
 */
async function retryTransient<T>(
  config: AxiosRequestConfig,
  attempt: number,
  error: ApiError,
): Promise<RetryOutcome<T>> {
  if (isRetryable(error) && attempt === 0) {
    return { retried: true, value: await send<T>(config, attempt + 1) };
  }
  return NOT_RETRIED;
}

/**
 * 重发规则收在一处：先按「令牌过期」处理，再按「临时故障」处理，都不成立就把原错误抛出去。
 *
 * 两条到达路径（响应体里的业务码、以及传输层抛出来的）都走这一个函数，
 * 所以「刷新成功就重发、失败就清会话」只写一遍。
 */
async function settleFailure<T>(
  config: AxiosRequestConfig,
  attempt: number,
  error: ApiError,
): Promise<T> {
  const refreshed = await retryAfterTokenExpiry<T>(config, attempt, error);
  if (refreshed.retried) {
    return refreshed.value;
  }
  const retried = await retryTransient<T>(config, attempt, error);
  if (retried.retried) {
    return retried.value;
  }
  throw error;
}

async function send<T>(config: AxiosRequestConfig, attempt = 0): Promise<T> {
  try {
    const response = await client.request<Envelope>(config);
    const envelope = response.data;
    if (!envelope || typeof envelope.code !== "number") {
      throw new ApiError("响应结构不符合契约（缺少 code 字段）", { code: -1 });
    }
    if (envelope.code === 0) {
      return envelope.data as T;
    }
    throw new ApiError(envelope.message || "请求失败", {
      code: envelope.code,
      requestId: envelope.request_id,
      httpStatus: response.status,
    });
  } catch (error) {
    // **判断顺序不能换**：`instanceof ApiError` 必须排在 `axios.isCancel` 前面。
    // 取消永远不是 ApiError，而 ApiError 也不需要问 axios——顺序反了就等于对每个业务错误
    // 都多调一次 `axios.isCancel`，那既没有收益，又在 axios 被替换/打桩时（测试里就是这么干的）
    // 把一次正常的业务报错变成 TypeError，最后被上层显示成兜底文案。
    if (error instanceof ApiError) {
      return settleFailure<T>(config, attempt, error);
    }
    // 取消是我们自己叫停的（守卫作废了这次请求），不是故障：直接抛出去让调用方丢弃，
    // 既不重试、也不报错
    if (axios.isCancel(error)) {
      throw error;
    }
    return settleFailure<T>(config, attempt, toApiError(error as AxiosError<Envelope>));
  }
}

/** 传一个 traceId 进来，前后端日志就能对上（后端会用这个值当 request_id）。 */
function traceHeaders(traceId?: string): Record<string, string> | undefined {
  return traceId ? { [TRACE_HEADER]: traceId } : undefined;
}

/**
 * 可选参数收成一个对象。
 *
 * 为什么不是位置参数：它们都很少用（traceId 目前**没有任何调用方**，signal 只有四个页面用），
 * 摊成四个位置参数后每个用到 signal 的调用点都得写 `undefined, undefined, signal` ——
 * 那种调用点读起来像在填一张表，而不是在表达意图。
 */
export interface RequestOptions {
  /** 前后端日志串联用；后端会把它当 `request_id` 回带。 */
  traceId?: string;
  /** 取消信号：页面用 `createLatestGuard` 领到的那个——发新请求会 abort 掉旧请求。 */
  signal?: AbortSignal;
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
    return send<T>({ method: "GET", url, params, headers: traceHeaders(options?.traceId), signal: options?.signal });
  },
  post<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return send<T>({ method: "POST", url, data: body, headers: traceHeaders(options?.traceId), signal: options?.signal });
  },
  put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return send<T>({ method: "PUT", url, data: body, headers: traceHeaders(options?.traceId), signal: options?.signal });
  },
  delete<T>(url: string, options?: RequestOptions): Promise<T> {
    return send<T>({ method: "DELETE", url, headers: traceHeaders(options?.traceId), signal: options?.signal });
  },
  /**
   * 直传原始字节（切片 #95 的上传路径）。
   *
   * **不走统一响应信封**：目标是我们自己签发的 `open` 域地址（将来是对象存储），成功返回 204 空体。
   * 所以这里刻意绕开 `send`——按 `code === 0` 解包会把一个正常的 204 当成结构错误。
   *
   * 带 `Authorization` 也无妨（后端那一组接口不要求登录，授权在签名里），但**不依赖它**：
   * 将来换成对象存储的直传地址时，多带一个头会被对方拒绝（签名不匹配），所以这里不带头。
   */
  async putRaw(url: string, body: Blob): Promise<void> {
    await axios.put(url, body, {
      timeout: UPLOAD_TIMEOUT_MS,
      headers: { "Content-Type": body.type || "application/octet-stream" },
    });
  },
};

export type { Envelope };
