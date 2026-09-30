/**
 * 服务者后台接口调用。类型全部来自 `contract/provider.yaml` 的生成物
 * （`ProviderSchemas`，由契约生成，**不手写**——AGENTS.md / ADR-0005）。
 *
 * 这一层只做两件事：拼路径、给返回类型。错误处理、鉴权、刷新都在请求层（`packages/shared/src/http`）。
 *
 * 它**刻意与契约一一对应**：契约 `provider.yaml` 里已实现的接口在这里都有方法，哪怕当前 UI
 * 还没用到（例如 `getApplication`）。所以「某个方法没有调用点」不等于死代码——判据是
 * 「契约里还有没有这个接口」。契约里删了接口，这里的方法要一起删。
 *
 * 与 `packages/shared/src/api/cApp.ts` 同构，区别只有一处：那一份在 shared 里（三个端都引它），
 * 这一份属于本端——服务者后台的接口只有本端会调。
 */
import type { ProviderSchemas as Schemas } from "@pet-health/shared";
import { newIdempotencyKey } from "@pet-health/shared";
import { providerHttp } from "./client";

export type OnboardingApplicationSummary = Schemas["OnboardingApplicationSummary"];
export type OnboardingApplicationView = Schemas["OnboardingApplicationView"];
export type OnboardingApplicationRequest = Schemas["OnboardingApplicationRequest"];
export type ProviderProfileView = Schemas["ProviderProfileView"];
export type ProviderProfileRequest = Schemas["ProviderProfileRequest"];
export type BusinessHour = Schemas["BusinessHour"];
export type BusinessHoursRequest = Schemas["BusinessHoursRequest"];
export type ProviderQualificationView = Schemas["ProviderQualificationView"];
export type ProviderQualificationRequest = Schemas["ProviderQualificationRequest"];
export type ProviderQualificationsRequest = Schemas["ProviderQualificationsRequest"];
export type ReviewLogView = Schemas["ReviewLogView"];
export type ServiceCategoryView = Schemas["ServiceCategoryView"];
export type ServiceItemView = Schemas["ServiceItemView"];
export type CatalogItemProposalSummary = Schemas["CatalogItemProposalSummary"];
export type CatalogItemProposalView = Schemas["CatalogItemProposalView"];
export type CatalogItemProposalRequest = Schemas["CatalogItemProposalRequest"];
export type ProviderServiceView = Schemas["ProviderServiceView"];
export type ProviderServiceRequest = Schemas["ProviderServiceRequest"];
export type ProviderServiceStatusRequest = Schemas["ProviderServiceStatusRequest"];
export type OrderSummaryView = Schemas["ProviderOrderSummaryView"];
export type OrderView = Schemas["ProviderOrderView"];
export type OrderPhotoWallView = Schemas["OrderPhotoWallView"];
export type OrderPhotoSlotRequest = Schemas["OrderPhotoSlotRequest"];
export type OrderPhotoSlotView = Schemas["OrderPhotoSlotView"];
export type OrderReportRequest = Schemas["OrderReportRequest"];
export type FilePresignRequest = Schemas["FilePresignRequest"];
export type FilePresignView = Schemas["FilePresignView"];
export type FileView = Schemas["FileView"];
export type ProviderOrderCancelRequest = Schemas["ProviderOrderCancelRequest"];
export type OrderCancelRejectRequest = Schemas["OrderCancelRejectRequest"];
export type CouponTemplateView = Schemas["CouponTemplateView"];
export type CouponContributionView = Schemas["CouponContributionView"];
export type CouponContributionRequest = Schemas["CouponContributionRequest"];
export type CouponContributionLogView = Schemas["CouponContributionLogView"];
export type CouponView = Schemas["CouponView"];
export type AssessmentSummaryView = Schemas["AssessmentSummaryView"];
export type AssessmentView = Schemas["AssessmentView"];
export type AssessmentItemView = Schemas["AssessmentItemView"];
export type AssessmentOverrideView = Schemas["AssessmentOverrideView"];

/**
 * 分页结构：信封用契约生成的 `PageResult`，只把 `list` 的元素类型收窄
 * ——**不手写整个分页类型**（AGENTS.md：前端不手写接口类型）。
 */
export type Paged<T> = Omit<Schemas["PageResult"], "list"> & { list: T[] };

