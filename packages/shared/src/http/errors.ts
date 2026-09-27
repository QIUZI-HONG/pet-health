/**
 * 接口调用失败时抛出的错误。
 *
 * 三种来源分得很清楚，因为调用方对它们的处理完全不同：
 *   - `code > 0`：后端返回的业务错误码（见 contract/common.yaml），message 可直接展示给用户；
 *   - `code = 0` 且 `network = true`：网络/传输层失败（断网、后端没起来），可重试；
 *   - `code = -1`：响应结构不符合契约（比如网关返回了 HTML），属于要报 bug 的情况。
 */
export class ApiError extends Error {
  readonly code: number;
  readonly requestId: string;
  readonly network: boolean;
  readonly httpStatus?: number;

  constructor(message: string, options: {
    code: number;
    requestId?: string;
    network?: boolean;
    httpStatus?: number;
  }) {
    super(message);
    this.name = "ApiError";
    this.code = options.code;
    this.requestId = options.requestId ?? "";
    this.network = options.network ?? false;
    this.httpStatus = options.httpStatus;
  }

  /** Token 已过期——前端应当拿去换新令牌，而不是跳登录。请求层用它触发静默刷新。 */
  get isTokenExpired(): boolean {
    return this.code === 40101;
  }
}
