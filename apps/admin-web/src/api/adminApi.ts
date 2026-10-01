/**
 * 运营后台接口调用。类型全部来自 `contract/admin.yaml` 的生成物
 * （`AdminSchemas`，由契约生成，**不手写**——AGENTS.md / ADR-0005）。
 *
 * 这一层只做两件事：拼路径、给返回类型。错误处理、鉴权、刷新都在请求层（`packages/shared/src/http`）。
 *
 * 它**刻意与契约一一对应**：契约里已实现的接口在这里都有方法，哪怕当前 UI 还没用到。
 * 所以「某个方法没有调用点」不等于死代码——判据是「契约里还有没有这个接口」。
 *
 * **还没接的那几组**（本 app 的模块树里没有承载页，接上它们只会是没有调用点的方法）：
 * 反作弊记录（`/invites/risk-records`，它是只读的排查面，不是配置）、
 * 权益码与手动授予（`POST /rights/codes`、`POST|DELETE /rights/grants`；`GET /rights/codes`
 * 已接——「积分与邀请配置」页要用它给邀请阶梯的「授权益」挑码）、
 * 积分任务（`/points/tasks`）、AI 的调用留痕（`/ai/consults`）。
 * 缺的不是调用点，是页面——需要时按同样的两件事（拼路径 + 给类型）往下加即可。
 */
import type { AdminSchemas as Schemas } from "@pet-health/shared";
import { adminHttp } from "./client";

export type ProviderProfileView = Schemas["ProviderProfileView"];
export type ProviderStatusRequest = Schemas["ProviderStatusRequest"];
export type ProviderAllianceRequest = Schemas["ProviderAllianceRequest"];
export type AllianceCategoryView = Schemas["AllianceCategoryView"];
export type AllianceCategoryRequest = Schemas["AllianceCategoryRequest"];
export type BusinessHour = Schemas["BusinessHour"];
export type OnboardingApplicationSummary = Schemas["OnboardingApplicationSummary"];
export type OnboardingApplicationView = Schemas["OnboardingApplicationView"];
export type ProviderQualificationView = Schemas["ProviderQualificationView"];
export type ReviewLogView = Schemas["ReviewLogView"];
export type ServiceCategoryView = Schemas["ServiceCategoryView"];
export type ServiceCategoryRequest = Schemas["ServiceCategoryRequest"];
export type ServiceItemView = Schemas["ServiceItemView"];
export type ServiceItemRequest = Schemas["ServiceItemRequest"];
export type CatalogItemProposalSummary = Schemas["CatalogItemProposalSummary"];
export type CatalogItemProposalView = Schemas["CatalogItemProposalView"];
export type CatalogItemProposalApproveRequest = Schemas["CatalogItemProposalApproveRequest"];
export type ReviewApproveRequest = Schemas["ReviewApproveRequest"];
export type ReviewRejectRequest = Schemas["ReviewRejectRequest"];
export type ProviderServiceView = Schemas["ProviderServiceView"];

// ---- 券池与券实例（contract/admin.yaml 的 /coupon-templates、/coupon-pool/overview、/coupons）----
export type CouponTemplateView = Schemas["CouponTemplateView"];
export type CouponTemplateRequest = Schemas["CouponTemplateRequest"];
export type CouponPoolOverviewView = Schemas["CouponPoolOverviewView"];
export type CouponView = Schemas["CouponView"];
export type IssueCouponRequest = Schemas["IssueCouponRequest"];

// ---- 用户详情要读的三块只读视图：权益判定 / 权益授予 / 邀请关系 --------------
// 「用户」在 admin.yaml 里**没有自己的资源**（没有 /users 列表与详情，见 UsersView 的文件头），
// 所以用户管理这一页只能用这三块按 user_id 反查——类型也就在这三块里取。
export type RightsEvaluationView = Schemas["RightsEvaluationView"];
export type RightsGrantView = Schemas["RightsGrantView"];
export type InviteOverviewView = Schemas["InviteOverviewView"];
export type InviteRelationView = Schemas["InviteRelationView"];
export type PointsOverviewView = Schemas["PointsOverviewView"];
export type PointRecordView = Schemas["PointRecordView"];
export type PointsSettingsView = Schemas["PointsSettingsView"];

// ---- 内容审核（contract/admin.yaml 的 /community/*）----
export type ContentReviewItemView = Schemas["ContentReviewItemView"];
export type SensitiveWordView = Schemas["SensitiveWordView"];
export type SensitiveWordRequest = Schemas["SensitiveWordRequest"];

// ---- 服务者月度考核（contract/admin.yaml 的 /assessments*）----
export type AssessmentSummaryView = Schemas["AssessmentSummaryView"];
export type AssessmentRuleView = Schemas["AssessmentRuleView"];
export type AssessmentRuleRequest = Schemas["AssessmentRuleRequest"];
export type AssessmentLevelRuleRequest = Schemas["AssessmentLevelRuleRequest"];

