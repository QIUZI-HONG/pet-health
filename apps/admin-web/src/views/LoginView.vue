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
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { toUserMessage } from "@pet-health/shared";
import { adminAuth } from "../api/auth";
import { signIn } from "../session";

const router = useRouter();
const phone = ref("");
const password = ref("");
/** 登录 / 注册两态：与其它两端同一个手感 */
const mode = ref<"login" | "register">("login");
const nickname = ref("");
const submitting = ref(false);
const errorMessage = ref("");
/** 注册成功后的提示（里面有「还差加入名单」那句话；它比错误更需要被看到，所以单独一格） */
const notice = ref("");

const isRegister = computed(() => mode.value === "register");

function switchMode(): void {
  mode.value = isRegister.value ? "login" : "register";
  errorMessage.value = "";
  notice.value = "";
}

async function submit(): Promise<void> {
  if (submitting.value || !phone.value.trim() || !password.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = "";
  notice.value = "";
  try {
    if (isRegister.value) {
      // 注册**不发令牌**：运营后台是名单制（fail-closed），所以这里只展示服务端给的那句话
      // （账号已建 + 还差把账号加入名单），然后切回登录态——不做「注册即进入」的假动作
      const created = await adminAuth.register({
        phone: phone.value.trim(),
        password: password.value,
        nickname: nickname.value.trim() === "" ? undefined : nickname.value.trim(),
      });
      notice.value = created.notice;
      mode.value = "login";
      password.value = "";
      return;
    }
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
        {{ isRegister
          ? "先注册一个账号。运营后台是名单制——账号建好之后，需要平台管理员把它加入名单才能登录。"
          : "运营账号由平台配置（账号 id 名单）。与服务者后台、C 端是三个互不通用的登录入口，在别处登录不影响这里。" }}
      </p>

      <label v-if="isRegister" class="ph-field">
        <span class="ph-field__label">称呼</span>
        <input v-model="nickname" class="ph-input" type="text" maxlength="64" placeholder="选填，如：张运营" />
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
      <p v-if="notice" class="ph-alert ph-alert--info">{{ notice }}</p>

      <button class="ph-button" type="submit" :disabled="submitting || !phone.trim() || !password">
        {{ submitting ? (isRegister ? "注册中…" : "登录中…") : (isRegister ? "注册账号" : "登录") }}
      </button>
      <p class="ph-text-sub">
        <button type="button" class="ph-login__switch" @click="switchMode">
          {{ isRegister ? "已经有账号了？去登录" : "还没有账号？去注册" }}
        </button>
      </p>
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

/* 登录 / 注册互切：与另两端同一个手感（文字链，不抢主按钮的注意力） */
.ph-login__switch {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ph-color-primary);
  font: inherit;
  cursor: pointer;
}
</style>
