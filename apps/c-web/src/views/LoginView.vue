<script setup lang="ts">
/**
 * 登录 / 注册。
 *
 * 独立于框架（不套左栏），因为未登录时导航没有意义。登录成功后回到来处（`redirect` 查询参数），
 * 没有就回首页——用户从深链接被拦下来时不该丢上下文。
 *
 * 没有短信通道，所以是手机号 + 密码（ADR-0012 / 0027 的取舍）；验证码登录等短信落地后作为并列方式补上。
 *
 * **邀请码在这里填，而且只有这一次**（ADR-0039 第一节）：归因的时点就是注册那一刻，
 * 注册成功后由本页立即调一次 `/invites/attribution`；接口层没有任何「事后补填」的出口
 * （补填就是刷券的入口）。分享链接带 `?invite=CODE` 时这里预填，链接只做预填、以用户填的为准。
 */
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { toApiFailure } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import { attributeAfterRegister, readRememberedInvite } from "../utils/invite";

const session = useSessionStore();
const router = useRouter();
const route = useRoute();

const mode = ref<"login" | "register">("login");
const submitting = ref(false);
const errorMessage = ref("");
const requestId = ref("");

const form = reactive({ phone: "", password: "", nickname: "", inviteCode: "" });

/**
 * 归因的渠道标签（契约：1 分享链接（预填）/ 2 注册表单手工填）。
 * 预填来自链接、用户没动过就是 1；用户自己敲/改了就是 2——两者都算「用户填了码」。
 */
const inviteChannel = ref(2);

onMounted(() => {
  const remembered = readRememberedInvite();
  if (remembered) {
    form.inviteCode = remembered.code;
    inviteChannel.value = remembered.channel;
  }
});

/** 失焦后才提示：刚打开页面、还没开始填就红一片是很烦人的。 */
const phoneTouched = ref(false);
const passwordTouched = ref(false);

const isRegister = computed(() => mode.value === "register");
const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(form.phone));
const passwordValid = computed(
  () => form.password.length >= 8 && form.password.length <= 32 && /[A-Za-z]/.test(form.password) && /\d/.test(form.password),
);
const canSubmit = computed(() => phoneValid.value && passwordValid.value && !submitting.value);

const phoneHint = computed(() => {
  if (!phoneTouched.value || phoneValid.value) return "";
  return form.phone.length === 0 ? "请填写手机号" : "手机号格式不对，应为 1 开头的 11 位数字";
});

const passwordHint = computed(() => {
  if (!passwordTouched.value || passwordValid.value || form.password.length === 0) return "";
  return "密码需 8–32 位，且同时包含字母与数字";
});

function switchMode(): void {
  mode.value = isRegister.value ? "login" : "register";
  errorMessage.value = "";
  requestId.value = "";
}

/** 手改邀请码 → 渠道标签变成「注册表单手工填」（契约的两个取值都由前端如实上报）。 */
function onInviteInput(): void {
  inviteChannel.value = 2;
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  errorMessage.value = "";
  requestId.value = "";
  submitting.value = true;
  try {
    if (isRegister.value) {
      await session.register(form.phone, form.password, form.nickname.trim() || undefined);
      // 归因只有一次机会，时点就是注册那一刻（ADR-0039 第一节）：注册成功后立即调一次。
      // **不 await**：这是一次旁路调用，超时或失败都不能把用户拦在注册页（失败只进日志）。
      const inviteCode = form.inviteCode.trim();
      if (inviteCode) {
        // 服务端返回的那句话由**跳转之后的页面**展示（首页的提示条，中转见 utils/invite.ts）——
        // 注册页在这一刻就要卸载了，留在这里显示等于没显示
        void attributeAfterRegister(inviteCode, inviteChannel.value);
      }
    } else {
      await session.login(form.phone, form.password);
    }
    const redirect = route.query.redirect;
    await router.push(typeof redirect === "string" && redirect ? redirect : { name: "home" });
  } catch (error) {
    const failure = toApiFailure(error, "登录失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
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
            class="ph-field__input ph-field__input--lg"
            :class="{ 'ph-field__input--invalid': phoneHint }"
            inputmode="numeric"
            maxlength="11"
            placeholder="13800138000"
            autocomplete="tel"
            @blur="phoneTouched = true"
          />
          <span v-if="phoneHint" class="ph-field__hint">{{ phoneHint }}</span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">密码</span>
          <input
            v-model="form.password"
            class="ph-field__input ph-field__input--lg"
            :class="{ 'ph-field__input--invalid': passwordHint }"
            type="password"
            placeholder="8–32 位，含字母与数字"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            @blur="passwordTouched = true"
          />
          <span v-if="passwordHint" class="ph-field__hint">{{ passwordHint }}</span>
        </label>
        <label v-if="isRegister" class="ph-field">
          <span class="ph-field__label">昵称（可选）</span>
          <input v-model.trim="form.nickname" class="ph-field__input ph-field__input--lg" maxlength="64" placeholder="怎么称呼你" />
        </label>
        <label v-if="isRegister" class="ph-field">
          <span class="ph-field__label">邀请码（可选）</span>
          <input
            v-model.trim="form.inviteCode"
            class="ph-field__input ph-field__input--lg"
            maxlength="32"
            placeholder="好友的邀请码"
            @input="onInviteInput"
          />
          <span class="ph-field__label">
            邀请码只在注册时有效，注册完成后无法补填——请现在确认。
          </span>
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
        <!-- 「注册即表示同意」必须把两份文档做成**入口**：只写书名号等于让人签一份读不到的东西 -->
        <template v-if="isRegister">
          注册即表示同意
          <RouterLink to="/legal/user_agreement">《用户协议》</RouterLink>
          与
          <RouterLink to="/legal/privacy_policy">《隐私政策》</RouterLink>
        </template>
        <template v-else>登录后可管理宠物档案与健康记录</template>
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
