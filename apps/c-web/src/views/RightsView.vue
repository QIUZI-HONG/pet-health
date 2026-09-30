<script setup lang="ts">
/**
 * 我的权益（切片 #112；决策见 ADR-0038 第三节 / ADR-0045）。
 *
 * 权益是「用户对某项 AI 或报告功能的可使用权」，由**邀请（永久）与打卡（当月）**两条路径叠加取得，
 * 同一功能取来源优先级最高的那条：**订阅 > 邀请 > 打卡**——按到期时间比会把语义算错
 * （「邀请得永久 + 订阅得月度」时，取最晚到期会选错来源）。
 *
 * 判定是**服务端实时的**（不缓存），界面只显示结论、不自己拼规则：任何模块自己拼一套，
 * 都会让客服与用户看到两个答案（ADR-0045）。
 *
 * `effective=false` 的权益**也列出来**（带名字）：让用户看到「有这项权益、但当前不生效」，
 * 而不是给他一个空的权益页。
 */
import { ref, watch } from "vue";
import { createLatestGuard, formatDateTime, toApiFailure } from "@pet-health/shared";
import { commerce, type RightsItemView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const session = useSessionStore();
const rights = ref<RightsItemView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const latest = createLatestGuard();

async function load(): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const result = await commerce.getRights(signal);
    if (!latest.isCurrent(token)) return;
    rights.value = result.rights ?? [];
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载权益失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load();
  },
  { immediate: true },
);

const RIGHT_SOURCE_NOTE = "来源优先级：订阅 > 邀请（永久）> 打卡（当月）；判定以服务端实时结果为准。";
</script>

<template>
  <section>
    <h2 class="ph-page-title">我的权益</h2>
    <p class="ph-page-desc">邀请得永久权益，打卡阶梯得当月权益；同一项权益取来源最高的那条。</p>

    <SessionGate forbidden-description="登录后查看你的权益。">
      <StateLoading v-if="loading" :rows="3" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />

      <article v-else-if="rights.length === 0" class="ph-card">
        <StateEmpty
          icon="🎫"
          title="还没有权益"
          description="权益来自邀请与打卡：邀请好友得永久权益，打卡阶梯得当月权益。"
        >
          <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'invites' }">去邀请好友</RouterLink>
          <RouterLink class="ph-button ph-button--text" :to="{ name: 'records' }">去打卡</RouterLink>
        </StateEmpty>
      </article>

      <template v-else>
        <ul class="ph-rights__list">
          <li v-for="right in rights" :key="right.code" class="ph-card ph-rights__item">
            <div class="ph-rights__head">
              <span class="ph-rights__name">{{ right.name ?? right.code }}</span>
              <span class="ph-rights__state" :class="{ 'ph-rights__state--on': right.effective }">
                {{ right.effective ? "生效中" : "未生效" }}
              </span>
            </div>
            <p class="ph-rights__meta ph-text-sub">
              权益码：<span class="ph-rights__code">{{ right.code }}</span>
            </p>
            <p class="ph-rights__meta ph-text-sub">
              来源：{{ right.source_name ?? "—" }} ·
              到期：{{ right.expire_at ? formatDateTime(right.expire_at) : "永久" }}
            </p>
          </li>
        </ul>

        <p class="ph-text-weak ph-rights__note">{{ RIGHT_SOURCE_NOTE }}</p>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-rights__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-3);
}

.ph-rights__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-rights__name {
  font-size: 15px;
  font-weight: 600;
}

.ph-rights__state {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  background: var(--ph-color-bg);
  color: var(--ph-color-text-weak);
}

.ph-rights__state--on {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-rights__meta {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
}

.ph-rights__code {
  font-family: var(--ph-font-numeric);
}

.ph-rights__note {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
}

@media (max-width: 1080px) {
  .ph-rights__list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
