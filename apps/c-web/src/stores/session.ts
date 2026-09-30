/**
 * 会话状态：当前用户、宠物列表、当前宠物。
 *
 * 三件事在这里统一：
 *   - 启动时用本地令牌换一次 `/users/me`（令牌过期会被请求层静默刷新，ADR-0012）；
 *   - 「当前宠物」以服务端为准（`active_pet_id`），本地只做兜底——用户可能在另一台浏览器上切过；
 *   - **区分「身份失效」与「服务不可用」**：前者清会话跳登录，后者保留会话显示错误态。
 *
 * 最后这条是踩出来的：早先任何失败都清令牌，于是后端一重启，所有在线用户都被静默登出、
 * 看到的是「登录后查看」而不是「服务异常，请重试」。用户没法从「请登录」这个提示里
 * 知道其实是后端抖了。
 */
import { defineStore } from "pinia";
import { ApiError, cApp, tokenStore, type PetView, type TokenPair, type UserProfile } from "@pet-health/shared";

export type SessionStatus = "idle" | "loading" | "authenticated" | "anonymous" | "error";

interface SessionState {
  status: SessionStatus;
  /** 本地是否有令牌（不代表服务端认可）。用于区分「没登录」与「登录了但取不到数据」。 */
  hasSession: boolean;
  user: UserProfile | null;
  pets: PetView[];
  /** 启动失败的原因；用于错误态展示与重试。 */
  errorMessage: string;
  /** 后端返回的请求 ID，报障时直接给这个。 */
  errorRequestId: string;
}

/** 这几种失败说明「你的身份不行了」——只有它们该清会话。 */
const IDENTITY_ERRORS = new Set([40100, 40101, 40300]);

export const useSessionStore = defineStore("session", {
  state: (): SessionState => ({
    status: "idle",
    hasSession: false,
    user: null,
    pets: [],
    errorMessage: "",
    errorRequestId: "",
  }),

  getters: {
    /** 服务端认可当前身份（`/users/me` 取到了）。 */
    isLoggedIn: (state) => state.status === "authenticated",
    /** 当前宠物：服务端记的那只；它被删掉或没切换过时回退到列表第一只（契约里的约定）。 */
    activePet(state): PetView | null {
      if (state.pets.length === 0) return null;
      const active = state.pets.find((pet) => pet.id === state.user?.active_pet_id);
      return active ?? state.pets[0] ?? null;
    },
  },

  actions: {
    /** 应用启动时调一次。没有本地令牌就直接是匿名，不发请求。 */
    async bootstrap(): Promise<void> {
      if (!tokenStore.get()) {
        this.status = "anonymous";
        this.hasSession = false;
        return;
      }
      this.hasSession = true;
      await this.reload();
    },

    async reload(): Promise<void> {
      this.status = "loading";
      this.errorMessage = "";
      this.errorRequestId = "";
      // 「有令牌」不等于「服务端认可」，但只要令牌还在，用户就还没被登出——
      // 错误态要用这一点来决定显示「重试」还是「去登录」
      this.hasSession = tokenStore.get() !== null;
      try {
        const [user, pets] = await Promise.all([cApp.me(), cApp.listPets()]);
        this.user = user;
        this.pets = pets;
        this.hasSession = true;
        this.status = "authenticated";
      } catch (error) {
        const apiError = error instanceof ApiError ? error : null;
        if (apiError && IDENTITY_ERRORS.has(apiError.code)) {
          // 身份确实失效了：清掉本地令牌，回到未登录
          tokenStore.clear();
          this.user = null;
          this.pets = [];
          this.hasSession = false;
          this.status = "anonymous";
        } else {
          // 服务不可用 / 网络问题：**保住会话**，交给页面显示错误态与重试
          this.status = "error";
        }
        this.errorMessage = apiError?.message ?? "加载失败，请稍后重试";
        this.errorRequestId = apiError?.requestId ?? "";
      }
    },

    /**
     * 会话失效（请求层刷新也换不回来时广播过来）。
     *
     * 与 logout 的区别：logout 是用户主动退出（会通知服务端），这里是身份已经作废，
     * 只做清态——令牌在请求层已经清掉了，这里把界面状态收干净，别停在「看起来已登录」。
     */
    markSessionExpired(): void {
      this.user = null;
      this.pets = [];
      this.hasSession = false;
      this.status = "anonymous";
      this.errorMessage = "";
      this.errorRequestId = "";
    },

    /**
     * 把服务端返回的最新资料收进会话。
     *
     * <p>为什么不复用 {@link reload}：那条路径会连带重拉宠物列表（两次请求）。改昵称/头像
     * 只影响用户自己那份资料，而 `PUT /users/me` 的响应体**就是**更新后的 `UserProfile`
     * （契约里这么定的），直接收下即可——多打一次 `/users/me` 只是把服务端的同一份数据再取一遍。
     */
    applyProfile(user: UserProfile): void {
      this.user = user;
    },

    async login(phone: string, password: string): Promise<void> {      const tokens = await cApp.login({ phone, password });
      this.applyTokens(tokens);
      this.user = tokens.user ?? null;
      await this.reload();
    },

    async register(phone: string, password: string, nickname?: string): Promise<void> {
      const tokens = await cApp.register({ phone, password, nickname });
      this.applyTokens(tokens);
      this.user = tokens.user ?? null;
      await this.reload();
    },

    async logout(): Promise<void> {
      const refreshToken = tokenStore.refreshToken;
      if (refreshToken) {
        // 退出失败也要把本地清掉：用户点了退出就该退出
        await cApp.logout(refreshToken).catch((error: unknown) => {
          console.warn("[session] 通知服务端退出失败，本地仍按已退出处理", error);
        });
      }
      tokenStore.clear();
      this.$reset();
      this.hasSession = false;
      this.status = "anonymous";
    },

    async activatePet(petId: number): Promise<void> {
      this.user = await cApp.activatePet(petId);
    },

    /** 宠物增删改之后刷新列表——当前宠物可能因此变了。 */
    async refreshPets(): Promise<void> {
      this.pets = await cApp.listPets();
      if (this.user) {
        this.user = await cApp.me();
      }
    },

    applyTokens(tokens: TokenPair): void {
      tokenStore.save({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
      this.hasSession = true;
    },
  },
});
