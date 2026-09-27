/**
 * 请求层：统一响应解包、鉴权头、40101 静默刷新、网络错误重试一次。
 *
 * 一次调用的完整路径（ADR-0012 定的行为）：
 *
 *   1. 带上 `Authorization: Bearer <access_token>`（如果有）；
 *   2. 响应体 `code === 0` → **直接把 `data` 交给调用方**，调用方看不到信封；
 *   3. `code !== 0` → 抛 `ApiError`，业务错误**不重试**（用户点了重试才重试）；
 *   4. `code === 40101`（Token 过期）→ 用 Refresh 换一对新令牌后**重发原请求一次**；
 *      换不到就清掉会话并抛错，让页面显示「请先登录」；
 *   5. 网络层失败（断网、后端没起来）→ 自动重试一次。
 *
 * 刷新是**单飞的**：并发请求同时收到 40101 时只会发一次 refresh，其余等它——否则多个请求
 * 会各换一次令牌，而 Refresh 是一次性的（ADR-0012），后换的会把先换的挤掉，用户直接被登出。
 */
import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from "axios";
import type { components } from "../api/common";
import { ApiError } from "./errors";
import { tokenStore } from "./tokenStore";

type Envelope = components["schemas"]["ApiResponse"];

/** 契约里定义的统一响应信封。前端其余部分不该看见它——见下面的解包。 */
interface RawEnvelope {
  code: number;
  message: string;
  data?: unknown;
  request_id?: string;
}

const REQUEST_TIMEOUT_MS = 15_000;
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
      const response = await refreshClient.post<RawEnvelope>("/api/v1/app/auth/refresh", {
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
    } catch {
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

function toApiError(error: AxiosError<RawEnvelope>): ApiError {
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
  return new ApiError(`服务异常（HTTP ${error.response.status}）`, {
    code: -1,
    httpStatus: error.response.status,
  });
}

/** 网络类失败才值得自动重试一次；业务错误重试只会重复同样的失败。 */
function isRetryable(error: ApiError): boolean {
  return error.network || error.code === 50000 || error.code === 50300;
}

async function send<T>(config: AxiosRequestConfig, attempt = 0): Promise<T> {
  try {
    const response = await client.request<RawEnvelope>(config);
    const envelope = response.data;
    if (!envelope || typeof envelope.code !== "number") {
      throw new ApiError("响应结构不符合契约（缺少 code 字段）", { code: -1 });
    }
    if (envelope.code === 0) {
      return envelope.data as T;
    }
    const apiError = new ApiError(envelope.message || "请求失败", {
      code: envelope.code,
      requestId: envelope.request_id,
      httpStatus: response.status,
    });
    // Token 过期：换一次令牌再重发，只重发一次
    if (apiError.isTokenExpired && attempt === 0 && (await refreshSession())) {
      return send<T>(config, attempt + 1);
    }
    if (apiError.isTokenExpired) {
      tokenStore.clear();
    }
    throw apiError;
  } catch (error) {
    if (error instanceof ApiError) {
      if (isRetryable(error) && attempt === 0) {
        return send<T>(config, attempt + 1);
      }
      throw error;
    }
    const apiError = toApiError(error as AxiosError<RawEnvelope>);
    if (apiError.isTokenExpired && attempt === 0 && (await refreshSession())) {
      return send<T>(config, attempt + 1);
    }
    if (apiError.isTokenExpired) {
      tokenStore.clear();
    }
    if (isRetryable(apiError) && attempt === 0) {
      return send<T>(config, attempt + 1);
    }
    throw apiError;
  }
}

/** 传一个 traceId 进来，前后端日志就能对上（后端会用这个值当 request_id）。 */
function traceHeaders(traceId?: string): Record<string, string> | undefined {
  return traceId ? { [TRACE_HEADER]: traceId } : undefined;
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>, traceId?: string): Promise<T> {
    return send<T>({ method: "GET", url, params, headers: traceHeaders(traceId) });
  },
  post<T>(url: string, body?: unknown, traceId?: string): Promise<T> {
    return send<T>({ method: "POST", url, data: body, headers: traceHeaders(traceId) });
  },
  put<T>(url: string, body?: unknown, traceId?: string): Promise<T> {
    return send<T>({ method: "PUT", url, data: body, headers: traceHeaders(traceId) });
  },
  delete<T>(url: string, traceId?: string): Promise<T> {
    return send<T>({ method: "DELETE", url, headers: traceHeaders(traceId) });
  },
};

export type { Envelope };