/**
 * 把「一定有」的几个字段收窄。
 *
 * 为什么需要：契约的 `components/schemas` 里多数 schema 没写 `required`，生成物于是把字段
 * 全标成可选（`id?: number`）。但列表接口返回的每一行**必然**带 id 与状态（服务端是从库里查出来的），
 * 于是每个调用点都要写一遍 `row.id ?? 0`——那种兜底会把「真没有」和「有但是 0」混成一回事。
 *
 * 收窄的只是**有没有**，不是**什么形状**：字段仍然全部来自生成物，写错名字编译期就红。
 */
export type Row<T, K extends keyof T> = T & Required<Pick<T, K>>;

export type OnboardingRow = Row<OnboardingApplicationSummary, "id" | "status" | "submitted_at">;
export type ProposalRow = Row<CatalogItemProposalSummary, "id" | "status" | "submitted_at">;
export type ServiceRow = Row<ProviderServiceView, "id" | "service_code" | "price" | "status" | "updated_at">;
/** 订单行的必要字段：列表里的每一行都来自库表，订单号与状态一定在（同上，收窄的只是「有没有」） */
export type OrderRow = Row<OrderSummaryView, "id" | "order_no" | "status">;
export type ContributionRow = Row<CouponContributionView, "id" | "template_id" | "total_count" | "status">;
export type TemplateRow = Row<CouponTemplateView, "id" | "code" | "name" | "status">;
export type CouponRow = Row<CouponView, "id" | "code" | "status">;
export type AssessmentRow = Row<AssessmentSummaryView, "id" | "period" | "total_score" | "level">;

const BASE = "/api/v1/provider";

