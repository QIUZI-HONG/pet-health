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
export { cApp, uploadFiles } from "./api/cApp";
export type {
  AccountExportView,
  AiConsultRequest,
  ComplianceDocumentView,
  AiConsultView,
  ArchiveRecordView,
  ArchiveRecordRequest,
  ArchiveSectionView,
  CareModeView,
  CheckInDay,
  CheckInItem,
  CheckInItemRequest,
  CheckInStreak,
  CheckInSubmitRequest,
  EpidemicRecordView,
  EpidemicRecordRequest,
  FilePresignRequest,
  FilePresignView,
  FileView,
  HealthReportView,
  HumanConsultView,
  HealthReportPayload,
  HealthReportStats,
  HealthScoreView,
  HealthScoreDimension,
  KnowledgeEntryView,
  LoginRequest,
  MessageView,
  Paged,
  PetView,
  PetCreateRequest,
  PetUpdateRequest,
  RegisterRequest,
  ReminderSettingView,
  TimelineEventView,
  TokenPair,
  UpdateProfileRequest,
  UserProfile,
} from "./api/cApp";

// 请求层：统一响应解包、鉴权头、Token 过期静默刷新、网络错误重试一次（ADR-0012）
export { http, createHttpClient } from "./http/client";
export type { RequestOptions, HttpClient, HttpClientOptions } from "./http/client";
export { ApiError, toApiFailure, toUserMessage, isIdentityError, IDENTITY_ERROR_CODES } from "./http/errors";
export { newIdempotencyKey } from "./http/idempotency";
export { tokenStore, createTokenStore } from "./http/tokenStore";
export { onSessionExpired } from "./http/sessionEvents";
export { createLatestGuard } from "./http/latestGuard";
export type { LatestGuard } from "./http/latestGuard";
export type { SessionTokens, TokenStore, LoginDomain } from "./http/tokenStore";

// 两个后台的契约生成类型（contract/provider.yaml、contract/admin.yaml 的 `components/schemas`）。
// 与 `cApp.ts` 里 `Schemas = components["schemas"]` 同理：**只把生成物取个名字，字段一个都不手写**
// （AGENTS.md：前端不手写接口类型）。两个后台各自的 `src/api/*.ts` 从这里取类型。
import type { components as providerComponents } from "./api/provider";
import type { components as adminComponents } from "./api/admin";
export type ProviderSchemas = providerComponents["schemas"];
export type AdminSchemas = adminComponents["schemas"];

// C 端那一份同样带出来。为什么需要：上面 cApp 那串清单是**逐个**带出来的（只列了当时有调用点
// 的名字），而 C 端交易与增长这条线新增的页面要用 `OrderView` / `CouponView` / `PointsCenterView`
// 这些还没在清单里的类型。页面按 `AppSchemas["OrderView"]` 取，取到的仍是生成物——
// 前端不手写接口类型（ADR-0005）这条不变。
import type { components as appComponents } from "./api/app";
export type AppSchemas = appComponents["schemas"];

// 展示格式化与字典：日期格式、日期加减、物种/性别这些标签各页面共用一份（docs/conventions.md）
export { formatDate, formatDateTime, shiftDate, todayIso } from "./format/date";
export {
  centsToAmount,
  formatAmount,
  formatAmountRange,
  isPriceFormatValid,
  isRangeBoundValid,
  isWithinRange,
  parseAmountToCents,
  priceRangeMessage,
} from "./format/money";
export { genderLabel, speciesLabel } from "./dict/pet";
export { riskTone, type RiskTone } from "./dict/risk";
export {
  COUPON_SOURCE,
  COUPON_SOURCE_OPTIONS,
  COUPON_STATUS,
  COUPON_STATUS_OPTIONS,
  COUPON_UNKNOWN,
  couponSourceLabel,
  couponStatusLabel,
} from "./dict/coupon";
export {
  applicablePetsLabel,
  businessHoursText,
  dayOfWeekLabel,
  providerStatusLabel,
  providerStatusTone,
  providerTypeLabel,
  qualificationStatusLabel,
  qualificationTypeLabel,
  reviewStatusLabel,
  reviewStatusTone,
  serviceStatusLabel,
  serviceStatusTone,
  type TagClass,
} from "./dict/provider";