// ---- AI 运营可调项（ADR-0010 的第二层：入库 + 运营后台，改完即时生效）----
export type PromptTemplateView = Schemas["PromptTemplateView"];
export type PromptTemplateRequest = Schemas["PromptTemplateRequest"];
export type PromptTemplateUpdateRequest = Schemas["PromptTemplateUpdateRequest"];
export type RedFlagView = Schemas["RedFlagView"];
export type RedFlagRequest = Schemas["RedFlagRequest"];
export type GradingRuleView = Schemas["GradingRuleView"];
export type GradingRuleRequest = Schemas["GradingRuleRequest"];
export type GuardTermView = Schemas["GuardTermView"];
export type GuardTermRequest = Schemas["GuardTermRequest"];
export type SwitchView = Schemas["SwitchView"];

// ---- 积分与邀请的配置（ADR-0010 / ADR-0038 / ADR-0046）----
export type PointsSettingsRequest = Schemas["PointsSettingsRequest"];
export type PointBehaviorView = Schemas["PointBehaviorView"];
export type PointBehaviorRequest = Schemas["PointBehaviorRequest"];
export type PointExchangeOptionView = Schemas["PointExchangeOptionView"];
export type PointExchangeOptionRequest = Schemas["PointExchangeOptionRequest"];
export type PointLadderTierView = Schemas["PointLadderTierView"];
export type PointLadderTierRequest = Schemas["PointLadderTierRequest"];
export type InviteLadderTierView = Schemas["InviteLadderTierView"];
export type InviteLadderTierRequest = Schemas["InviteLadderTierRequest"];
export type RightsCodeView = Schemas["RightsCodeView"];

/**
 * 分页结构：信封用契约生成的 `PageResult`，只把 `list` 的元素类型收窄
 * ——**不手写整个分页类型**（AGENTS.md：前端不手写接口类型）。
 */
export type Paged<T> = Omit<Schemas["PageResult"], "list"> & { list: T[] };

/**
 * 把「一定有」的几个字段收窄。
 *
 * 为什么需要：契约的 `components/schemas` 里多数 schema 没写 `required`，生成物于是把字段全标成
 * 可选（`id?: number`）。但列表接口返回的每一行**必然**带 id 与状态（服务端是从库里查出来的），
 * 于是每个调用点都要写一遍 `row.id ?? 0`——那种兜底会把「真没有」和「有但是 0」混成一回事。
 *
 * 收窄的只是**有没有**，不是**什么形状**：字段仍然全部来自生成物，写错名字编译期就红。
 */
export type Row<T, K extends keyof T> = T & Required<Pick<T, K>>;

export type ApplicationRow = Row<OnboardingApplicationSummary, "id" | "status" | "submitted_at">;
export type ProviderRow = Row<
  ProviderProfileView,
  "id" | "name" | "status" | "address" | "category" | "category_name" | "updated_at"
>;
export type CategoryRow = Row<ServiceCategoryView, "id" | "code" | "item_code_prefix" | "name" | "status" | "updated_at">;
export type ItemRow = Row<ServiceItemView, "id" | "code" | "name" | "price_min" | "price_max" | "status" | "updated_at">;
export type ProposalRow = Row<CatalogItemProposalSummary, "id" | "status" | "submitted_at">;
export type ListingRow = Row<ProviderServiceView, "id" | "service_code" | "price" | "status" | "updated_at">;

// 列表行必然带 id 与状态（服务端是从库里查出来的），按上面 `Row` 的理由逐个收窄。
export type CouponTemplateRow = Row<
  CouponTemplateView,
  "id" | "code" | "name" | "face_value" | "min_amount" | "valid_days" | "cost_bearer" | "scope_type" | "status" | "updated_at"
>;
export type CouponRow = Row<CouponView, "id" | "code" | "user_id" | "template_code" | "template_name" | "face_value" | "source" | "status" | "issued_at">;
export type GrantRow = Row<RightsGrantView, "id" | "user_id" | "code" | "source" | "status" | "created_at">;
export type InviteRelationRow = Row<
  InviteRelationView,
  "id" | "inviter_user_id" | "invitee_user_id" | "invite_code" | "status" | "attributed_at"
>;
export type PointRecordRow = Row<PointRecordView, "id" | "user_id" | "behavior_code" | "change" | "balance_after" | "created_at">;
/** 内容审核队列的行：`content_type` + `content_id` 才是一行（三种内容各住一张表，id 不跨表唯一） */
export type ContentRow = Row<ContentReviewItemView, "content_type" | "content_id" | "content" | "status" | "created_at">;
export type WordRow = Row<SensitiveWordView, "id" | "word" | "enabled" | "updated_at">;
export type AssessmentRow = Row<AssessmentSummaryView, "id" | "period" | "total_score" | "level" | "calculated_at">;

