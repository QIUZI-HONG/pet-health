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
};
