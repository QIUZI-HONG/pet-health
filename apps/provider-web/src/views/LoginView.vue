<script setup lang="ts">
/**
 * 服务者后台登录页（F021）。
 *
 * 三条与 C 端登录页不同的地方，都是「独立登录域」带来的：
 *  1. 登录的是 `/api/v1/provider/auth/login`，拿到的是 **provider 域**令牌——C 端登录了
 *     也不能进这里（ADR-0012），所以页面顶上要写清这一点，别让用户拿 C 端账号反复试；
 *  2. **不设准入**：任何启用中的账号都能登进来（BPM-4 的第一步是「申请入驻」，
 *     而申请要先能进控制台）。进来以后看不到订单也正常——那由各接口的绑定校验决定；
 *  3. 登进来默认去**今日概览**（`/`），而不是回跳某个深链接：后台没有「深链接分享」的场景。
 *
 * 失败文案直接用服务端给的 `message`：40300（账号被禁用）与 40100（口令不对）在服务端
 * 就是两句不同的话，前端再翻译一遍只会漂移。
 *
 * **注册也在这一页**（与 C 端同一手感）：账号与 C 端是同一批（ADR-0035），区别只在
 * 「注册完给什么」——这里注册成功**直接进后台**（provider 域令牌，见 `providerAuth.register`）。
 * 权限后置：进来能看到什么，由各接口的绑定校验决定（未入驻的账号看到的是空态与引导）。
 */
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { toUserMessage } from "@pet-health/shared";
import { providerAuth } from "../api/auth";
import { signIn } from "../session";

const router = useRouter();
/** 登录 / 注册：与 C 端登录页同一个手感（两态互切，注册成功即登录） */
const mode = ref<"login" | "register">("login");
const nickname = ref("");
const phone = ref("");
const password = ref("");
const submitting = ref(false);
const errorMessage = ref("");

const isRegister = computed(() => mode.value === "register");

function switchMode(): void {
  mode.value = isRegister.value ? "login" : "register";
  errorMessage.value = "";
}

async function submit(): Promise<void> {
  if (submitting.value || !phone.value.trim() || !password.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = "";
  try {
    // 注册即登录：两个接口都返回 provider 域的令牌对（契约 provider.yaml 的 /auth/register）
    const tokens = isRegister.value
      ? await providerAuth.register({
        phone: phone.value.trim(),
        password: password.value,
        nickname: nickname.value.trim() === "" ? undefined : nickname.value.trim(),
      })
      : await providerAuth.login({ phone: phone.value.trim(), password: password.value });
    signIn({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
    await router.replace({ name: "dashboard" });
  } catch (error) {
    errorMessage.value = toUserMessage(error, "登录失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="ph-console__login">
    <form class="ph-card ph-login" @submit.prevent="submit">
      <h1 class="ph-login__title">服务者后台</h1>
      <p class="ph-text-sub">
        {{ isRegister
          ? "注册一个账号就能提交入驻申请。账号与 C 端是同一批，手机号通用。"
          : "用提交入驻申请的手机号登录。后台与 C 端是两个登录入口，这里的登录状态不影响你手机上的账号。" }}
      </p>

      <label v-if="isRegister" class="ph-field">
        <span class="ph-field__label">称呼</span>
        <input v-model="nickname" class="ph-input" type="text" maxlength="64" placeholder="选填，如：王店长" />
      </label>

      <label class="ph-field">
        <span class="ph-field__label">手机号 *</span>
        <input v-model="phone" class="ph-input" type="tel" autocomplete="username" maxlength="20" />
      </label>
      <label class="ph-field">
        <span class="ph-field__label">密码 *</span>
        <input v-model="password" class="ph-input" type="password" autocomplete="current-password" />
      </label>

      <p v-if="errorMessage" class="ph-error">{{ errorMessage }}</p>

      <button class="ph-button" type="submit" :disabled="submitting || !phone.trim() || !password">
        {{ submitting ? (isRegister ? "注册中…" : "登录中…") : (isRegister ? "注册并进入" : "登录") }}
      </button>
      <p class="ph-text-sub">
        {{ isRegister
          ? "已经有账号了？"
          : "还没有门店？注册一个账号，或直接用同一手机号登录后提交入驻申请。" }}
        <button type="button" class="ph-login__switch" @click="switchMode">
          {{ isRegister ? "去登录" : "去注册" }}
        </button>
      </p>
    </form>
  </div>
</template>

<style scoped>
.ph-console__login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--ph-color-bg);
}

.ph-login {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
  width: min(420px, 92vw);
}

.ph-login__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

/* 登录 / 注册互切：与 C 端登录页同一个手感（文字链，不抢主按钮的注意力） */
.ph-login__switch {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ph-color-primary);
  font: inherit;
  cursor: pointer;
}
</style>
