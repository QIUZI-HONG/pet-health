<script setup lang="ts">
/**
 * 运营后台登录页（F021）。
 *
 * 与服务者后台那份的区别只有**准入**：本端是名单制——不在 `app.console.admin-user-ids`
 * 里的账号会拿到 40300（不是 40100：口令是对的，只是这个账号不是运营后台账号）。
 * 所以失败文案直接用服务端那句，不在这里另写一套。
 *
 * 登录成功去**服务者审核**页（运营进来第一件事通常是审入驻），而不是某个深链接。
 */
import { ref } from "vue";
import { useRouter } from "vue-router";
import { toUserMessage } from "@pet-health/shared";
import { adminAuth } from "../api/auth";
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
    const tokens = await adminAuth.login({ phone: phone.value.trim(), password: password.value });
    signIn({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
    await router.replace({ name: "providers" });
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
      <h1 class="ph-login__title">运营后台</h1>
      <p class="ph-text-sub">
        运营账号由平台配置（账号 id 名单）。与服务者后台、C 端是三个互不通用的登录入口，
        在别处登录不影响这里。
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
        提示：如果提示「该账号不是运营后台账号」，说明这个手机号还没被加进运营名单——
        联系平台超管在配置里加上账号 id。
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
