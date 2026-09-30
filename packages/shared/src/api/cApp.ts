/**
 * C 端接口调用。类型全部来自 `contract/app.yaml` 的生成物——**不要手写接口类型**（ADR-0005）。
 *
 * 这一层只做两件事：拼路径、给返回类型。错误处理、鉴权、刷新都在 `http/client.ts` 里。
 *
 * **它刻意与契约一一对应**：契约 `app.yaml` 里已实现的接口在这里都有方法，哪怕当前 UI 还没用到
 * （例如 `getPet`、`listComplianceDocuments`）。所以「某个方法没有调用点」不等于死代码——判断依据是
 * 「契约里还有没有这个接口」。反过来，契约里删了接口，这里的方法要一起删。
 */
import type { components } from "./app";
import { http } from "../http/client";

type Schemas = components["schemas"];

export type TokenPair = Schemas["TokenPair"];
export type UserProfile = Schemas["UserProfile"];
export type PetView = Schemas["PetView"];
export type RegisterRequest = Schemas["RegisterRequest"];
export type LoginRequest = Schemas["LoginRequest"];
export type UpdateProfileRequest = Schemas["UpdateProfileRequest"];
export type PetCreateRequest = Schemas["PetCreateRequest"];
export type PetUpdateRequest = Schemas["PetUpdateRequest"];
export type CheckInDay = Schemas["CheckInDay"];
export type CheckInItem = Schemas["CheckInItem"];
export type CheckInItemRequest = Schemas["CheckInItemRequest"];
export type CheckInSubmitRequest = Schemas["CheckInSubmitRequest"];
export type CheckInStreak = Schemas["CheckInStreak"];
export type HealthScoreView = Schemas["HealthScoreView"];
export type HealthScoreDimension = Schemas["HealthScoreDimension"];
export type MessageView = Schemas["MessageView"];
/**
 * 分页结构：信封用契约生成的 `PageResult`，只把 `list` 的元素类型收窄
 * ——**不手写整个分页类型**（AGENTS.md：前端不手写接口类型）。
 */
export type Paged<T> = Omit<Schemas["PageResult"], "list"> & { list: T[] };
export type MessagePage = Paged<MessageView>;
export type ReminderSettingView = Schemas["ReminderSettingView"];
export type EpidemicRecordView = Schemas["EpidemicRecordView"];
export type EpidemicRecordRequest = Schemas["EpidemicRecordRequest"];
export type AccountExportView = Schemas["AccountExportView"];
export type ComplianceDocumentView = Schemas["ComplianceDocumentView"];
export type AiConsultRequest = Schemas["AiConsultRequest"];
export type AiConsultView = Schemas["AiConsultView"];
export type HumanConsultView = Schemas["HumanConsultView"];
export type FilePresignRequest = Schemas["FilePresignRequest"];
export type FilePresignView = Schemas["FilePresignView"];
export type FileView = Schemas["FileView"];
export type ArchiveSectionView = Schemas["ArchiveSectionView"];
export type ArchiveRecordView = Schemas["ArchiveRecordView"];
export type ArchiveRecordRequest = Schemas["ArchiveRecordRequest"];
export type TimelineEventView = Schemas["TimelineEventView"];
export type CareModeView = Schemas["CareModeView"];
export type HealthReportView = Schemas["HealthReportView"];
export type HealthReportPayload = Schemas["HealthReportPayload"];
export type HealthReportStats = Schemas["HealthReportStats"];
export type KnowledgeEntryView = Schemas["KnowledgeEntryView"];

const BASE = "/api/v1/app";

