/**
 * 会话令牌的存放（ADR-0012：Token 不放 Cookie，后端只认 Authorization 头）。
 *
 * 放 localStorage 而不是内存：桌面 Web 刷新页面很频繁，放内存等于每次刷新都要重新登录。
 * 代价是 XSS 能读到它——所以 C 端不许出现 `v-html` 渲染用户输入，这条写进 ADR-0015 的组件约定里。
 */

const ACCESS_KEY = "ph.c.access_token";
const REFRESH_KEY = "ph.c.refresh_token";

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

/** 内存里再存一份：避免每次请求都读一遍 localStorage。 */
let cached: TokenPair | null = null;

function read(): TokenPair | null {
  if (cached) return cached;
  const accessToken = localStorage.getItem(ACCESS_KEY);
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  cached = accessToken && refreshToken ? { accessToken, refreshToken } : null;
  return cached;
}

export const tokenStore = {
  get(): TokenPair | null {
    return read();
  },

  get accessToken(): string | null {
    return read()?.accessToken ?? null;
  },

  get refreshToken(): string | null {
    return read()?.refreshToken ?? null;
  },

  save(pair: TokenPair): void {
    cached = pair;
    localStorage.setItem(ACCESS_KEY, pair.accessToken);
    localStorage.setItem(REFRESH_KEY, pair.refreshToken);
  },

  clear(): void {
    cached = null;
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};
