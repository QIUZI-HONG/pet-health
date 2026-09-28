<script setup lang="ts">
/**
 * 合规文档页（切片 #74）：隐私政策 / 用户协议 / AI 免责声明。
 *
 * 两个必须做对的点（ADR-0025）：
 *  1. **占位正文要标明**：法务定稿前正文只是占位说明，界面必须显示「待法务定稿」——
 *     把占位文字当生效条款展示，是实实在在的合规风险；
 *  2. 正文用 `white-space: pre-line` 渲染：条款是靠段落读的，不能挤成一坨。
 */
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { cApp, ApiError, type ComplianceDocumentView } from "@pet-health/shared";
import SessionGate from "../components/SessionGate.vue";
import StateLoading from "../components/states/StateLoading.vue";
import StateError from "../components/states/StateError.vue";

const route = useRoute();
const document = ref<ComplianceDocumentView | null>(null);
const loading = ref(true);
const errorMessage = ref("");

async function load(code: string): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    document.value = await cApp.getComplianceDocument(code);
  } catch (error) {
    document.value = null;
    errorMessage.value = error instanceof ApiError ? error.message : "文档加载失败";
  } finally {
    loading.value = false;
  }
}

watch(() => route.params.code as string, (code) => void load(code), { immediate: true });
</script>

<template>
  <section>
    <h2 class="ph-page-title">{{ document?.title ?? "条款与说明" }}</h2>

    <SessionGate forbidden-description="条款与账号操作需要先登录。">
      <article class="ph-card ph-legal">
        <StateLoading v-if="loading" :rows="4" />
        <StateError v-else-if="errorMessage" :message="errorMessage" @retry="load(route.params.code as string)" />

        <template v-else-if="document">
          <p v-if="document.is_placeholder" class="ph-legal__placeholder">
            这是待法务定稿的占位文本（版本 {{ document.version }}），
            <strong>不作为生效条款</strong>。定稿后会替换本页内容并标注生效日期。
          </p>
          <p v-else class="ph-text-sub">
            版本 {{ document.version }}<template v-if="document.effective_from"> · 自 {{ document.effective_from }} 起生效</template>
          </p>
          <div class="ph-legal__body">{{ document.body }}</div>
          <RouterLink class="ph-button ph-button--secondary" to="/profile">返回我的</RouterLink>
        </template>
      </article>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-legal {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-4);
  max-width: 760px;
}

.ph-legal__placeholder {
  margin: 0;
  padding: var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-orange-light);
  color: var(--ph-color-text);
  font-size: 14px;
}

.ph-legal__body {
  /* 条款靠段落读：保留换行 */
  white-space: pre-line;
  line-height: 1.9;
  color: var(--ph-color-text);
}
</style>
