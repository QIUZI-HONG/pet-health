<script setup lang="ts">
/**
 * 知识库浏览（F024 / F025，决策见 ADR-0033）：按关键词搜、按分类翻、点进去看正文。
 *
 * 四条口径都是契约与 ADR 定的，别在这里自己发明：
 *  1. **需要登录**：`/knowledge/**` 两条路径都没有标 `security: []`（app.yaml 顶部的
 *     `security: bearerAuth` 是全局的），未登录时后端回 40100。所以这一页套 `SessionGate`，
 *     且**等会话就绪再发请求**——无条件 onMounted 会让游客看到一串 40100，而不是「登录后查看」
 *     （消息中心踩过同一个坑）；
 *  2. **`review_status` 必须原样展示**（ADR-0033）：条目多数是 `pending_review`
 *     （工程按公开兽医共识起草、待合作兽医复核，ADR-0025 第二节），不许把未复核说成已复核；
 *  3. **列表不下发正文**（`body` 是空串）：列表用 `summary`，正文点进详情看；
 *  4. **只有可读的条目会出现**（复核通过或待复核）：草稿与已删除在这一组接口里根本读不到，
 *     所以列表为空就是真的没有，不是「被藏了」。
 *
 * 为什么只做关键词搜索、不做分类筛选：分类中文名**由服务端给**（C 端不认 `category_code`），
 * 而契约里没有「知识分类清单」这个出口——把十个编码抄进前端，等于把运营的词典冻在代码里。
 */
import { computed, ref, watch } from "vue";
import {
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

const PAGE_SIZE = 20;

const session = useSessionStore();
const entries = ref<KnowledgeEntryView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 输入框里的字；改了**不等于**要立刻发请求（与目录页、社区的搜索同一口径）。 */
const keyword = ref("");
/** 已提交的搜索词：请求只用它。 */
const appliedKeyword = ref("");
const page = ref(1);
const total = ref(0);
const hasMore = ref(false);

/** 并发守卫：连点翻页或改关键词时，先发的请求可能后回来（实现见 shared 的 createLatestGuard）。 */
const latest = createLatestGuard();

const filtersActive = computed(() => appliedKeyword.value.trim() !== "");

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const trimmed = appliedKeyword.value.trim();
    const result = await cApp.listKnowledgeEntries(
      {
        // 空白关键词不发：服务端把它当「不过滤」，白送一个参数没有意义
        keyword: trimmed === "" ? undefined : trimmed,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!latest.isCurrent(token)) return;
    entries.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? entries.value.length;
    // 没有 has_more 时退化成「这一页塞满了就还能翻」：两份判据都在契约里（PageResult）
    hasMore.value = result.has_more ?? entries.value.length >= PAGE_SIZE;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载知识条目失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

/** 关键词**提交时才搜**（回车或点按钮）：知识检索的中文匹配在服务端（ngram），逐字发没有意义。 */
function submitSearch(): void {
  appliedKeyword.value = keyword.value;
  void load(1);
}

function clearSearch(): void {
  keyword.value = "";
  appliedKeyword.value = "";
  void load(1);
}

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load(1);
  },
  { immediate: true },
);

/**
 * 复核状态 → 界面标记。**原样对应契约的取值**，不做「看起来已复核」的美化：
 * `vetted` 已复核；`pending_review` 待复核（这是多数条目的真实状态）。
 */
function reviewLabel(status: string | undefined): string {
  return status === "vetted" ? "已复核" : "待复核";
}

/** 风险等级 → 色档（分档判据在 shared 的 riskTone，这里只决定显示什么词）。 */
const RISK_WORD: Record<RiskTone, string> = { red: "建议尽快就医", yellow: "建议就医", green: "可先观察" };

