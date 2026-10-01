<script setup lang="ts">
/**
 * 按项目找服务：**项目 → 门店**（服务页是「分类 → 门店」，这一页是它的反向）。
 *
 * 三件事：分类导航（来自目录，不是前端常量）、项目名搜索、项目列表。每个项目给出
 * **平台区间价**与「哪些店能做」的入口——「这个项目多少钱」要到门店维度看，
 * 区间价只是平台允许的范围（ADR-0034 第三节），界面上必须说清楚，否则会被读成「起价」。
 *
 * **不需要登录**（ADR-0037 第一节）：与门店浏览同一口径，这一页也不套 `SessionGate`。
 */
import { ref } from "vue";
import { createLatestGuard, formatAmountRange, toApiFailure } from "@pet-health/shared";
import { catalog, type CatalogCategoryView, type CatalogItemView } from "../api/catalog";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const PAGE_SIZE = 20;

const categories = ref<CatalogCategoryView[]>([]);
const items = ref<CatalogItemView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const category = ref("");
/** 输入框里的字；它改了不等于立刻发请求（见 submitSearch）。 */
const keyword = ref("");
/** 已提交的搜索词——请求只用它，避免输入过程中的每个字都变成一次查询。 */
const appliedKeyword = ref("");
const page = ref(1);
const total = ref(0);

/** 并发守卫：连点分类或翻页时，先发的请求可能后回来（实现见 shared）。 */
const latest = createLatestGuard();

