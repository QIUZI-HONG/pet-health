<script setup lang="ts">
/**
 * 一条知识条目的正文（F024 / F025；决策见 ADR-0033）。
 *
 * 三条口径：
 *  1. **复核状态必须原样展示**：多数条目是 `pending_review`（待合作兽医复核），
 *     界面不许把未复核说成已复核；详情页把它放在正文之前，用户先看到它再读内容；
 *  2. **医疗合规（docs/conventions.md）**：知识内容必须带免责声明；条目里带就医紧迫程度
 *     （`risk_level`）且为红色时，必须把「建议尽快就医」明说出来（宁严勿松）；
 *  3. **不可读 = 40400**：不存在的编号、已删除、复核状态不可读在契约里是同一件事
 *     （免得用编号探测哪些条目存在过），所以这里说「这条不在了」，不给「重试」——
 *     重试也不会变。
 */
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  ApiError,
  cApp,
  createLatestGuard,
  riskTone,
  toApiFailure,
  type KnowledgeEntryView,
  type RiskTone,
} from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const route = useRoute();
const session = useSessionStore();
const entry = ref<KnowledgeEntryView | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 40400：这条读不到（不存在 / 已删除 / 不可读三者同码）。重试没有意义，也不是「加载失败」。 */
const gone = ref(false);

const latest = createLatestGuard();

async function load(): Promise<void> {
  const code = String(route.params.code ?? "");
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  gone.value = false;
  entry.value = null;
  try {
    const result = await cApp.getKnowledgeEntry(code, signal);
    if (!latest.isCurrent(token)) return;
    entry.value = result;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    if (error instanceof ApiError && error.code === 40400) {
      gone.value = true;
      return;
    }
    const failure = toApiFailure(error, "加载知识条目失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

// 编号变了就重拉（从列表连点两条不同条目时复用同一个组件实例）。
// **也要等会话就绪**：这一组接口要求登录，游客直接打开详情链接时不该拿到一串 40100
// （那会让「登录后查看」变成一条错误）——与列表页同一口径。
watch(
  [() => route.params.code, () => session.isLoggedIn],
  () => {
    if (session.isLoggedIn) void load();
  },
  { immediate: true },
);

const RISK_WORD: Record<RiskTone, string> = { red: "紧急", yellow: "需关注", green: "可先观察" };

function riskLabel(entryView: KnowledgeEntryView): string {
  return entryView.risk_level ? RISK_WORD[riskTone(entryView.risk_level)] : "";
}

/** 红色风险必须建议就医（宁严勿松）；其余档位只描述紧迫程度，不擅自加医嘱。 */
function riskAdvice(entryView: KnowledgeEntryView): string {
  return riskTone(entryView.risk_level) === "red" ? "出现这类情况请尽快就医。" : "";
}

/** 来源链接只在它是 http(s) 时渲染成可点的链接：来源可能是书名或文献名，不是网址。 */
function sourceIsLink(entryView: KnowledgeEntryView): boolean {
  return /^https?:\/\//.test(entryView.source_url ?? "");
}
</script>

<template>
  <section>
    <p class="ph-text-weak">
      <RouterLink :to="{ name: 'knowledge' }">← 返回知识库</RouterLink>
    </p>

    <SessionGate forbidden-description="知识库需要登录后查看。">
      <StateLoading v-if="loading" :rows="5" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />
      <article v-else-if="gone" class="ph-card">
        <StateEmpty
          icon="📚"
          title="这条知识条目不在了"
          description="它可能已被撤下或合并。回知识库看看别的条目。"
        >
          <RouterLink class="ph-button ph-button--primary" :to="{ name: 'knowledge' }">回知识库</RouterLink>
        </StateEmpty>
      </article>

      <article v-else-if="entry" class="ph-card">
      <div class="ph-entry__meta">
        <span class="ph-entry__code">{{ entry.code }}</span>
        <span v-if="entry.category_name" class="ph-entry__tag">{{ entry.category_name }}</span>
        <span
          class="ph-entry__review"
          :class="entry.review_status === 'vetted' ? 'ph-entry__review--vetted' : 'ph-entry__review--pending'"
        >
          {{ entry.review_status === "vetted" ? "已复核" : "待复核" }}
        </span>
        <span v-if="riskLabel(entry)" class="ph-entry__tag ph-entry__tag--risk">
          就医紧迫程度：{{ riskLabel(entry) }}
        </span>
      </div>

      <h2 class="ph-page-title ph-entry__title">{{ entry.title }}</h2>
      <p v-if="entry.summary" class="ph-text-sub ph-entry__summary">{{ entry.summary }}</p>

      <!-- 未复核的内容必须在正文之前说清：它是「工程按公开兽医共识起草、还没经合作兽医复核」
           （ADR-0025 第二节），读了之后按已复核来用就是我们的责任 -->
      <p v-if="entry.review_status !== 'vetted'" class="ph-entry__notice">
        这条内容<strong>还没有经过合作兽医复核</strong>，按公开兽医共识起草，仅供参考。
      </p>
      <p v-if="riskAdvice(entry)" class="ph-entry__notice ph-entry__notice--risk">{{ riskAdvice(entry) }}</p>

      <!-- 正文是纯文本：不渲染 HTML（不用 v-html，见 components/README.md） -->
      <p class="ph-entry__body">{{ entry.body }}</p>

      <dl v-if="entry.source_title || entry.source_url" class="ph-entry__source">
        <dt>来源</dt>
        <dd>
          {{ entry.source_title ?? "未标注来源" }}
          <a
            v-if="sourceIsLink(entry)"
            class="ph-entry__link"
            :href="entry.source_url ?? undefined"
            target="_blank"
            rel="noopener noreferrer"
          >
            查看原文
          </a>
        </dd>
      </dl>

      <p class="ph-entry__disclaimer">
        以上内容仅供参考，不能替代兽医诊断；宠物出现紧急症状请尽快就医。
      </p>
      </article>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-entry__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
}

.ph-entry__code {
  font-family: var(--ph-font-numeric);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-entry__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-entry__tag--risk {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-entry__review {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
}

.ph-entry__review--vetted {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-entry__review--pending {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-entry__title {
  margin: var(--ph-space-3) 0 0;
}

.ph-entry__summary {
  margin: var(--ph-space-2) 0 0;
  font-size: 14px;
  line-height: 1.7;
}

.ph-entry__notice {
  margin: var(--ph-space-4) 0 0;
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-orange-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-orange);
  font-size: 13px;
  line-height: 1.7;
}

.ph-entry__notice--risk {
  background: var(--ph-color-danger-light);
  color: var(--ph-color-danger);
}

.ph-entry__body {
  margin: var(--ph-space-4) 0 0;
  line-height: 1.9;
  white-space: pre-wrap;
}

.ph-entry__source {
  margin: var(--ph-space-5) 0 0;
  font-size: 13px;
}

.ph-entry__source dt {
  color: var(--ph-color-text-sub);
}

.ph-entry__source dd {
  margin: var(--ph-space-1) 0 0;
}

.ph-entry__link {
  margin-left: var(--ph-space-2);
  color: var(--ph-color-primary);
}

.ph-entry__disclaimer {
  margin: var(--ph-space-5) 0 0;
  padding-top: var(--ph-space-4);
  border-top: 1px solid var(--ph-color-divider);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ph-color-text-weak);
}
</style>
