/**
 * 三端共享：请求封装 / 鉴权 / 契约生成的 TS 类型。
 *
 * 接口类型**不在这里手写**——`src/api/*.d.ts` 由 `contract/*.yaml` 生成：
 *
 *     pnpm --filter @pet-health/shared gen:api
 *
 * 改契约后必须重跑；CI 用 `gen:api:check` 检查生成物是否同步。见 ADR-0005。
 */

// 公共组件（统一响应、分页）——所有端共用
export type * from "./api/common";

// 按端划分的接口调用。领域类型从各自的 cApp/providerApp 里显式带出来：
// 不能用 `export type *` 直接转出 app.d.ts / provider.d.ts——它们和 common.d.ts 都导出了
// `paths` / `components` / `operations` 这些同名成员，批量转出会报「重复导出」。
export { cApp } from "./api/cApp";
export type {
  LoginRequest,
  Pet,
  PetCreateRequest,
  PetUpdateRequest,
  RegisterRequest,
  TokenPair,
  UpdateProfileRequest,
  UserProfile,
} from "./api/cApp";

// 请求层：统一响应解包、鉴权头、Token 过期静默刷新、网络错误重试一次（ADR-0012）
export { http } from "./http/client";
export { ApiError } from "./http/errors";
export { tokenStore } from "./http/tokenStore";
export type { TokenPair as SessionTokens } from "./http/tokenStore";

export const SHARED_PACKAGE_READY = true;