function riskLabel(entry: KnowledgeEntryView): string {
  return entry.risk_level ? RISK_WORD[riskTone(entry.risk_level)] : "";
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">知识库</h2>
    <p class="ph-page-desc">
      平台整理的健康知识：品种、疫苗驱虫、症状分诊、常见病、行为营养与急救。
      多数条目还在等合作兽医复核，<strong>复核状态会标在每一条上</strong>，请对照着看。
    </p>

    <SessionGate forbidden-description="知识库需要登录后查看。">
      <div class="ph-knowledge__search">
        <input
          v-model="keyword"
          class="ph-knowledge__input"
          type="search"
          maxlength="64"
          placeholder="搜症状或主题，例如「犬瘟」「呕吐」"
          aria-label="搜索知识条目"
          @keyup.enter="submitSearch"
        />
        <button type="button" class="ph-button ph-button--primary" @click="submitSearch">搜索</button>
        <button v-if="filtersActive" type="button" class="ph-button ph-button--secondary" @click="clearSearch">
          清空
        </button>
      </div>

      <StateLoading v-if="loading" :rows="4" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

      <article v-else-if="entries.length === 0" class="ph-card">
        <StateEmpty
          icon="📚"
          :title="filtersActive ? '没有匹配的知识条目' : '知识库还在建设中'"
          :description="
            filtersActive
              ? '换个说法再搜：中文匹配按短语走（搜「犬瘟」能命中「犬瘟热」）。'
              : '条目由运营与工程按公开兽医共识起草，入库后就会出现在这里。'
          "
        >
          <button v-if="filtersActive" type="button" class="ph-button ph-button--secondary" @click="clearSearch">
            清空搜索
          </button>
        </StateEmpty>
      </article>

      <template v-else>
        <p class="ph-text-weak ph-knowledge__count">共 {{ total }} 条，第 {{ page }} 页</p>
        <ul class="ph-knowledge__list">
          <li v-for="entry in entries" :key="entry.code" class="ph-card ph-knowledge__item">
            <div class="ph-knowledge__meta">
              <span class="ph-knowledge__code">{{ entry.code }}</span>
              <span v-if="entry.category_name" class="ph-knowledge__tag">{{ entry.category_name }}</span>
              <!-- 复核状态：医疗合规要求，未复核的必须标出来（ADR-0033） -->
              <span
                class="ph-knowledge__review"
                :class="entry.review_status === 'vetted' ? 'ph-knowledge__review--vetted' : 'ph-knowledge__review--pending'"
              >
                {{ reviewLabel(entry.review_status) }}
              </span>
              <span v-if="riskLabel(entry)" class="ph-knowledge__tag ph-knowledge__tag--risk">
                {{ riskLabel(entry) }}
              </span>
            </div>

            <RouterLink class="ph-knowledge__title" :to="{ name: 'knowledge-entry', params: { code: entry.code } }">
              {{ entry.title }}
            </RouterLink>
            <p v-if="entry.summary" class="ph-text-sub ph-knowledge__summary">{{ entry.summary }}</p>
          </li>
        </ul>

        <div class="ph-knowledge__pager">
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="page <= 1 || loading"
            @click="load(page - 1)"
          >
            上一页
          </button>
          <button
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="!hasMore || loading"
            @click="load(page + 1)"
          >
            下一页
          </button>
        </div>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-knowledge__search {
  display: flex;
  gap: var(--ph-space-3);
  margin-bottom: var(--ph-space-4);
}

.ph-knowledge__input {
  flex: 1;
  max-width: 420px;
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font: inherit;
  color: var(--ph-color-text);
}

.ph-knowledge__count {
  margin: 0 0 var(--ph-space-3);
  font-size: 12px;
}

.ph-knowledge__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-knowledge__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
}

.ph-knowledge__code {
  font-family: var(--ph-font-numeric);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-knowledge__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-knowledge__tag--risk {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

/* 复核状态用两种底色分开：已复核是主色，待复核是提示色——它是一句提醒，不是装饰 */
.ph-knowledge__review {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
}

.ph-knowledge__review--vetted {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-knowledge__review--pending {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-knowledge__title {
  display: inline-block;
  margin-top: var(--ph-space-2);
  font-size: 16px;
  font-weight: 600;
  color: var(--ph-color-text);
}

.ph-knowledge__title:hover {
  color: var(--ph-color-primary);
}

.ph-knowledge__summary {
  margin: var(--ph-space-2) 0 0;
  line-height: 1.7;
}

.ph-knowledge__pager {
  display: flex;
  justify-content: center;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
