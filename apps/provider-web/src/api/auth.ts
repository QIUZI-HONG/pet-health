/**
 * 服务者后台的登录接口（F021）。
 *
 * **为什么要单独一个文件**：登录/换发/退出是**唯一三条不带令牌**的接口
 * （其余接口都靠请求层的拦截器挂 `Authorization`）。放在 `providerApi.ts` 里会和那些
 * 「必须具备本域令牌」的方法混在一起，读的人容易以为它们也要先登录。
 *
 * 类型全部取自契约生成物（ADR-0005：前端不手写接口类型）；凭据形状与 C 端是同一个
 * （`LoginRequest` / `RefreshRequest` / `LogoutRequest` / `TokenPair` 在 app.yaml 里，
 * provider.yaml 用 `$ref` 引用它——后台与 C 端用的是同一批账号，ADR-0035 决定 4）。
 */
import type { AppSchemas } from "@pet-health/shared";
import { providerHttp } from "./client";

type LoginRequest = AppSchemas["LoginRequest"];
type LogoutRequest = AppSchemas["LogoutRequest"];
type TokenPair = AppSchemas["TokenPair"];

const BASE = "/api/v1/provider/auth";

export const providerAuth = {
  /** 手机号 + 密码登录；拿到的是 **provider 域**令牌（域不通用，见契约的说明）。 */
  login(body: LoginRequest): Promise<TokenPair> {
    return providerHttp.post<TokenPair>(`${BASE}/login`, body);
  },

  /** 退出：吊销 Refresh。已签发的 Access 在剩余有效期（≤2 小时）内仍有效（ADR-0012）。 */
  logout(body: LogoutRequest): Promise<void> {
    return providerHttp.post<void>(`${BASE}/logout`, body);
  },
};
