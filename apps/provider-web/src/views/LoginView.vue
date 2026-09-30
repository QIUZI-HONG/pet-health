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
 */
import { ref } from "vue";
import { useRouter } from "vue-router";
import { toUserMessage } from "@pet-health/shared";
import { providerAuth } from "../api/auth";
import { signIn } from "../session";

const router = useRouter();
const phone = ref("");
const password = ref("");
const submitting = ref(false);
const errorMessage = ref("");

async function submit(): Promise<void> {
  if (submitting.value || !phone.value.trim() || !password.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = "";
  try {
    const tokens = await providerAuth.login({ phone: phone.value.trim(), password: password.value });
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
        用提交入驻申请的手机号登录。后台与 C 端是两个登录入口，这里的登录状态不影响你手机上的账号。
      </p>

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
        {{ submitting ? "登录中…" : "登录" }}
      </button>
      <p class="ph-text-sub">
        还没有门店？用同一手机号登录后即可提交入驻申请——审核通过后这里会出现你的订单与核销。
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
</style>
