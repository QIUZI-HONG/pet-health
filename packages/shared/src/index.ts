/**
 * 三端共享：请求封装 / 鉴权 / 字典 / 契约生成的 TS 类型。
 *
 * 接口类型**不在这里手写**——`src/api/*.d.ts` 由 `contract/*.yaml` 生成：
 *
 *     pnpm --filter @pet-health/shared gen:api
 *
 * 改契约后必须重跑；CI 用 `gen:api:check` 检查生成物是否同步。见 ADR-0005。
 */

export type * from "./api/common";

export const SHARED_PACKAGE_READY = true;
