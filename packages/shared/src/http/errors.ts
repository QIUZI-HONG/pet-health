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

/**
 * 把任意异常转成**能直接显示给用户的一句话**。
 *
 * 为什么收成一个函数：视图里「catch 住 → 写进 errorMessage」的写法重复了二十来处，
 * 每处都要自己判断 `instanceof ApiError`，于是新页面会再抄一遍、而且抄得越来越不一致
 * （有的漏了兜底文案，有的把原生 Error 的英文 message 直接摆到界面上）。
 *
 * 规则只有两条：
 *   - 确实是我们抛的 {@link ApiError} → 用后端那句话。契约说 `message` 是「前端直接展示」
 *     的文案（contract/common.yaml），措辞已经在服务端定好了；
 *   - 其它异常（原生 `TypeError`、第三方库抛的）→ 一律用调用方给的中文兜底。
 *     它们的 message 是英文技术描述，属于日志，不属于界面。
 *
 * @param error    捕获到的任意异常。用 `unknown` 而不是 `Error`：TS 的 catch 变量本来就不保证是 Error
 * @param fallback 兜底文案，由调用点按场景给（例如「打卡失败，请稍后重试」）
 */
export function toUserMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}