export const cApp = {
  // ---- 认证 ----
  register(body: RegisterRequest): Promise<TokenPair> {
    return http.post<TokenPair>(`${BASE}/auth/register`, body);
  },
  login(body: LoginRequest): Promise<TokenPair> {
    return http.post<TokenPair>(`${BASE}/auth/login`, body);
  },
  logout(refreshToken: string): Promise<void> {
    return http.post<void>(`${BASE}/auth/logout`, { refresh_token: refreshToken });
  },

  // ---- 当前用户 ----
  me(): Promise<UserProfile> {
    return http.get<UserProfile>(`${BASE}/users/me`);
  },
  updateMe(body: UpdateProfileRequest): Promise<UserProfile> {
    return http.put<UserProfile>(`${BASE}/users/me`, body);
  },
  activatePet(petId: number): Promise<UserProfile> {
    return http.put<UserProfile>(`${BASE}/users/me/active-pet`, { pet_id: petId });
  },

  // ---- 宠物档案 ----
  listPets(includeDeleted = false, signal?: AbortSignal): Promise<PetView[]> {
    return http.get<PetView[]>(`${BASE}/pets`, includeDeleted ? { deleted: true } : undefined, { signal });
  },
  createPet(body: PetCreateRequest): Promise<PetView> {
    return http.post<PetView>(`${BASE}/pets`, body);
  },
  getPet(petId: number): Promise<PetView> {
    return http.get<PetView>(`${BASE}/pets/${petId}`);
  },
  updatePet(petId: number, body: PetUpdateRequest): Promise<PetView> {
    return http.put<PetView>(`${BASE}/pets/${petId}`, body);
  },
  deletePet(petId: number): Promise<void> {
    return http.delete<void>(`${BASE}/pets/${petId}`);
  },
  restorePet(petId: number): Promise<PetView> {
    return http.post<PetView>(`${BASE}/pets/${petId}/restore`);
  },

  // ---- 打卡（切片 #97，规则见 ADR-0018）----
  // date 是**业务日期**：省略=今天（当场录入），传过去 7 天内=补录。
  getCheckInDay(petId: number, date?: string, signal?: AbortSignal): Promise<CheckInDay> {
    return http.get<CheckInDay>(`${BASE}/pets/${petId}/check-ins`, date ? { date } : undefined, { signal });
  },
  submitCheckIn(petId: number, body: CheckInSubmitRequest): Promise<CheckInDay> {
    return http.post<CheckInDay>(`${BASE}/pets/${petId}/check-ins`, body);
  },
  undoCheckIn(petId: number, date: string, category: number): Promise<CheckInDay> {
    return http.delete<CheckInDay>(`${BASE}/pets/${petId}/check-ins/item?date=${date}&category=${category}`);
  },
  getCheckInStreak(petId: number, signal?: AbortSignal): Promise<CheckInStreak> {
    return http.get<CheckInStreak>(`${BASE}/pets/${petId}/check-ins/streak`, undefined, { signal });
  },

  // ---- 健康评分（切片 #97，算法见 ADR-0018）----
  getHealthScore(petId: number, signal?: AbortSignal): Promise<HealthScoreView> {
    return http.get<HealthScoreView>(`${BASE}/pets/${petId}/health-score`, undefined, { signal });
  },

  // ---- 防疫记录（切片 #99：疫苗/驱虫日期，疫苗提醒与评分「防疫」维度的数据源）----
  listEpidemicRecords(petId: number, signal?: AbortSignal): Promise<EpidemicRecordView[]> {
    return http.get<EpidemicRecordView[]>(`${BASE}/pets/${petId}/epidemic-records`, undefined, { signal });
  },
  createEpidemicRecord(petId: number, body: EpidemicRecordRequest): Promise<EpidemicRecordView> {
    return http.post<EpidemicRecordView>(`${BASE}/pets/${petId}/epidemic-records`, body);
  },
  deleteEpidemicRecord(petId: number, recordId: number): Promise<void> {
    return http.delete<void>(`${BASE}/pets/${petId}/epidemic-records/${recordId}`);
  },

  // ---- 消息中心（切片 #99，决策见 ADR-0019）----
  // 读取会惰性补算一次提醒，所以这几个接口拿到的一定是最新的
  listMessages(params?: { kind?: number; unreadOnly?: boolean; page?: number; pageSize?: number },
               signal?: AbortSignal):
    Promise<MessagePage> {
    return http.get<MessagePage>(`${BASE}/messages`, {
      kind: params?.kind,
      unread_only: params?.unreadOnly,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  getMessageHighlights(limit = 6, signal?: AbortSignal): Promise<MessageView[]> {
    return http.get<MessageView[]>(`${BASE}/messages/highlights`, { limit }, { signal });
  },
  getUnreadCount(): Promise<{ unread: number; unread_reminders: number }> {
    return http.get(`${BASE}/messages/unread-count`);
  },
  markMessageRead(messageId: number): Promise<MessageView> {
    return http.put<MessageView>(`${BASE}/messages/${messageId}/read`);
  },
  deleteMessage(messageId: number): Promise<void> {
    return http.delete<void>(`${BASE}/messages/${messageId}`);
  },
  markAllMessagesRead(): Promise<{ unread: number }> {
    return http.put<{ unread: number }>(`${BASE}/messages/read-all`);
  },
  listReminderSettings(signal?: AbortSignal): Promise<ReminderSettingView[]> {
    return http.get<ReminderSettingView[]>(`${BASE}/messages/settings`, undefined, { signal });
  },
  updateReminderSetting(type: number, enabled: boolean): Promise<ReminderSettingView[]> {
    return http.put<ReminderSettingView[]>(`${BASE}/messages/settings`, { type, enabled });
  },

  // ---- 合规（切片 #74，决策见 ADR-0025）----
  // 文档正文由运营/法务在库里维护；`is_placeholder` 为 true 时界面必须显示「待法务定稿」，
  // 不能把占位文字当生效条款展示。
  listComplianceDocuments(): Promise<ComplianceDocumentView[]> {
    return http.get<ComplianceDocumentView[]>(`${BASE}/compliance/documents`);
  },
  getComplianceDocument(code: string): Promise<ComplianceDocumentView> {
    return http.get<ComplianceDocumentView>(`${BASE}/compliance/documents/${code}`);
  },
  exportAccount(): Promise<AccountExportView> {
    return http.get<AccountExportView>(`${BASE}/users/me/export`);
  },
  // 注销：接口幂等，**确认是界面的事**（调用方必须先让用户确认一次）
  deactivateAccount(): Promise<void> {
    return http.post<void>(`${BASE}/users/me/deactivation`);
  },

  // ---- AI 咨询（切片 #98，决策见 ADR-0021/0024）----
  // 一次咨询 = 一次请求。红线命中时后端不经模型（结果里 red_flag_hits 非空）；
  // 到量只是提示，接口照常返回 —— 见 remaining_today 的说明。
  consultAi(petId: number, body: AiConsultRequest, signal?: AbortSignal): Promise<AiConsultView> {
    return http.post<AiConsultView>(`${BASE}/pets/${petId}/ai-consults`, body, { signal });
  },

  /**
   * 转人工：把这次咨询交给平台人工跟进（F006 的出口）。
   *
   * **幂等**：一次咨询只能转一次，重复调用返回同一条工单（后端在 `consult_id` 上有唯一键）。
   * 所以连点两下、或网络抖动后重试，都不会给用户造出两张单子。
   */
  transferToHuman(petId: number, consultId: number): Promise<HumanConsultView> {
    return http.post<HumanConsultView>(`${BASE}/pets/${petId}/ai-consults/${consultId}/transfer`, {});
  },

  // ---- 档案分项与时间轴（切片 #102，规则见 ADR-0030）----
  // 分项是原始记录；写接口只收「列表语义」的四个分项，体重/排泄/防疫各有自己的入口。
  listArchiveSections(petId: number, signal?: AbortSignal): Promise<ArchiveSectionView[]> {
    return http.get<ArchiveSectionView[]>(`${BASE}/pets/${petId}/archive-sections`, undefined, { signal });
  },
  listArchiveRecords(
    petId: number,
    params?: { section?: string; from?: string; to?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<ArchiveRecordView>> {
    return http.get<Paged<ArchiveRecordView>>(`${BASE}/pets/${petId}/archive-records`, {
      section: params?.section,
      from: params?.from,
      to: params?.to,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  createArchiveRecord(petId: number, body: ArchiveRecordRequest): Promise<ArchiveRecordView> {
    return http.post<ArchiveRecordView>(`${BASE}/pets/${petId}/archive-records`, body);
  },
  updateArchiveRecord(petId: number, recordId: number, body: ArchiveRecordRequest): Promise<ArchiveRecordView> {
    return http.put<ArchiveRecordView>(`${BASE}/pets/${petId}/archive-records/${recordId}`, body);
  },
  deleteArchiveRecord(petId: number, recordId: number): Promise<void> {
    return http.delete<void>(`${BASE}/pets/${petId}/archive-records/${recordId}`);
  },
  // 时间轴只收四类事件（就医 / 疫苗驱虫 / 异常打卡 / 服务者报工），正常打卡不在里面
  listTimeline(
    petId: number,
    params?: { type?: string; from?: string; to?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<TimelineEventView>> {
    return http.get<Paged<TimelineEventView>>(`${BASE}/pets/${petId}/timeline`, {
      type: params?.type,
      from: params?.from,
      to: params?.to,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- 健康报告（切片 #115，决策见 ADR-0031）----
  // 读取会惰性生成当期报告（幂等）；报告由规则模板拼装，不经过模型
  listHealthReports(
    petId: number,
    params?: { type?: number; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<HealthReportView>> {
    return http.get<Paged<HealthReportView>>(`${BASE}/pets/${petId}/health-reports`, {
      type: params?.type,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },

  // ---- 专项照护模式（切片 #116，决策见 ADR-0032）----
  // 它是派生结果：GET 只读；PUT 用于「用户手动关闭 / 重新交还给自动判定」
  getCareMode(petId: number, signal?: AbortSignal): Promise<CareModeView> {
    return http.get<CareModeView>(`${BASE}/pets/${petId}/care-mode`, undefined, { signal });
  },
  setCareMode(petId: number, enabled: boolean): Promise<CareModeView> {
    return http.put<CareModeView>(`${BASE}/pets/${petId}/care-mode`, { enabled });
  },

  // ---- 知识库（F024 / F025，决策见 ADR-0033）----
  // 只读出口：列表不带正文（`body` 是空串）、详情才有；两条都**需要登录**。
  // `review_status` 是必填字段且**必须原样展示**——条目多数是待复核（ADR-0025 第二节），
  // 界面不许把未复核说成已复核（ADR-0033）。
  listKnowledgeEntries(
    params?: { categoryCode?: string; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<Paged<KnowledgeEntryView>> {
    return http.get<Paged<KnowledgeEntryView>>(`${BASE}/knowledge/entries`, {
      category_code: params?.categoryCode,
      keyword: params?.keyword,
      page: params?.page,
      page_size: params?.pageSize,
    }, { signal });
  },
  // 不可读的编号（不存在 / 已删除 / 复核状态不可读）一律 40400——与「看不到」同码
  getKnowledgeEntry(code: string, signal?: AbortSignal): Promise<KnowledgeEntryView> {
    return http.get<KnowledgeEntryView>(`${BASE}/knowledge/entries/${code}`, undefined, { signal });
  },

  // ---- 文件（切片 #95，决策见 ADR-0020）----
  // 上传分两步：先取凭证，再把字节直传到凭证给的地址（**不经业务接口**）。
  // 下载也不用这个客户端：`url` / `thumb_url` 是签名地址，直接塞给 `<img src>` 即可。
  presignFiles(body: FilePresignRequest): Promise<FilePresignView[]> {
    return http.post<FilePresignView[]>(`${BASE}/files/presign`, body);
  },
  listFiles(params?: { petId?: number; bizType?: string }): Promise<FileView[]> {
    return http.get<FileView[]>(`${BASE}/files`, {
      pet_id: params?.petId,
      biz_type: params?.bizType,
    });
  },
  deleteFile(fileId: number): Promise<void> {
    return http.delete<void>(`${BASE}/files/${fileId}`);
  },
};

/**
 * 「拍照 → 拷到电脑 → 批量上传」的一条龙（切片 #95 的验收标准）。
 *
 * <p>一次申请凭证、逐个直传。选中的文件里但凡有一个不合规（类型/体积），申请阶段就整体被拒——
 * 这是有意的：**批量上传时用户不想一个个试**，一次说清哪里不对。
 */
export async function uploadFiles(options: {
  petId?: number;
  /** 取值来自契约的枚举，不手写字符串——写错了编译期就红，而不是等到上传被拒（切片 #95） */
  bizType: NonNullable<FilePresignRequest["biz_type"]>;
  files: File[];
  onProgress?: (done: number, total: number) => void;
}): Promise<FileView[]> {
  const presigned = await cApp.presignFiles({
    biz_type: options.bizType,
    pet_id: options.petId,
    items: options.files.map((file) => ({
      mime: file.type === "image/png" ? "image/png" : "image/jpeg",
      size_bytes: file.size,
      role: "original",
    })),
  });

  let done = 0;
  for (const [index, file] of options.files.entries()) {
    const target = presigned[index];
    if (!target) break;
    await http.putRaw(target.upload_url, file);
    done += 1;
    options.onProgress?.(done, options.files.length);
  }

  return cApp.listFiles({ petId: options.petId, bizType: options.bizType });
}
