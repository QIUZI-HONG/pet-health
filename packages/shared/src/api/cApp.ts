/**
 * C 端接口调用。类型全部来自 `contract/app.yaml` 的生成物——**不要手写接口类型**（ADR-0005）。
 *
 * 这一层只做两件事：拼路径、给返回类型。错误处理、鉴权、刷新都在 `http/client.ts` 里。
 */
import type { components } from "./app";
import { http } from "../http/client";

type Schemas = components["schemas"];

export type TokenPair = Schemas["TokenPair"];
export type UserProfile = Schemas["UserProfile"];
export type Pet = Schemas["Pet"];
export type RegisterRequest = Schemas["RegisterRequest"];
export type LoginRequest = Schemas["LoginRequest"];
export type UpdateProfileRequest = Schemas["UpdateProfileRequest"];
export type PetCreateRequest = Schemas["PetCreateRequest"];
export type PetUpdateRequest = Schemas["PetUpdateRequest"];
export type CheckInDay = Schemas["CheckInDay"];
export type CheckInItem = Schemas["CheckInItem"];
export type CheckInItemInput = Schemas["CheckInItemInput"];
export type CheckInSubmitRequest = Schemas["CheckInSubmitRequest"];
export type CheckInStreak = Schemas["CheckInStreak"];
export type HealthScore = Schemas["HealthScore"];
export type HealthScoreDimension = Schemas["HealthScoreDimension"];
export type MessageView = Schemas["MessageView"];
/**
 * 消息列表的分页结构：信封用契约生成的 `PageResult`，只有 `list` 的元素类型在契约里是 unknown，
 * 这里收窄成 `MessageView`——**不手写整个分页类型**（AGENTS.md：前端不手写接口类型）。
 */
export type MessagePage = Omit<Schemas["PageResult"], "list"> & { list: MessageView[] };
export type ReminderSetting = Schemas["ReminderSetting"];
export type EpidemicRecord = Schemas["EpidemicRecord"];
export type EpidemicRecordInput = Schemas["EpidemicRecordInput"];
export type AccountExportView = Schemas["AccountExportView"];
export type ComplianceDocumentView = Schemas["ComplianceDocumentView"];
export type AiConsultRequest = Schemas["AiConsultRequest"];
export type AiConsultView = Schemas["AiConsultView"];
export type FilePresignRequest = Schemas["FilePresignRequest"];
export type FilePresignView = Schemas["FilePresignView"];
export type FileView = Schemas["FileView"];

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
  listPets(includeDeleted = false, signal?: AbortSignal): Promise<Pet[]> {
    return http.get<Pet[]>(`${BASE}/pets`, includeDeleted ? { deleted: true } : undefined, { signal });
  },
  createPet(body: PetCreateRequest): Promise<Pet> {
    return http.post<Pet>(`${BASE}/pets`, body);
  },
  getPet(petId: number): Promise<Pet> {
    return http.get<Pet>(`${BASE}/pets/${petId}`);
  },
  updatePet(petId: number, body: PetUpdateRequest): Promise<Pet> {
    return http.put<Pet>(`${BASE}/pets/${petId}`, body);
  },
  deletePet(petId: number): Promise<void> {
    return http.delete<void>(`${BASE}/pets/${petId}`);
  },
  restorePet(petId: number): Promise<Pet> {
    return http.post<Pet>(`${BASE}/pets/${petId}/restore`);
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
  getHealthScore(petId: number, signal?: AbortSignal): Promise<HealthScore> {
    return http.get<HealthScore>(`${BASE}/pets/${petId}/health-score`, undefined, { signal });
  },

  // ---- 防疫记录（切片 #99：疫苗/驱虫日期，疫苗提醒与评分「防疫」维度的数据源）----
  listEpidemicRecords(petId: number, signal?: AbortSignal): Promise<EpidemicRecord[]> {
    return http.get<EpidemicRecord[]>(`${BASE}/pets/${petId}/epidemic-records`, undefined, { signal });
  },
  createEpidemicRecord(petId: number, body: EpidemicRecordInput): Promise<EpidemicRecord> {
    return http.post<EpidemicRecord>(`${BASE}/pets/${petId}/epidemic-records`, body);
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
  listReminderSettings(signal?: AbortSignal): Promise<ReminderSetting[]> {
    return http.get<ReminderSetting[]>(`${BASE}/messages/settings`, undefined, { signal });
  },
  updateReminderSetting(type: number, enabled: boolean): Promise<ReminderSetting[]> {
    return http.put<ReminderSetting[]>(`${BASE}/messages/settings`, { type, enabled });
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