/** 分类名与顺序来自服务端：运营改了名字，C 端立刻跟着变（各端抄一份常量就会显示旧名字）。 */
async function loadCategories(): Promise<void> {
  try {
    categories.value = await catalog.categories();
  } catch {
    // 分类拉不到不该让整页报错：项目列表仍然可用（只是少了筛选入口）
    categories.value = [];
  }
}

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const trimmed = appliedKeyword.value.trim();
    const result = await catalog.items(
      {
        categoryCode: category.value === "" ? undefined : category.value,
        keyword: trimmed === "" ? undefined : trimmed,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!latest.isCurrent(token)) return;
    items.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? items.value.length;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载服务项目失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

function selectCategory(code: string): void {
  if (category.value === code) return;
  category.value = code;
  void load(1);
}

/** 关键词**提交时才搜**（回车或点按钮）：项目名搜索的收益要等词打完才出现。 */
function submitSearch(): void {
  appliedKeyword.value = keyword.value;
  void load(1);
}

function clearFilters(): void {
  category.value = "";
  keyword.value = "";
  appliedKeyword.value = "";
  void load(1);
}

/** 预计耗时：没填就不显示，不显示成 0 分钟。 */
function durationText(minutes: number | null | undefined): string {
  return minutes == null ? "" : `约 ${minutes} 分钟`;
}

void loadCategories();
void load(1);
</script>

<template>
  <section>
    <p class="ph-text-weak">
      <RouterLink :to="{ name: 'services' }">← 返回服务页</RouterLink>
    </p>
    <h2 class="ph-page-title">按项目找服务</h2>
    <p class="ph-page-desc">
      先选项目，再看哪些门店能做、各自多少钱。平台给的是允许定价的范围，具体价格由门店在范围内定。
    </p>

    <div class="ph-catalog__search">
      <input
        v-model="keyword"
        class="ph-catalog__input"
        type="search"
        maxlength="32"
        placeholder="搜索服务项目（如「体检」）"
        aria-label="搜索服务项目"
        @keyup.enter="submitSearch"
      />
      <button type="button" class="ph-button ph-button--primary" @click="submitSearch">搜索</button>
    </div>

    <!-- 分类标签墙（交付文档 4.16.5 的「按服务项目找」）：三列流式的分类标签，
         点一个下钻到该分类的项目列表。**粒度与稿子差一层**：稿子的 chip 是项目、
         我们的是分类——契约只给「分页的项目列表」，没有「按分类分组取项目」的出口，
         为了不在前端硬凑（一次请求 ×N 个分类）就这么分了，差异记在缺陷计划里 -->
    <div v-if="categories.length > 0" class="ph-catalog__wall">
      <button
        type="button"
        class="ph-catalog__chip"
        :class="{ 'ph-catalog__chip--active': category === '' }"
        @click="selectCategory('')"
      >
        全部分类
      </button>
      <button
        v-for="entry in categories"
        :key="entry.code"
        type="button"
        class="ph-catalog__chip"
        :class="{ 'ph-catalog__chip--active': category === entry.code }"
        @click="selectCategory(entry.code ?? '')"
      >
        {{ entry.name }}<span v-if="entry.item_count" class="ph-catalog__chip-count">{{ entry.item_count }}</span>
      </button>
    </div>

    <StateLoading v-if="loading" :rows="4" />
    <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

    <article v-else-if="items.length === 0" class="ph-card">
      <StateEmpty
        icon="📋"
        title="没有找到服务项目"
        :description="
          category === '' && appliedKeyword === ''
            ? '平台正在整理标准服务目录，之后可以按项目查价格与可预约的门店。'
            : '换个分类或关键词试试，也可以清空筛选看全部项目。'
        "
      >
        <button
          v-if="category !== '' || keyword !== ''"
          type="button"
          class="ph-button ph-button--secondary"
          @click="clearFilters"
        >
          清空筛选
        </button>
      </StateEmpty>
    </article>

    <template v-else>
      <ul class="ph-catalog__list">
        <li v-for="item in items" :key="item.code" class="ph-card ph-catalog__item">
          <div class="ph-catalog__main">
            <div>
              <p class="ph-catalog__name">{{ item.name ?? "服务项目" }}</p>
              <p class="ph-catalog__meta ph-text-sub">
                <span v-if="item.category_name">{{ item.category_name }}</span>
                <span v-if="item.price_unit">计价单位：{{ item.price_unit }}</span>
                <span v-if="durationText(item.duration_minutes)">{{ durationText(item.duration_minutes) }}</span>
              </p>
              <p v-if="item.description" class="ph-catalog__desc ph-text-sub">{{ item.description }}</p>
            </div>
            <div class="ph-catalog__price">
              <span class="ph-catalog__range">{{ formatAmountRange(item.price_min, item.price_max) }}</span>
              <span class="ph-text-weak">平台区间价</span>
            </div>
          </div>

          <div class="ph-catalog__actions">
            <RouterLink
              class="ph-button ph-button--secondary"
              :to="{ name: 'catalog-item', params: { code: item.code } }"
            >
              哪些门店能做
            </RouterLink>
          </div>
        </li>
      </ul>

      <div class="ph-catalog__pager">
        <span class="ph-text-weak">共 {{ total }} 个项目，第 {{ page }} 页</span>
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
          :disabled="items.length < PAGE_SIZE || loading"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ph-catalog__search {
  display: flex;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-3);
}

.ph-catalog__input {
  flex: 1;
  height: 40px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
}

.ph-catalog__wall {
  display: grid;
  /* 三列流式（稿子的形态）：窄屏自动降到两列/一列，不写死列数 */
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-4);
}

.ph-catalog__chip {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ph-space-2);
  padding: var(--ph-space-2) var(--ph-space-3);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-surface);
  font: inherit;
  color: var(--ph-color-text-sub);
  cursor: pointer;
}

.ph-catalog__chip--active {
  border-color: var(--ph-color-primary);
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
  font-weight: 500;
}

.ph-catalog__chip-count {
  color: var(--ph-color-text-weak);
  font-size: 12px;
}



.ph-catalog__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-catalog__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-catalog__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ph-catalog__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-catalog__desc {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
}

.ph-catalog__price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  font-size: 12px;
  text-align: right;
  white-space: nowrap;
}

.ph-catalog__range {
  font-family: var(--ph-font-numeric);
  font-size: 16px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-catalog__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-catalog__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