export const providerApp = {
  // ---- 入驻申请（切片 #104，决策见 ADR-0035 的落地口径）----
  // 列表里就带 reject_reason：服务者最关心「卡在哪」，不必点进详情才看到原因
  listApplications(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<OnboardingRow>> {
    return providerHttp.get<Paged<OnboardingRow>>(`${BASE}/onboarding/applications`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  submitApplication(body: OnboardingApplicationRequest): Promise<OnboardingApplicationView> {
    return providerHttp.post<OnboardingApplicationView>(`${BASE}/onboarding/applications`, body);
  },
  getApplication(applicationId: number, signal?: AbortSignal): Promise<OnboardingApplicationView> {
    return providerHttp.get<OnboardingApplicationView>(`${BASE}/onboarding/applications/${applicationId}`, undefined, { signal });
  },
  /** 驳回后修改重提：**同一份申请单**改完回到待审核（不新建服务者记录）。 */
  resubmitApplication(applicationId: number, body: OnboardingApplicationRequest): Promise<OnboardingApplicationView> {
    return providerHttp.put<OnboardingApplicationView>(`${BASE}/onboarding/applications/${applicationId}`, body);
  },

  // ---- 门店信息 / 营业时间 / 资质（切片 #104）----
  // 读不设门禁（待审核 / 驳回 / 冻结都能读自己的门店），写要求服务者状态为「正常」
  getProfile(signal?: AbortSignal): Promise<ProviderProfileView> {
    return providerHttp.get<ProviderProfileView>(`${BASE}/profile`, undefined, { signal });
  },
  updateProfile(body: ProviderProfileRequest): Promise<ProviderProfileView> {
    return providerHttp.put<ProviderProfileView>(`${BASE}/profile`, body);
  },
  /** 整体替换：数组里没有的星期几就是休息，空数组表示整周休息（**不是**「不改」）。 */
  updateBusinessHours(body: BusinessHoursRequest): Promise<ProviderProfileView> {
    return providerHttp.put<ProviderProfileView>(`${BASE}/profile/business-hours`, body);
  },
  /** 补交 / 更新资质材料（整体替换）：新材料回到「待审核」，补交即可恢复上架。 */
  updateQualifications(body: ProviderQualificationsRequest): Promise<ProviderProfileView> {
    return providerHttp.put<ProviderProfileView>(`${BASE}/profile/qualifications`, body);
  },

  // ---- 标准目录（只读：停用的分类与项目对服务者等于不存在）----
  listCategories(signal?: AbortSignal): Promise<ServiceCategoryView[]> {
    return providerHttp.get<ServiceCategoryView[]>(`${BASE}/catalog/categories`, undefined, { signal });
  },
  listCatalogItems(
    params?: { categoryCode?: string; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ServiceItemView>> {
    return providerHttp.get<Paged<ServiceItemView>>(`${BASE}/catalog/items`, {
      category_code: params?.categoryCode,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- 目录外服务提案（服务者只能提案，不能自由建项）----
  listProposals(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ProposalRow>> {
    return providerHttp.get<Paged<ProposalRow>>(`${BASE}/catalog/item-requests`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  submitProposal(body: CatalogItemProposalRequest): Promise<CatalogItemProposalView> {
    return providerHttp.post<CatalogItemProposalView>(`${BASE}/catalog/item-requests`, body);
  },
  getProposal(requestId: number, signal?: AbortSignal): Promise<CatalogItemProposalView> {
    return providerHttp.get<CatalogItemProposalView>(`${BASE}/catalog/item-requests/${requestId}`, undefined, { signal });
  },

  // ---- 选品定价与上架（切片 #105）----
  // 列表不默认过滤状态：服务者要看得见自己的全部动作（含待审核与已驳回）
  listServices(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ServiceRow>> {
    return providerHttp.get<Paged<ServiceRow>>(`${BASE}/services`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 勾选目录项并定价 → 进待审核。价格越界由后端回 90001，message 就是给人看的那句。 */
  createService(body: ProviderServiceRequest): Promise<ProviderServiceView> {
    return providerHttp.post<ProviderServiceView>(`${BASE}/services`, body);
  },
  /** 改价 → 回到待审核；已上架的会先从前台撤下。 */
  updateServicePrice(serviceId: number, price: string): Promise<ProviderServiceView> {
    return providerHttp.put<ProviderServiceView>(`${BASE}/services/${serviceId}`, { price });
  },
  /** 上架 1 / 下架 2。0（待审核）与 3（已驳回）是审核流程的结果，服务者设不了。 */
  updateServiceStatus(serviceId: number, status: ProviderServiceStatusRequest["status"]): Promise<ProviderServiceView> {
    return providerHttp.put<ProviderServiceView>(`${BASE}/services/${serviceId}/status`, { status });
  },

  // ---- 订单与履约（切片 #77 / #107 / #109；状态机见 ADR-0038 第一节 / ADR-0048）----
  //
  // 这一组的写操作（接单 / 核销 / 取消 / 同意 / 拒绝 / 报工）都带 `Idempotency-Key`（ADR-0028）：
  // 它们全是状态迁移，键在**一次调用**里生成——「响应丢了再点一次」会拿到 40900（状态已推进），
  // 界面上是提示不精确，但服务端不会写两次。要更精确得把键提到「打开表单时生成」那一层，
  // 与 C 端下单的已知差距是同一形状（见 shared 的 `idempotency.ts`），不在这里单独造一套。

  /**
   * 本店订单列表（分页 + 筛选 + 核销定位）。
   *
   * `keyword` 是核销的入口（ADR-0038 第二节）：门店**不需要扫码枪**，输入核销码 / 手机号 /
   * 订单号都能定位订单。**核销码不回给服务者**——门店凭用户出示的码定位订单，不需要知道码本身。
   */
  listOrders(
    params?: { status?: number; appointmentDate?: string; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<OrderRow>> {
    return providerHttp.get<Paged<OrderRow>>(`${BASE}/orders`, {
      status: params?.status,
      appointment_date: params?.appointmentDate,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 订单详情：含券实例、照片墙、取消申请与状态迁移的时间点（核销码不在里面）。 */
  getOrder(orderId: number, signal?: AbortSignal): Promise<OrderView> {
    return providerHttp.get<OrderView>(`${BASE}/orders/${orderId}`, undefined, { signal });
  },
  /** 接单（待接单 → 已预约）：**接单即承诺**，之后用户再取消需要门店同意。别的状态一律 40900。 */
  acceptOrder(orderId: number): Promise<OrderView> {
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/accept`, undefined, { idempotencyKey: newIdempotencyKey() });
  },
  /**
   * 核销（已预约 → 履约中；本单锁定的券同时转已核销）。
   *
   * 契约里**没有请求体**：核销码是「定位订单」用的（见 {@link listOrders} 的 keyword），
   * 不是随核销提交的字段——码不回给门店，这条是刻意的。重复核销 40900。
   */
  redeemOrder(orderId: number): Promise<OrderView> {
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/redeem`, undefined, { idempotencyKey: newIdempotencyKey() });
  },
  /** 门店取消（`reason` 必填，会展示给用户并计入考核 —— ADR-0049 §七）。履约中 / 终态 → 40900。 */
  cancelOrder(orderId: number, reason: string): Promise<OrderView> {
    const body: ProviderOrderCancelRequest = { reason };
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/cancel`, body, { idempotencyKey: newIdempotencyKey() });
  },
  /** 同意用户的取消申请（已预约阶段的取消需要门店同意）。没有待处理申请 → 40900。 */
  approveOrderCancel(orderId: number): Promise<OrderView> {
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/cancel/approve`, undefined, { idempotencyKey: newIdempotencyKey() });
  },
  /** 拒绝用户的取消申请：**理由必填**（ADR-0038 第一节点名要求），订单留在「已预约」。 */
  rejectOrderCancel(orderId: number, reason: string): Promise<OrderView> {
    const body: OrderCancelRejectRequest = { reason };
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/cancel/reject`, body, { idempotencyKey: newIdempotencyKey() });
  },
  /** 保存一个照片槽位（整体替换）。**只在「履约中」可写**；照片要先直传文件域拿到 file_id。 */
  saveOrderPhotoSlot(orderId: number, slot: 1 | 2 | 3, body: OrderPhotoSlotRequest): Promise<OrderPhotoWallView> {
    return providerHttp.put<OrderPhotoWallView>(`${BASE}/orders/${orderId}/photo-slots/${slot}`, body, { idempotencyKey: newIdempotencyKey() });
  },
  /** 报工（履约中 → 已完成）。三道照片墙缺一道由**服务端**拒绝（40900，message 里点名缺哪道）。 */
  reportOrder(orderId: number, body: OrderReportRequest): Promise<OrderView> {
    return providerHttp.post<OrderView>(`${BASE}/orders/${orderId}/report`, body, { idempotencyKey: newIdempotencyKey() });
  },

  // ---- 文件（切片 #107 的服务留痕照片；协议见 ADR-0020）----
  //
  // 这一份是**服务者侧**的 presign：`/api/v1/app/files/**` 只认 C 端令牌（JwtAuthenticationFilter
  // 按路径前缀判登录域，ADR-0012），门店的令牌打过去等于没登录。契约里本端的 `biz_type`
  // **只收 care**（服务留痕），别的一律 40001——所以类型上也只有一个取值可用。
  /** 申请服务留痕照片的上传凭证（一次最多 9 张）。字节直传到返回的 `upload_url`，不经业务接口。 */
  presignCarePhotos(body: FilePresignRequest): Promise<FilePresignView[]> {
    return providerHttp.post<FilePresignView[]>(`${BASE}/files/presign`, body);
  },

  // ---- 券池的服务者侧（切片 #110；决策见 ADR-0037 第三节 / ADR-0044）----
  //
  // **服务者不能自建券**：这里只有「从池子里挑一个模板并承诺我店愿意接多少张」。
  // 券的钱语义：券只是到店抵扣凭证，任何地方都不出现资金流 / 结算 / 打款（ADR-0036）。

  /** 券池里可以贡献的券模板（**只列服务者成本券**：平台补贴券不需要也不允许服务者承诺额度）。 */
  listCouponTemplates(
    params?: { keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<TemplateRow>> {
    return providerHttp.get<Paged<TemplateRow>>(`${BASE}/coupon-pool/templates`, {
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 我的券贡献（每行一条额度账：承诺 / 已发放 / 已核销 / 占用中 / 已过期 / 还可发放）。 */
  listCouponContributions(
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ContributionRow>> {
    return providerHttp.get<Paged<ContributionRow>>(`${BASE}/coupon-contributions`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 从券池选券并承诺可核销额度（**承诺不是发放**；同一模板重复贡献 → 40900，调整要用 PUT）。 */
  createCouponContribution(body: CouponContributionRequest): Promise<CouponContributionView> {
    return providerHttp.post<CouponContributionView>(`${BASE}/coupon-contributions`, body, { idempotencyKey: newIdempotencyKey() });
  },
  /** 券贡献详情：比列表多出 `remark` 与 `logs`（额度流水，append-only —— 额度怎么变的查得出来）。 */
  getCouponContribution(contributionId: number, signal?: AbortSignal): Promise<CouponContributionView> {
    return providerHttp.get<CouponContributionView>(`${BASE}/coupon-contributions/${contributionId}`, undefined, { signal });
  },
  /** 调整承诺额度（`total_count` 不得低于已核销 + 占用中）；`status=1` 可让已停止的那条重新生效。 */
  updateCouponContribution(contributionId: number, body: CouponContributionRequest): Promise<CouponContributionView> {
    return providerHttp.put<CouponContributionView>(`${BASE}/coupon-contributions/${contributionId}`, body, { idempotencyKey: newIdempotencyKey() });
  },
  /** 撤回未发放的额度：只收回还没发出去的那部分（用户的券不受影响），状态转「已停止发放」。 */
  withdrawCouponContribution(contributionId: number): Promise<CouponContributionView> {
    return providerHttp.delete<CouponContributionView>(`${BASE}/coupon-contributions/${contributionId}`, { idempotencyKey: newIdempotencyKey() });
  },
  /** 本店贡献券的发放与核销明细 = 服务者的「对账视图」（ADR-0036 之后没有余额与提现）。 */
  listContributionCoupons(
    contributionId: number,
    params?: { status?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<CouponRow>> {
    return providerHttp.get<Paged<CouponRow>>(`${BASE}/coupon-contributions/${contributionId}/coupons`, {
      status: params?.status,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- 月度考核（F022；决策见 ADR-0039 第三节 / ADR-0050 第四节 / ADR-0052）----
  //
  // 总分 = 拉新 40% + 券 40% + 过程 20%（权重在运营后台配置）。**服务者只看自己的**，
  // 别人的账期一律 40400。规则配置没有服务者侧接口——界面上那段规则说明是**文案**，
  // 不是从某个 `/assessments/rules` 读来的（那个接口不存在，别照着猜）。

  /** 我的月度考核列表（按账期倒序；还没算到的账期不出现）。 */
  listAssessments(
    params?: { period?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<AssessmentRow>> {
    return providerHttp.get<Paged<AssessmentRow>>(`${BASE}/assessments`, {
      period: params?.period,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  /** 某月考核明细：三项 + 过程子项（未参与的也在）+ 覆盖留痕（服务者可见，否则无法申诉）。 */
  getAssessment(period: string, signal?: AbortSignal): Promise<AssessmentView> {
    return providerHttp.get<AssessmentView>(`${BASE}/assessments/${period}`, undefined, { signal });
  },
};

/**
 * 「相机拍到电脑 → 一次选多张 → 直传」的一条龙（ADR-0020 的形态，与 C 端 `uploadFiles` 同构）。
 *
 * <p>与 C 端那份的区别只有一处：**回传的是 `file_id` 而不是文件列表**——服务者侧没有
 * 「我的文件」列表接口（契约里只开了 presign），而照片墙要的就是 id：挂到槽位上之后，
 * 签名读地址由订单详情的 `photo_wall.slots[].photos[].thumb_url` 给出来。
 *
 * <p>一次申请凭证、逐个直传，返回的顺序与选中的文件一致。上传没有「部分成功」这种交付：
 * 任何一个字节传失败，调用方（照片墙面板）就把这一批整体当作没上传——槽位里不会挂上半个集合。
 * **代价**：已经落定的那几个文件会留在文件域（本端没有删除接口，见面板的说明），
 * 重选一次即可把它们换掉，但它们不会被回收。
 */
export async function uploadCarePhotos(
  petId: number,
  files: File[],
  onProgress?: (done: number, total: number) => void,
): Promise<number[]> {
  const presigned = await providerApp.presignCarePhotos({
    biz_type: "care",
    pet_id: petId,
    items: files.map((file) => ({
      mime: file.type === "image/png" ? "image/png" : "image/jpeg",
      size_bytes: file.size,
      role: "original",
    })),
  });

  const fileIds: number[] = [];
  for (const [index, file] of files.entries()) {
    const target = presigned[index];
    if (!target) break;
    await providerHttp.putRaw(target.upload_url, file);
    fileIds.push(target.file_id);
    onProgress?.(fileIds.length, files.length);
  }
  return fileIds;
}
