/**
 * 会话令牌的存放（ADR-0012：Token 不放 Cookie，后端只认 Authorization 头）。
 *
 * 放 localStorage 而不是内存：桌面 Web 刷新页面很频繁，放内存等于每次刷新都要重新登录。
 * 代价是 XSS 能读到它——所以 C 端不许出现 `v-html` 渲染用户输入，这条写进 ADR-0015 的组件约定里。
 *
 * **按登录域分键**：三个端各有独立登录域，Token 互不通用（ADR-0012 的 `domain` 声明）。
 * 同一个浏览器上 C 端与两个后台可能同时开着，键不分域就会互相覆盖——C 端登出会把后台的令牌
 * 一起清掉，后台的令牌又会被 C 端的请求层拿去用（域不匹配，后端按未登录处理，现象是
 * 「刚登录就 401」，且说不清是谁踩了谁）。
 *
 * C 端的键保持原样（`ph.c.*`）：改键等于把线上所有用户静默登出一次。
 */

const KEYS: Record<LoginDomain, { access: string; refresh: string }> = {
  app: { access: "ph.c.access_token", refresh: "ph.c.refresh_token" },
  provider: { access: "ph.provider.access_token", refresh: "ph.provider.refresh_token" },
  admin: { access: "ph.admin.access_token", refresh: "ph.admin.refresh_token" },
};

/** 登录域：与后端 `LoginDomain` 枚举、契约里的 `actor_domain` 同名同值（ADR-0012）。 */
export type LoginDomain = "app" | "provider" | "admin";

/** 本地存放的形状（camelCase）；契约里的 TokenPair 是接口返回的形状（snake_case），别混。 */
export interface SessionTokens {
  accessToken: string;
  refreshToken: string;
}

/** 一套令牌的读写口。三个端各拿一个（见 {@link createTokenStore}）。 */
export interface TokenStore {
  get(): SessionTokens | null;
  readonly accessToken: string | null;
  readonly refreshToken: string | null;
  save(pair: SessionTokens): void;
  clear(): void;
}

export function createTokenStore(domain: LoginDomain): TokenStore {
  const key = KEYS[domain];

  /** 内存里再存一份：避免每次请求都读一遍 localStorage。每个域各一份，互不影响。 */
  let cached: SessionTokens | null = null;

  function read(): SessionTokens | null {
    if (cached) return cached;
    const accessToken = localStorage.getItem(key.access);
    const refreshToken = localStorage.getItem(key.refresh);
    cached = accessToken && refreshToken ? { accessToken, refreshToken } : null;
    return cached;
  }

  return {
    get(): SessionTokens | null {
      return read();
    },

    get accessToken(): string | null {
      return read()?.accessToken ?? null;
    },

    get refreshToken(): string | null {
      return read()?.refreshToken ?? null;
    },

    save(pair: SessionTokens): void {
      cached = pair;
      localStorage.setItem(key.access, pair.accessToken);
      localStorage.setItem(key.refresh, pair.refreshToken);
    },

    clear(): void {
      cached = null;
      localStorage.removeItem(key.access);
      localStorage.removeItem(key.refresh);
    },
  };
}

/** C 端（app 域）的令牌。请求层的默认实例用的就是它——上线前已有的调用点一个都不用改。 */
export const tokenStore: TokenStore = createTokenStore("app");