// AI 可调项的列表行：服务端从库里查出来，这几列必然有（理由同上）
export type PromptRow = Row<PromptTemplateView, "id" | "code" | "version" | "system_prompt" | "gray_ratio" | "enabled" | "updated_at">;
export type SwitchRow = Row<SwitchView, "id" | "code" | "enabled">;
export type RedFlagRow = Row<RedFlagView, "id" | "code" | "pattern" | "level" | "action_hint" | "enabled" | "updated_at">;
export type GradingRuleRow = Row<GradingRuleView, "id" | "code" | "name" | "match_terms" | "min_level" | "enabled" | "updated_at">;
export type GuardTermRow = Row<GuardTermView, "id" | "kind" | "term" | "enabled" | "updated_at">;
/** 知识条目（复核用）：只给复核要看的字段，正文与载荷不在列表里（ADR-0054）。 */
// 名字**必须在 admin 侧取**：`@pet-health/shared` 出口的 `KnowledgeEntryView` 是 C 端那份
// （带正文与 risk_level），两侧同名不同形状——从 shared 直接导入会静默拿到另一个 schema。
// 契约与 DTO 也特意叫 `AdminKnowledgeEntryView`：漂移守卫要求 schema 名 ↔ 类名一一对应
export type AdminKnowledgeEntryView = Schemas["AdminKnowledgeEntryView"];
export type KnowledgeEntryRow = Row<AdminKnowledgeEntryView,
  "id" | "code" | "category_code" | "title" | "summary" | "source_title" | "review_status"
  | "reviewed_by" | "reviewed_credential" | "reviewed_at" | "updated_at">;

// 积分与邀请的配置行：行为表的键是 `code`（没有 id），其余各有 id（或 threshold）
export type PointBehaviorRow = Row<PointBehaviorView, "code" | "name" | "points" | "status" | "updated_at">;
export type ExchangeOptionRow = Row<PointExchangeOptionView, "id" | "name" | "points_cost" | "coupon_template_id" | "status" | "updated_at">;
export type PointLadderRow = Row<PointLadderTierView, "id" | "threshold_points" | "coupon_template_id" | "coupon_count" | "status" | "updated_at">;
export type InviteLadderRow = Row<InviteLadderTierView, "threshold" | "status" | "updated_at">;
export type RightsCodeRow = Row<RightsCodeView, "code" | "name" | "status">;

const BASE = "/api/v1/admin";

