/**
 * 运营后台的登录接口（F021）。
 *
 * **单独一个文件**的理由与服务者后台那份相同：登录/换发/退出是唯一三条不带令牌的接口，
 * 混在 `adminApi.ts` 里会让人以为它们也要先登录。类型取自契约生成物（ADR-0005）。
 *
 * 与 C 端、服务者后台的区别是**准入**：本端是名单制，不在 `app.console.admin-user-ids`
 * 里的账号登录会拿到 40300（不是 40100——口令是对的，别让用户以为密码错了）。
 */
import type { AppSchemas } from "@pet-health/shared";
import { adminHttp } from "./client";

type LoginRequest = AppSchemas["LoginRequest"];
type LogoutRequest = AppSchemas["LogoutRequest"];
type TokenPair = AppSchemas["TokenPair"];

const BASE = "/api/v1/admin/auth";

export const adminAuth = {
  /** 手机号 + 密码登录；拿到的是 **admin 域**令牌。 */
  login(body: LoginRequest): Promise<TokenPair> {
    return adminHttp.post<TokenPair>(`${BASE}/login`, body);
  },

  /** 退出：吊销 Refresh（已签发的 Access 在剩余有效期内仍有效，ADR-0012）。 */
  logout(body: LogoutRequest): Promise<void> {
    return adminHttp.post<void>(`${BASE}/logout`, body);
  },
};
