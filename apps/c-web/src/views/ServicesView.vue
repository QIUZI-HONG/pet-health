<script setup lang="ts">
/**
 * 服务页：按分类找店（切片 #104 的 C 端出口；决策见 ADR-0034 / ADR-0037）。
 *
 * 三件事：**六个分类筛选**、**门店名搜索**、**分页列表**。价格与在架服务项在详情页
 * （`/providers/:id`）——「这家店做这个项目多少钱」是那道具体的缝，列表页负责把人带过去。
 *
 * **不需要登录**：只读浏览对身份没有要求（ADR-0037 第一节），所以这一页**不套 SessionGate**
 * ——套上它，未登录的访客只会看到一句「请先登录」，而这一页恰恰是最该给访客看的一页
 * （浏览是转化漏斗的入口）。需要身份的是「预约」这个动作，它在详情页里。
 *
 * 列表里显示的门店**都已由服务端筛过**（状态正常且有未过期资质），所以这里不再判「能不能约」：
 * 前端不复制服务端的可见性口径，那正是两个判据分叉的开始。
 */
import { ref, watch } from "vue";
import { createLatestGuard, toApiFailure } from "@pet-health/shared";
import { providers, type ProviderSummaryView } from "../api/providers";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const PAGE_SIZE = 20;

/**
 * 六个分类 = 服务者的 `type`（交付文档 5.2 的六大类）。
 *
 * 这里的标签只用于**筛选项**；卡片上的分类名用服务端给的 `type_name`（同一套名字由后端的
 * `Provider.typeName` 定义）——避免「筛选项叫洗护、卡片上叫洗护美容」这种同物两名。
 */
const CATEGORY_FILTERS: Array<{ value: number | null; label: string }> = [
  { value: null, label: "全部分类" },
  { value: 1, label: "医院" },
  { value: 2, label: "洗护美容" },
  { value: 3, label: "训犬" },
  { value: 4, label: "寄养上门" },
  { value: 5, label: "食品用品" },
  { value: 6, label: "间接服务" },
];

const rows = ref<ProviderSummaryView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const category = ref<number | null>(null);
/** 输入框里的字；它改了**不等于**要立刻发请求（见 submitSearch）。 */
const keyword = ref("");
/** 已提交的搜索词——请求只用它，避免输入过程中的每个字都变成一次查询。 */
const appliedKeyword = ref("");
const page = ref(1);
const total = ref(0);

/** 并发守卫：连点分类或翻页时，先发的请求可能后回来，用旧结果盖掉新筛选（实现见 shared）。 */
const latest = createLatestGuard();

async function load(targetPage = 1): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const trimmed = appliedKeyword.value.trim();
    const result = await providers.list(
      {
        type: category.value ?? undefined,
        // 空白关键词不发：服务端把它当「不过滤」，白送一个参数没有意义
        keyword: trimmed === "" ? undefined : trimmed,
        page: targetPage,
        pageSize: PAGE_SIZE,
      },
      signal,
    );
    if (!latest.isCurrent(token)) return;
    rows.value = result.list ?? [];
    page.value = result.page ?? targetPage;
    total.value = result.total ?? rows.value.length;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载门店失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

function selectCategory(value: number | null): void {
  if (category.value === value) return;
  category.value = value;
  void load(1);
}

/** 关键词**提交时才搜**（回车或点按钮）：门店名搜索的收益要等词打完才出现。 */
function submitSearch(): void {
  appliedKeyword.value = keyword.value;
  void load(1);
}

/** 清空全部筛选（空态里的「清空筛选」用）。 */
function clearFilters(): void {
  category.value = null;
  keyword.value = "";
  appliedKeyword.value = "";
  void load(1);
}

watch(
  () => total.value,
  (value) => {
    // 翻页翻过头（筛掉的店正好在最后一页）时回到第一页，别把人留在一个空页上
    if (value > 0 && rows.value.length === 0 && page.value > 1) void load(1);
  },
);

void load(1);
</script>

