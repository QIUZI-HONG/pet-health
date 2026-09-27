<script setup lang="ts">
/**
 * 登录 / 注册。
 *
 * 独立于框架（不套左栏），因为未登录时导航没有意义。登录成功后回到来处（`redirect` 查询参数），
 * 没有就回首页——用户从深链接被拦下来时不该丢上下文。
 *
 * 没有短信通道，所以是手机号 + 密码（ADR-0012 的取舍）；验证码登录等短信落地后作为并列方式补上。
 */
import { computed, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiError } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const router = useRouter();
const route = useRoute();

const mode = ref<"login" | "register">("login");
const submitting = ref(false);
const errorMessage = ref("");
const requestId = ref("");

const form = reactive({ phone: "", password: "", nickname: "" });

const isRegister = computed(() => mode.value === "register");
const canSubmit = computed(
  () => /^1[3-9]\d{9}$/.test(form.phone) && form.password.length >= 8 && !submitting.value,
);

function switchMode(): void {
  mode.value = isRegister.value ? "login" : "register";
  errorMessage.value = "";
  requestId.value = "";
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  errorMessage.value = "";
  requestId.value = "";
  submitting.value = true;
  try {
    if (isRegister.value) {
      await session.register(form.phone, form.password, form.nickname.trim() || undefined);
    } else {
      await session.login(form.phone, form.password);
    }
    const redirect = route.query.redirect;
    await router.push(typeof redirect === "string" && redirect ? redirect : { name: "home" });
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      requestId.value = error.requestId;
    } else {
      errorMessage.value = "登录失败，请稍后重试";
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="ph-login">
    <div class="ph-login__panel">
      <div class="ph-login__brand">
        <span aria-hidden="true">🐾</span>
        <span>宠物健康管家</span>
      </div>
      <h1 class="ph-login__title">{{ isRegister ? "注册账号" : "登录" }}</h1>
      <p class="ph-login__desc">
        {{ isRegister ? "手机号注册，立刻开始建档。" : "用注册时的手机号与密码登录。" }}
      </p>

      <form class="ph-login__form" @submit.prevent="submit">
        <label class="ph-field">
          <span class="ph-field__label">手机号</span>
          <input
            v-model.trim="form.phone"
            class="ph-field__input"
            inputmode="numeric"
            maxlength="11"
            placeholder="13800138000"
            autocomplete="tel"
          />
        </label>
        <label class="ph-field">
          <span class="ph-field__label">密码</span>
          <input
            v-model="form.password"
            class="ph-field__input"
            type="password"
            placeholder="8–32 位，含字母与数字"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
          />
        </label>
        <label v-if="isRegister" class="ph-field">
          <span class="ph-field__label">昵称（可选）</span>
          <input v-model.trim="form.nickname" class="ph-field__input" maxlength="64" placeholder="怎么称呼你" />
        </label>

        <p v-if="errorMessage" class="ph-login__error">
          {{ errorMessage }}
          <span v-if="requestId" class="ph-login__trace">（请求 ID：{{ requestId }}）</span>
        </p>

        <button type="submit" class="ph-button ph-button--primary ph-login__submit" :disabled="!canSubmit">
          {{ submitting ? "请稍候…" : isRegister ? "注册并登录" : "登录" }}
        </button>
      </form>

      <button type="button" class="ph-button ph-button--text" @click="switchMode">
        {{ isRegister ? "已有账号？去登录" : "没有账号？去注册" }}
      </button>

      <p class="ph-login__note">
        接口是切片 #94 的注册 / 登录（bcrypt + JWT Access 2h + Redis Refresh 7d，ADR-0012）。
      </p>
    </div>
  </div>
</template>

<style scoped>
.ph-login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--ph-space-8);
  background: var(--ph-color-bg);
}

.ph-login__panel {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--ph-space-3);
  padding: var(--ph-space-8);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-modal);
}

.ph-login__brand {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-weight: 600;
}

.ph-login__title {
  margin: var(--ph-space-2) 0 0;
  font-size: 22px;
}

.ph-login__desc {
  margin: 0 0 var(--ph-space-2);
  color: var(--ph-color-text-sub);
}

.ph-login__form {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-field {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-field__label {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-field__input {
  height: 40px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
}

.ph-field__input:focus {
  outline: none;
  border-color: var(--ph-color-primary);
}

.ph-login__error {
  margin: 0;
  font-size: 13px;
  color: var(--ph-color-danger);
}

.ph-login__trace {
  color: var(--ph-color-text-weak);
}

.ph-login__submit {
  height: 40px;
}

.ph-login__note {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}
</style>