export const adminApp = {
  // ---- 标准目录：分类（只增改，编码与前缀不可变）----
  listCategories(includeDisabled = true, signal?: AbortSignal): Promise<CategoryRow[]> {
    return adminHttp.get<CategoryRow[]>(`${BASE}/catalog/categories`, { include_disabled: includeDisabled }, { signal });
  },
  createCategory(body: ServiceCategoryRequest): Promise<ServiceCategoryView> {
    return adminHttp.post<ServiceCategoryView>(`${BASE}/catalog/categories`, body);
  },
  /** 修改分类：编码与前缀**不可变更**（传了别的值一律 40001，不是静默忽略）。 */
  updateCategory(categoryId: number, body: ServiceCategoryRequest): Promise<ServiceCategoryView> {
    return adminHttp.put<ServiceCategoryView>(`${BASE}/catalog/categories/${categoryId}`, body);
  },

  // ---- 标准目录：项目（含价格区间）----
  listItems(
    params?: { categoryCode?: string; keyword?: string; status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ItemRow>> {
    return adminHttp.get<Paged<ItemRow>>(`${BASE}/catalog/items`, {
      category_code: params?.categoryCode,
      keyword: params?.keyword,
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  createItem(body: ServiceItemRequest): Promise<ServiceItemView> {
    return adminHttp.post<ServiceItemView>(`${BASE}/catalog/items`, body);
  },
  /** 修改目录项：编码与所属分类**不可变更**。收窄区间不会自动下架存量服务项（ADR-0034）。 */
  updateItem(itemId: number, body: ServiceItemRequest): Promise<ServiceItemView> {
    return adminHttp.put<ServiceItemView>(`${BASE}/catalog/items/${itemId}`, body);
  },
  /** 启用 1 / 停用 0：停用**只挡新的选品**，已上架的服务项保持原状。 */
  updateItemStatus(itemId: number, status: 0 | 1): Promise<ServiceItemView> {
    return adminHttp.put<ServiceItemView>(`${BASE}/catalog/items/${itemId}/status`, { status });
  },

  // ---- 目录外提案审核 ----
  listProposals(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ProposalRow>> {
    return adminHttp.get<Paged<ProposalRow>>(`${BASE}/catalog/item-requests`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  getProposal(requestId: number, signal?: AbortSignal): Promise<CatalogItemProposalView> {
    return adminHttp.get<CatalogItemProposalView>(`${BASE}/catalog/item-requests/${requestId}`, undefined, { signal });
  },
  /** 通过：**最终区间必填**（不默认采纳建议值——让申请方定义规则等于让区间校验自己批自己）。 */
  approveProposal(requestId: number, body: CatalogItemProposalApproveRequest): Promise<CatalogItemProposalView> {
    return adminHttp.post<CatalogItemProposalView>(`${BASE}/catalog/item-requests/${requestId}/approve`, body);
  },
  rejectProposal(requestId: number, body: ReviewRejectRequest): Promise<CatalogItemProposalView> {
    return adminHttp.post<CatalogItemProposalView>(`${BASE}/catalog/item-requests/${requestId}/reject`, body);
  },

  // ---- 服务者入驻审核 ----
  listApplications(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ApplicationRow>> {
    return adminHttp.get<Paged<ApplicationRow>>(`${BASE}/provider-applications`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  getApplication(applicationId: number, signal?: AbortSignal): Promise<OnboardingApplicationView> {
    return adminHttp.get<OnboardingApplicationView>(`${BASE}/provider-applications/${applicationId}`, undefined, { signal });
  },
  approveApplication(applicationId: number, remark?: string): Promise<OnboardingApplicationView> {
    const body: ReviewApproveRequest = { remark: remark?.trim() ? remark.trim() : null };
    return adminHttp.post<OnboardingApplicationView>(`${BASE}/provider-applications/${applicationId}/approve`, body);
  },
  rejectApplication(applicationId: number, reason: string): Promise<OnboardingApplicationView> {
    return adminHttp.post<OnboardingApplicationView>(`${BASE}/provider-applications/${applicationId}/reject`, { reason });
  },

  // ---- 服务者列表与状态处置 ----
  // 关键字只搜名称：联系电话是密文，模糊搜索在字段级加密下做不到（ADR-0013）
  listProviders(
    params?: { status?: number; type?: number; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ProviderRow>> {
    return adminHttp.get<Paged<ProviderRow>>(`${BASE}/providers`, {
      status: params?.status,
      type: params?.type,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  getProvider(providerId: number, signal?: AbortSignal): Promise<ProviderProfileView> {
    return adminHttp.get<ProviderProfileView>(`${BASE}/providers/${providerId}`, undefined, { signal });
  },
  /** 冻结（3）= 清退落点 / 解冻（1）。被驳回（2）的服务者不能从这里恢复为正常。 */
  updateProviderStatus(providerId: number, body: ProviderStatusRequest): Promise<ProviderProfileView> {
    return adminHttp.put<ProviderProfileView>(`${BASE}/providers/${providerId}/status`, body);
  },
  /** 指定联盟分类归属。目标维度必须是启用中的（停用档 40001）。 */
  updateProviderAlliance(providerId: number, body: ProviderAllianceRequest): Promise<ProviderProfileView> {
    return adminHttp.put<ProviderProfileView>(`${BASE}/providers/${providerId}/alliance`, body);
  },
  /**
   * 指定区域编码（V45）。传 null / 空串表示**清空**——门店可能从一个片区摘下来。
   *
   * 「区域保护」目前只到这一格：**可维护、可筛选**，排他性规则仍未定（见契约说明）。
   */
  updateProviderRegion(providerId: number, regionCode: string | null): Promise<ProviderProfileView> {
    return adminHttp.put<ProviderProfileView>(`${BASE}/providers/${providerId}/region`, {
      region_code: regionCode,
    });
  },

  // ---- 联盟分类维度（`provider.category` 的值域，运营可维护）----
  // 不分页：维度的量级是「十几档」，分页在这里只会多一次点击
  listAllianceCategories(signal?: AbortSignal): Promise<AllianceCategoryView[]> {
    return adminHttp.get<AllianceCategoryView[]>(`${BASE}/alliance-categories`, undefined, { signal });
  },
  createAllianceCategory(body: AllianceCategoryRequest): Promise<AllianceCategoryView> {
    return adminHttp.post<AllianceCategoryView>(`${BASE}/alliance-categories`, body);
  },
  /** 改名称 / 说明 / 顺序。**编码不可改**（契约里写明修改路径会忽略 code）。 */
  updateAllianceCategory(categoryId: number, body: AllianceCategoryRequest): Promise<AllianceCategoryView> {
    return adminHttp.put<AllianceCategoryView>(`${BASE}/alliance-categories/${categoryId}`, body);
  },
  /** 启用 / 停用。停用**不移动既有归属**，只挡住新的指派。 */
  updateAllianceCategoryStatus(categoryId: number, enabled: 0 | 1): Promise<AllianceCategoryView> {
    return adminHttp.put<AllianceCategoryView>(`${BASE}/alliance-categories/${categoryId}/status`, { enabled });
  },

  // ---- 服务上架审核 ----
  // 每一行都带着目录侧的名称与当前区间（现取，不是快照）：「这个价是不是还在区间内」不用翻页去对
  listServiceListings(
    params?: { status?: number; providerId?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ListingRow>> {
    return adminHttp.get<Paged<ListingRow>>(`${BASE}/service-listings`, {
      status: params?.status,
      provider_id: params?.providerId,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 通过即上架（服务者提交审核的意图就是上架）。 */
  approveServiceListing(serviceId: number, remark?: string): Promise<ProviderServiceView> {
    const body: ReviewApproveRequest = { remark: remark?.trim() ? remark.trim() : null };
    return adminHttp.post<ProviderServiceView>(`${BASE}/service-listings/${serviceId}/approve`, body);
  },
  rejectServiceListing(serviceId: number, reason: string): Promise<ProviderServiceView> {
    return adminHttp.post<ProviderServiceView>(`${BASE}/service-listings/${serviceId}/reject`, { reason });
  },

  // ---- 券模板：平台统一定义，服务者侧只能「选券并承诺额度」（ADR-0037 第三节）----
  /** 默认连停用的一起给（运营要能看见自己停用过的模板）。 */
  listCouponTemplates(
    params?: { status?: number; costBearer?: number; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<CouponTemplateRow>> {
    return adminHttp.get<Paged<CouponTemplateRow>>(`${BASE}/coupon-templates`, {
      status: params?.status,
      cost_bearer: params?.costBearer,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  createCouponTemplate(body: CouponTemplateRequest): Promise<CouponTemplateView> {
    return adminHttp.post<CouponTemplateView>(`${BASE}/coupon-templates`, body);
  },
  /** 修改模板：编码与成本归属**不可变更**；改面额/门槛/有效期只影响之后新发的券（已发的存快照）。 */
  updateCouponTemplate(templateId: number, body: CouponTemplateRequest): Promise<CouponTemplateView> {
    return adminHttp.put<CouponTemplateView>(`${BASE}/coupon-templates/${templateId}`, body);
  },
  /** 启用 1 / 停用 0：停用只挡新的发放与新的贡献，已发出的券照常可核销。 */
  updateCouponTemplateStatus(templateId: number, status: 0 | 1): Promise<CouponTemplateView> {
    return adminHttp.put<CouponTemplateView>(`${BASE}/coupon-templates/${templateId}/status`, { status });
  },
  /** 券池的一句话账（含对账口径 `reconciliation`）。 */
  getCouponPoolOverview(signal?: AbortSignal): Promise<CouponPoolOverviewView> {
    return adminHttp.get<CouponPoolOverviewView>(`${BASE}/coupon-pool/overview`, undefined, { signal });
  },
  /** 券实例分页：运营查询与排查（按发放时间倒序）。 */
  listCoupons(
    params?: { templateId?: number; source?: number; status?: number; userId?: number; providerId?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<CouponRow>> {
    return adminHttp.get<Paged<CouponRow>>(`${BASE}/coupons`, {
      template_id: params?.templateId,
      source: params?.source,
      status: params?.status,
      user_id: params?.userId,
      provider_id: params?.providerId,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 定向发放平台补贴券。模板必须是 `cost_bearer = 2`，否则 40900 —— 平台不替服务者放券。 */
  issueCoupon(body: IssueCouponRequest): Promise<CouponView> {
    return adminHttp.post<CouponView>(`${BASE}/coupons`, body);
  },

  // ---- 权益与积分：用户详情页的只读视图（没有「用户」资源，只能按 user_id 反查）----
  /** 权益引擎唯一的口径出口：每个码是否生效 + 来源 + 到期（`effective=false` 的也会列出来）。 */
  getUserRights(userId: number, signal?: AbortSignal): Promise<RightsEvaluationView> {
    return adminHttp.get<RightsEvaluationView>(`${BASE}/rights/users/${userId}`, undefined, { signal });
  },
  /** 授予记录：同一来源各一条（ADR-0038 第三节），所以「订阅得的」与「邀请得的」是两行。 */
  listRightsGrants(
    params?: { userId?: number; code?: string; status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<GrantRow>> {
    return adminHttp.get<Paged<GrantRow>>(`${BASE}/rights/grants`, {
      user_id: params?.userId,
      code: params?.code,
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  getPointsOverview(signal?: AbortSignal): Promise<PointsOverviewView> {
    return adminHttp.get<PointsOverviewView>(`${BASE}/points/overview`, undefined, { signal });
  },
  /** 积分流水：每条都带变动后余额，所以用户当前的余额就是最新一条的 `balance_after`。 */
  listPointRecords(
    params?: { userId?: number; behaviorCode?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<PointRecordRow>> {
    return adminHttp.get<Paged<PointRecordRow>>(`${BASE}/points/records`, {
      user_id: params?.userId,
      behavior_code: params?.behaviorCode,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  // ---- 邀请 ----
  /** 邀请总览（含阶梯达成）。`valid_rate` 是有效邀请转化率，替代了交付文档的 K 因子（ADR-0039）。 */
  getInviteOverview(signal?: AbortSignal): Promise<InviteOverviewView> {
    return adminHttp.get<InviteOverviewView>(`${BASE}/invites/overview`, undefined, { signal });
  },
  listInviteRelations(
    params?: { inviterUserId?: number; inviteeUserId?: number; status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<InviteRelationRow>> {
    return adminHttp.get<Paged<InviteRelationRow>>(`${BASE}/invites/relations`, {
      inviter_user_id: params?.inviterUserId,
      invitee_user_id: params?.inviteeUserId,
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- 内容审核：三种内容共用一个形状，但**必须按类型分别查**（各住一张表）----
  listContents(
    params: { contentType: number; status?: number; authorId?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ContentRow>> {
    return adminHttp.get<Paged<ContentRow>>(`${BASE}/community/contents`, {
      content_type: params.contentType,
      status: params.status,
      author_id: params.authorId,
      page: params.page,
      page_size: params.pageSize,
    }, { signal });
  },
  /** 通过：**没有请求体**——通过时没有要对作者说的话（内容只是出现了），所以不摆备注框。 */
  approveContent(contentType: number, contentId: number): Promise<ContentReviewItemView> {
    return adminHttp.post<ContentReviewItemView>(`${BASE}/community/contents/${contentType}/${contentId}/approve`);
  },
  /** 驳回 / 下架（同一个状态迁移）：理由必填，会原样出现在**作者自己的**那份内容里。 */
  rejectContent(contentType: number, contentId: number, reason: string): Promise<ContentReviewItemView> {
    return adminHttp.post<ContentReviewItemView>(`${BASE}/community/contents/${contentType}/${contentId}/reject`, { reason });
  },
  listSensitiveWords(
    params?: { enabled?: boolean; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<WordRow>> {
    return adminHttp.get<Paged<WordRow>>(`${BASE}/community/sensitive-words`, {
      enabled: params?.enabled,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  createSensitiveWord(body: SensitiveWordRequest): Promise<SensitiveWordView> {
    return adminHttp.post<SensitiveWordView>(`${BASE}/community/sensitive-words`, body);
  },
  /** 改词 / 启停 / 说明。**没有删除动作**：停用即可——留着它才能解释「昨天为什么拦了那条内容」。 */
  updateSensitiveWord(wordId: number, body: SensitiveWordRequest): Promise<SensitiveWordView> {
    return adminHttp.put<SensitiveWordView>(`${BASE}/community/sensitive-words/${wordId}`, body);
  },

  // ---- 服务者月度考核 ----
  /** 考核规则（权重 / 三项达标线 / 三档阈值）。改规则只影响之后算出的账期（历史存快照）。 */
  getAssessmentRules(signal?: AbortSignal): Promise<AssessmentRuleView> {
    return adminHttp.get<AssessmentRuleView>(`${BASE}/assessments/rules`, undefined, { signal });
  },
  /** 修改考核规则：**只归超级管理员**（ADR-0037 第一节的矩阵）。权重之和必须为 100，基础档必须从 0 起。 */
  updateAssessmentRules(body: AssessmentRuleRequest): Promise<AssessmentRuleView> {
    return adminHttp.put<AssessmentRuleView>(`${BASE}/assessments/rules`, body);
  },
  /** 全平台考核列表。只有**已经算过的账期**才有行（每月 1 日算上月）。 */
  listAssessments(
    params?: { period?: string; level?: number; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<AssessmentRow>> {
    return adminHttp.get<Paged<AssessmentRow>>(`${BASE}/assessments`, {
      period: params?.period,
      level: params?.level,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- AI 运营可调项：ADR-0010 的第二层（入库 + 运营后台，改完即时生效）----
  // 这些表由 Python 侧带 TTL 缓存直读（ADR-0033 第三节）：写完**不需要重启任何进程**，
  // 代价是最多滞后一个 TTL。四条贯穿这一组的口径见 contract/admin.yaml 的 ai 段注释。
  /** 提示词版本列表（按版本号倒序，正文一起给）。`review_status` 读得到但改不了（ADR-0040 第二节）。 */
  listAiPrompts(params?: { code?: string }, signal?: AbortSignal): Promise<PromptRow[]> {
    return adminHttp.get<PromptRow[]>(`${BASE}/ai/prompts`, { code: params?.code }, { signal });
  },
  /** 新建一个提示词版本。同 (code, version) 已存在时 40900——**正文改动必须换版本号**（ADR-0010）。 */
  createAiPrompt(body: PromptTemplateRequest): Promise<PromptTemplateView> {
    return adminHttp.post<PromptTemplateView>(`${BASE}/ai/prompts`, body);
  },
  /** 调灰度 / 启停（回滚动作）。**正文不能在这里改**；把比例改回 0 就是回滚，不必发版。 */
  updateAiPrompt(promptId: number, body: PromptTemplateUpdateRequest): Promise<PromptTemplateView> {
    return adminHttp.put<PromptTemplateView>(`${BASE}/ai/prompts/${promptId}`, body);
  },
  /**
   * 硬红线词分页。命中红线的咨询**不经过模型**（ADR-0021）——所以这是一张安全网词表。
   * `enabled` 是 JSON boolean（读写同形状），写 0/1 整数会被拒成 40001。
   */
  listAiRedFlags(
    params?: { enabled?: boolean; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<RedFlagRow>> {
    return adminHttp.get<Paged<RedFlagRow>>(`${BASE}/ai/red-flags`, {
      enabled: params?.enabled,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  createAiRedFlag(body: RedFlagRequest): Promise<RedFlagView> {
    return adminHttp.post<RedFlagView>(`${BASE}/ai/red-flags`, body);
  },
  updateAiRedFlag(redFlagId: number, body: RedFlagRequest): Promise<RedFlagView> {
    return adminHttp.put<RedFlagView>(`${BASE}/ai/red-flags/${redFlagId}`, body);
  },
  /** 停用（软删）一条红线：**不是物理删除**——留痕里引用过它的咨询仍要能查到当时的规则。 */
  disableAiRedFlag(redFlagId: number): Promise<void> {
    return adminHttp.delete<void>(`${BASE}/ai/red-flags/${redFlagId}`);
  },
  /** 分级规则（命中词 → 风险下限，**只抬不降**）。不分页：这组规则的量级是个位数。 */
  listAiGradingRules(params?: { enabled?: boolean }, signal?: AbortSignal): Promise<GradingRuleRow[]> {
    return adminHttp.get<GradingRuleRow[]>(`${BASE}/ai/grading-rules`, { enabled: params?.enabled }, { signal });
  },
  createAiGradingRule(body: GradingRuleRequest): Promise<GradingRuleView> {
    return adminHttp.post<GradingRuleView>(`${BASE}/ai/grading-rules`, body);
  },
  updateAiGradingRule(ruleId: number, body: GradingRuleRequest): Promise<GradingRuleView> {
    return adminHttp.put<GradingRuleView>(`${BASE}/ai/grading-rules/${ruleId}`, body);
  },
  /** 护栏词表（**输出侧**过滤：`drug` 药名 / `phrase` 越界表述）。这类词不能停用——它挡的是不该出口的话。 */
  listAiGuardTerms(params?: { kind?: "drug" | "phrase" }, signal?: AbortSignal): Promise<GuardTermRow[]> {
    return adminHttp.get<GuardTermRow[]>(`${BASE}/ai/guard-terms`, { kind: params?.kind }, { signal });
  },
  createAiGuardTerm(body: GuardTermRequest): Promise<GuardTermView> {
    return adminHttp.post<GuardTermView>(`${BASE}/ai/guard-terms`, body);
  },
  updateAiGuardTerm(termId: number, body: GuardTermRequest): Promise<GuardTermView> {
    return adminHttp.put<GuardTermView>(`${BASE}/ai/guard-terms/${termId}`, body);
  },
  /** 运行时开关的**当前状态**（语义在代码里）。库里读不到时一律按关闭算（ADR-0033 第三节）。 */
  listAiSwitches(signal?: AbortSignal): Promise<SwitchRow[]> {
    return adminHttp.get<SwitchRow[]>(`${BASE}/ai/switches`, undefined, { signal });
  },
  /**
   * 切一个开关（一键降级）。**只允许改已存在的 code**：新增一个没人读的开关会让运营以为它生效了，
   * 那比没有开关更糟（ADR-0010）。`force_rule_only` 打开后全量走规则通道、不调模型。
   */
  updateAiSwitch(switchCode: string, enabled: boolean): Promise<SwitchView> {
    return adminHttp.put<SwitchView>(`${BASE}/ai/switches/${switchCode}`, { enabled });
  },

  /**
   * 知识条目列表（交付文档 F024/F025 的内容，按复核状态查阅）。
   *
   * **正文与 L1 载荷不在列表里**（那是检索素材）：这一屏管的是「哪些还没复核」，不是读内容。
   * 默认未复核在前——运营打开就想看到还差哪些。
   */
  listAiKnowledgeEntries(
    params: { reviewStatus?: string; categoryCode?: string; keyword?: string; page?: number; pageSize?: number } = {},
    signal?: AbortSignal,
  ): Promise<Paged<KnowledgeEntryRow>> {
    return adminHttp.get<Paged<KnowledgeEntryRow>>(`${BASE}/ai/knowledge-entries`, {
      review_status: params.reviewStatus,
      category_code: params.categoryCode,
      keyword: params.keyword,
      page: params.page,
      page_size: params.pageSize,
    }, { signal });
  },

  /**
   * 复核一条知识条目：`vet` 通过 / `reject` 打回。
   *
   * **这是唯一能改 `review_status` 的接口**（ADR-0054），且必须填复核人与资质——
   * `vetted` 是一句专业背书（只有它能进 AI 引用的 citations），不是一次开关操作。
   */
  reviewAiKnowledgeEntry(
    code: string,
    body: { action: "vet" | "reject"; reviewer: string; credential: string },
  ): Promise<AdminKnowledgeEntryView> {
    return adminHttp.post<AdminKnowledgeEntryView>(`${BASE}/ai/knowledge-entries/${encodeURIComponent(code)}/review`, body);
  },

  // ---- 积分与邀请的配置（ADR-0038 / ADR-0046；这一页只有配置，没有用户与流水）----
  /** 积分规则设置（每日获取上限）。本端现在可写，见 GrowthConfigView。 */
  getPointsSettings(signal?: AbortSignal): Promise<PointsSettingsView> {
    return adminHttp.get<PointsSettingsView>(`${BASE}/points/settings`, undefined, { signal });
  },
  /** 改每日获取上限（1–1000）。**邀请与一次性项不占这个上限**（ADR-0038 第四节）。 */
  updatePointsSettings(body: PointsSettingsRequest): Promise<PointsSettingsView> {
    return adminHttp.put<PointsSettingsView>(`${BASE}/points/settings`, body);
  },
  /** 行为分值表。**只能改分值、频次与启停，不能新增行为**——行为码要有代码去触发它（ADR-0046 第七节）。 */
  listPointBehaviors(signal?: AbortSignal): Promise<PointBehaviorRow[]> {
    return adminHttp.get<PointBehaviorRow[]>(`${BASE}/points/behaviors`, undefined, { signal });
  },
  updatePointBehavior(behaviorCode: string, body: PointBehaviorRequest): Promise<PointBehaviorView> {
    return adminHttp.put<PointBehaviorView>(`${BASE}/points/behaviors/${behaviorCode}`, body);
  },
  /** 兑换档位（**只兑平台补贴券**：兑换消耗的是平台的钱，不消耗服务者的贡献额度）。 */
  listPointExchangeOptions(signal?: AbortSignal): Promise<ExchangeOptionRow[]> {
    return adminHttp.get<ExchangeOptionRow[]>(`${BASE}/points/exchange-options`, undefined, { signal });
  },
  createPointExchangeOption(body: PointExchangeOptionRequest): Promise<PointExchangeOptionView> {
    return adminHttp.post<PointExchangeOptionView>(`${BASE}/points/exchange-options`, body);
  },
  updatePointExchangeOption(optionId: number, body: PointExchangeOptionRequest): Promise<PointExchangeOptionView> {
    return adminHttp.put<PointExchangeOptionView>(`${BASE}/points/exchange-options/${optionId}`, body);
  },
  /** 月度阶梯档位。**种子里一个都没有**（ADR-0046 第八节：门槛与奖励没有规则依据，不编）。 */
  listPointLadderTiers(signal?: AbortSignal): Promise<PointLadderRow[]> {
    return adminHttp.get<PointLadderRow[]>(`${BASE}/points/ladder-tiers`, undefined, { signal });
  },
  createPointLadderTier(body: PointLadderTierRequest): Promise<PointLadderTierView> {
    return adminHttp.post<PointLadderTierView>(`${BASE}/points/ladder-tiers`, body);
  },
  updatePointLadderTier(tierId: number, body: PointLadderTierRequest): Promise<PointLadderTierView> {
    return adminHttp.put<PointLadderTierView>(`${BASE}/points/ladder-tiers/${tierId}`, body);
  },
  /**
   * 邀请阶梯档位（门槛固定五档 1/3/5/10/15，**改的是每档发什么**）。
   * 奖励物未配置的档位照样记录达成，只是不发东西（ADR-0046 第四节）。
   */
  listInviteLadderTiers(signal?: AbortSignal): Promise<InviteLadderRow[]> {
    return adminHttp.get<InviteLadderRow[]>(`${BASE}/invites/ladder-tiers`, undefined, { signal });
  },
  /** 配置某一档的奖励：门槛不可改；`reward_type` 传 null 表示这一档不发东西。 */
  updateInviteLadderTier(threshold: number, body: InviteLadderTierRequest): Promise<InviteLadderTierView> {
    return adminHttp.put<InviteLadderTierView>(`${BASE}/invites/ladder-tiers/${threshold}`, body);
  },
  /** 权益码表：邀请阶梯的「授权益」要按码挑（手打一个不存在的码只会换来 40001）。 */
  listRightsCodes(signal?: AbortSignal): Promise<RightsCodeRow[]> {
    return adminHttp.get<RightsCodeRow[]>(`${BASE}/rights/codes`, undefined, { signal });
  },
};