<template>
  <section>
    <h2 class="ph-page-title">服务</h2>
    <p class="ph-page-desc">
      按分类找门店，进店能看到在架的服务项与价格；选好项目再约时段，费用在门店直接付给服务者。
    </p>

    <p class="ph-services__cross">
      也可以<RouterLink class="ph-services__cross-link" :to="{ name: 'catalog' }">按项目找服务</RouterLink>
      ——先选项目，再看哪些门店能做、各自多少钱；或者
      <RouterLink class="ph-services__cross-link" :to="{ name: 'service-finder' }">说说它怎么了</RouterLink>
      ，按症状帮你找。
    </p>

    <div class="ph-services__search">
      <input
        v-model="keyword"
        class="ph-services__input"
        type="search"
        maxlength="32"
        placeholder="搜索门店名称"
        aria-label="搜索门店名称"
        @keyup.enter="submitSearch"
      />
      <button type="button" class="ph-button ph-button--primary" @click="submitSearch">搜索</button>
    </div>

    <div class="ph-services__filters">
      <button
        v-for="filter in CATEGORY_FILTERS"
        :key="String(filter.value)"
        type="button"
        class="ph-services__filter"
        :class="{ 'ph-services__filter--active': category === filter.value }"
        @click="selectCategory(filter.value)"
      >
        {{ filter.label }}
      </button>
    </div>

    <StateLoading v-if="loading" :rows="4" />
    <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load(page)" />

    <article v-else-if="rows.length === 0" class="ph-card">
      <StateEmpty
        icon="🐾"
        title="没有找到门店"
        :description="
          category === null && appliedKeyword === ''
            ? '平台正在接入服务者，之后可以按分类浏览、比价和预约。'
            : '换个分类或关键词试试，也可以清空筛选看全部门店。'
        "
      >
        <button
          v-if="category !== null || keyword !== ''"
          type="button"
          class="ph-button ph-button--secondary"
          @click="clearFilters"
        >
          清空筛选
        </button>
      </StateEmpty>
    </article>

    <template v-else>
      <ul class="ph-services__list">
        <li v-for="provider in rows" :key="provider.id" class="ph-card ph-services__item">
          <div class="ph-services__head">
            <img v-if="provider.logo" class="ph-services__logo" :src="provider.logo" :alt="provider.name ?? '门店'" />
            <div>
              <p class="ph-services__name">{{ provider.name ?? "门店" }}</p>
              <p class="ph-services__meta">
                <span class="ph-services__tag">{{ provider.type_name ?? "服务者" }}</span>
                <span class="ph-text-sub">评分 {{ provider.rating ?? "—" }}</span>
              </p>
            </div>
          </div>

          <p v-if="provider.intro" class="ph-services__intro ph-text-sub">{{ provider.intro }}</p>

          <dl class="ph-services__facts">
            <div>
              <dt>地址</dt>
              <dd>{{ provider.address ?? "—" }}</dd>
            </div>
            <div>
              <dt>电话</dt>
              <!-- 服务端给的就是脱敏值（138****8888，ADR-0049 第二节）：打电话找店不必看完整号码 -->
              <dd>{{ provider.phone || "—" }}</dd>
            </div>
          </dl>

          <div class="ph-services__actions">
            <RouterLink
              class="ph-button ph-button--secondary"
              :to="{ name: 'provider-detail', params: { id: provider.id } }"
            >
              查看门店与价格
            </RouterLink>
          </div>
        </li>
      </ul>

      <div class="ph-services__pager">
        <span class="ph-text-weak">共 {{ total }} 家，第 {{ page }} 页</span>
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
          :disabled="rows.length < PAGE_SIZE || loading"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ph-services__cross {
  margin: 0 0 var(--ph-space-3);
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-services__cross-link {
  color: var(--ph-color-primary);
}

.ph-services__search {
  display: flex;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-3);
}

.ph-services__input {
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

.ph-services__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-4);
}

.ph-services__filter {
  height: 32px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-button);
  font-family: inherit;
  font-size: 13px;
  color: var(--ph-color-text);
  cursor: pointer;
}

.ph-services__filter--active {
  background: var(--ph-color-primary-light);
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
  font-weight: 600;
}

.ph-services__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-services__head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
}

.ph-services__logo {
  width: 44px;
  height: 44px;
  border-radius: var(--ph-radius-input);
  object-fit: cover;
  background: var(--ph-color-bg);
}

.ph-services__name {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ph-services__meta {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-services__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary);
}

.ph-services__intro {
  margin: var(--ph-space-3) 0 0;
  font-size: 13px;
}

.ph-services__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-4);
  margin: var(--ph-space-3) 0 0;
  font-size: 12px;
}

.ph-services__facts dt {
  color: var(--ph-color-text-weak);
}

.ph-services__facts dd {
  margin: 2px 0 0;
  color: var(--ph-color-text-sub);
}

.ph-services__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-services__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
