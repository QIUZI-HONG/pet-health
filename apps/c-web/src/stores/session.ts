/**
 * 会话状态：当前用户、宠物列表、当前宠物。
 *
 * 三件事在这里统一：
 *   - 启动时用本地令牌换一次 `/users/me`（令牌过期会被请求层静默刷新，ADR-0012）；
 *   - 「当前宠物」以服务端为准（`active_pet_id`），本地只做兜底——用户可能在另一台浏览器上切过；
 *   - 令牌失效统一收敛成 `anonymous`，页面据此显示无权限态，而不是各页面自己判断。
 */
import { defineStore } from "pinia";
import { cApp, tokenStore, type Pet, type UserProfile } from "@pet-health/shared";

export type SessionStatus = "idle" | "loading" | "authenticated" | "anonymous";

interface SessionState {
  status: SessionStatus;
  user: UserProfile | null;
  pets: Pet[];
  /** 启动失败的原因；用于错误态展示与重试。 */
  errorMessage: string;
}

export const useSessionStore = defineStore("session", {
  state: (): SessionState => ({
    status: "idle",
    user: null,
    pets: [],
    errorMessage: "",
  }),

  getters: {
    isLoggedIn: (state) => state.status === "authenticated",
    /** 当前宠物：服务端记的那只；它被删掉或没切换过时回退到列表第一只（契约里的约定）。 */
    activePet(state): Pet | null {
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
        return;
      }
      this.status = "loading";
      await this.reload();
    },

    async reload(): Promise<void> {
      this.status = "loading";
      this.errorMessage = "";
      try {
        const [user, pets] = await Promise.all([cApp.me(), cApp.listPets()]);
        this.user = user;
        this.pets = pets;
        this.status = "authenticated";
      } catch (error) {
        this.user = null;
        this.pets = [];
        // 令牌问题 → 匿名；其它（网络/服务异常）→ 也退回匿名但把原因留给页面展示
        tokenStore.clear();
        this.errorMessage = error instanceof Error ? error.message : "加载失败";
        this.status = "anonymous";
      }
    },

    async login(phone: string, password: string): Promise<void> {
      const tokens = await cApp.login({ phone, password });
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
        await cApp.logout(refreshToken).catch(() => undefined);
      }
      tokenStore.clear();
      this.$reset();
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

    applyTokens(tokens: { access_token: string; refresh_token: string }): void {
      tokenStore.save({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
    },
  },
});
