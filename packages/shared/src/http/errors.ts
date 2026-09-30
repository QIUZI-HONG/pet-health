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

/**
 * 页面加载失败时要写进界面的两样东西：一句话 + 请求 ID。
 *
 * 为什么在 {@link toUserMessage} 之外还要一个：页面级的失败提示除了文案，还要把
 * **请求 ID** 显示给用户（客服据此在后端日志里按 traceId 追这一次请求，见 ADR-0029）。
 * 原先四个视图各自写了这段 if/else（六行 × 4 处），而「哪些错误带请求 ID」这条规则
 * 就散在四处——漏写一处的表现是页面上少一行追溯信息，没人会发现。
 *
 * 规则：只有我们抛的 {@link ApiError} 带 requestId（它是后端信封里的字段）；
 * 其它异常没有可追的东西，给空串（界面据此不渲染那一行）。
 */
export function toApiFailure(error: unknown, fallback: string): { message: string; requestId: string } {
  return {
    message: toUserMessage(error, fallback),
    requestId: error instanceof ApiError ? error.requestId : "",
  };
}

/**
 * 服务端按「没权限」处理的错误码（contract/common.yaml）：40100 未登录 / 40101 令牌过期 /
 * 40300 无权限。它们**重试没有意义**（重试还是同样的拒绝），所以页面要进「无权限」态而不是
 * 「失败 + 重试」态。
 *
 * 为什么要导出：这一集合原先在 8 处各写了一遍（两个后台的分页与提交 composable、
 * 运营后台的 `failure.ts`、C 端的会话 store，以及若干 `||` 链），少写一个码的表现是
 * 「40300 显示了重试按钮，点几次都是同样失败」——从界面上看不出哪里错了。
 */
export const IDENTITY_ERROR_CODES: ReadonlySet<number> = new Set([40100, 40101, 40300]);

/** 这个错误是不是「身份 / 权限」类失败（见 {@link IDENTITY_ERROR_CODES}）。 */
export function isIdentityError(error: unknown): boolean {
  return error instanceof ApiError && IDENTITY_ERROR_CODES.has(error.code);
}
