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
  listPets(includeDeleted = false): Promise<Pet[]> {
    return http.get<Pet[]>(`${BASE}/pets`, includeDeleted ? { deleted: true } : undefined);
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
  getCheckInDay(petId: number, date?: string): Promise<CheckInDay> {
    return http.get<CheckInDay>(`${BASE}/pets/${petId}/check-ins`, date ? { date } : undefined);
  },
  submitCheckIn(petId: number, body: CheckInSubmitRequest): Promise<CheckInDay> {
    return http.post<CheckInDay>(`${BASE}/pets/${petId}/check-ins`, body);
  },
  undoCheckIn(petId: number, date: string, category: number): Promise<CheckInDay> {
    return http.delete<CheckInDay>(`${BASE}/pets/${petId}/check-ins/item?date=${date}&category=${category}`);
  },
  getCheckInStreak(petId: number): Promise<CheckInStreak> {
    return http.get<CheckInStreak>(`${BASE}/pets/${petId}/check-ins/streak`);
  },

  // ---- 健康评分（切片 #97，算法见 ADR-0018）----
  getHealthScore(petId: number): Promise<HealthScore> {
    return http.get<HealthScore>(`${BASE}/pets/${petId}/health-score`);
  },
};
