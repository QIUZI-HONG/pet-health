<script setup lang="ts">
/**
 * 门店详情：看店（价格在这一页）。
 *
 * 这一页回答的是「**这家店做这个项目多少钱**」——在此之前用户只能在下单页盲点提交，
 * 靠订单返回的预估实付才知道价格（ADR-0047 推进顺序里欠的那一半）。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li>**只读、不需要登录**（ADR-0037 第一节）：未登录可以看门店、资质与全部价格；
 *       只有「预约」这个动作要身份，未登录时跳到登录页并带回来处；
 *   <li>**40400 不是「重试」**：未过审 / 已驳回 / 已冻结 / 资质全过期的店与不存在的店同码，
 *       页面按「这家店看不了」处理（给返回入口，不给重试按钮——重试会一直是同样的结果）；
 *   <li>**没有在架服务不是错误**（契约写明）：如实说「暂无在架服务」，不是把它显示成加载失败。
 * </ul>
 *
 * 另加一块**用户评价**（只读、要登录）：评分是门店详情里的聚合值（`rating`），条数与列表来自
 * `GET /providers/{id}/reviews`——两个数一起看才是「N 分 / M 条评价」。
 * 评价自己有一份区块级的加载/失败/空态（见下方注释）：它加载失败不该把门店与价格一起换成错误页；
 * 未登录时它给登录入口而不是发一个注定 40100 的请求（评价属「内容面」，游客只给「浏览服务」）。
 */
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ApiError, businessHoursText, createLatestGuard, formatDate, toApiFailure } from "@pet-health/shared";
import { commerce, type OrderReviewView } from "../api/commerce";
import { providers, type ProviderDetailView, type ProviderServiceOfferView } from "../api/providers";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { formatAmount } from "../utils/money";
import { reviewContentText, reviewStars } from "../utils/review";

const route = useRoute();
const session = useSessionStore();

const detail = ref<ProviderDetailView | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
/** 「这家店看不了」（40400）：与加载失败分开——重试没有意义，要给人另一个出口。 */
const notBrowsable = ref(false);

/** 并发守卫：在门店之间来回切时，先发的请求可能后回来（实现见 shared）。 */
const latest = createLatestGuard();

const providerId = computed(() => Number(route.params.id));

async function load(): Promise<void> {
  if (!Number.isFinite(providerId.value) || providerId.value <= 0) {
    notBrowsable.value = true;
    loading.value = false;
    return;
  }
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  notBrowsable.value = false;
  try {
    detail.value = await providers.detail(providerId.value, signal);
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    // 40400：「看不到」与「不存在」对服务端是同一件事，对用户也是——给返回入口而不是重试
    if (error instanceof ApiError && error.code === 40400) {
      notBrowsable.value = true;
      detail.value = null;
      return;
    }
    const failure = toApiFailure(error, "加载门店失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

watch(providerId, () => void load(), { immediate: true });

/** 营业时间：不出现的星期几就是休息（空数组 = 整周休息），格式与后台同一份实现。 */
const hoursText = computed(() => businessHoursText(detail.value?.business_hours));

// ---------------------------------------------------------------- 用户评价（只读、要登录）

/**
 * 评价列表是**这一页的第二个请求**，所以它有自己的一份加载状态与并发守卫。
 *
 * 为什么不用页面级那套四态：四态属于「门店详情」这一份数据——评价加载失败不该把门店信息、
 * 价格与资质一起换成一个错误页（那三块仍然有用，而且能下单）。所以这里的失败是**区块级**的：
 * 一句话 + 重试按钮，页面其余部分照常。
 *
 * **要登录**（契约写明：评价是「内容面」，游客只给「浏览服务」）。所以未登录时**不发这个请求**
 * ——发了只会拿到 40100，然后把「未登录」显示成「加载失败」。这与「预约」的处理是同一条思路：
 * 该要身份的动作就给登录入口，而不是让用户撞一次错误。
 */
const REVIEW_PAGE_SIZE = 5;
const reviews = ref<OrderReviewView[]>([]);
const reviewsLoading = ref(false);
const reviewsError = ref("");
const reviewsHasMore = ref(false);
const reviewTotal = ref(0);
const reviewPage = ref(0);
const reviewGuard = createLatestGuard();

/** 评价条数与门店评分一起才是「N 分 / M 条评价」——评分是门店详情的 `rating`（聚合值）。 */
const ratingText = computed(() => detail.value?.rating ?? "—");

/**
 * 加载评价。
 *
 * @param page 要加载的页码；**传 1 表示重来**（首次加载与失败重试都走它），
 *             更大的页码表示「查看更多」——追加到现有列表后面
 */
async function loadReviews(page: number): Promise<void> {
  const target = providerId.value;
  if (!Number.isFinite(target) || target <= 0 || !session.isLoggedIn) return;
  const { token, signal } = reviewGuard.claim();
  reviewsLoading.value = true;
  reviewsError.value = "";
  try {
    const result = await commerce.listProviderReviews(target, { page, pageSize: REVIEW_PAGE_SIZE }, signal);
    if (!reviewGuard.isCurrent(token)) return;
    reviews.value = page <= 1 ? result.list : [...reviews.value, ...result.list];
    reviewTotal.value = result.total;
    reviewsHasMore.value = result.has_more;
    reviewPage.value = result.page;
  } catch (error) {
    if (!reviewGuard.isCurrent(token)) return;
    reviewsError.value = toApiFailure(error, "评价加载失败，请稍后重试").message;
  } finally {
    if (reviewGuard.isCurrent(token)) {
      reviewsLoading.value = false;
    }
  }
}

/**
 * 门店换了一家、或登录状态变了就重新加载：切换时先清空，别把上一家的评价（或登录前的空态）
 * 留在列表里。
 */
watch(
  [providerId, () => session.isLoggedIn],
  () => {
    reviews.value = [];
    reviewTotal.value = 0;
    reviewsHasMore.value = false;
    reviewPage.value = 0;
    reviewsError.value = "";
    if (session.isLoggedIn) {
      void loadReviews(1);
    }
  },
  { immediate: true },
);

/** 未登录时的评价入口：去登录，并带回这一页（与「预约」同一手法）。 */
const loginTarget = computed(() => ({ name: "login", query: { redirect: route.fullPath } }));

/** 资质有效期：为空表示长期有效。 */
function validUntilText(validUntil: string | null | undefined): string {
  return validUntil ? `有效期至 ${formatDate(validUntil)}` : "长期有效";
}

/** 预计耗时：没填（null）时不显示，不显示成 0 分钟。 */
function durationText(minutes: number | null | undefined): string {
  return minutes == null ? "" : `约 ${minutes} 分钟`;
}

/**
 * 「预约」的目标：带着门店与服务项进下单页。
 *
 * 未登录时先去登录并带回来处——把「登录后才能预约」做成一次跳转，而不是让用户点一下
 * 拿到 40100 的报错（那会把「先登录」说成「操作失败」）。
 */
function bookTarget(service: ProviderServiceOfferView) {
  if (!session.isLoggedIn) {
    return { name: "login", query: { redirect: route.fullPath } };
  }
  return {
    name: "order-create",
    query: {
      provider_id: providerId.value,
      service_id: service.id,
      // 仅用于展示的名字；下单依据是上面两个 id（下单页会写明这一点）
      service_name: service.service_name ?? undefined,
      provider_name: detail.value?.name ?? undefined,
    },
  };
}
</script>

<template>
  <section>
    <p class="ph-text-weak">
      <RouterLink :to="{ name: 'services' }">← 返回服务页</RouterLink>
    </p>

    <StateLoading v-if="loading" :rows="4" />
    <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />

    <article v-else-if="notBrowsable" class="ph-card">
      <StateEmpty
        icon="🏠"
        title="这家门店暂不可浏览"
        description="它可能还没有通过审核，或资质已过期。换一家看看。"
      >
        <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'services' }">回服务页</RouterLink>
      </StateEmpty>
    </article>

    <template v-else-if="detail">
      <article class="ph-card ph-provider__head">
        <div class="ph-provider__title">
          <img v-if="detail.logo" class="ph-provider__logo" :src="detail.logo" :alt="detail.name ?? '门店'" />
          <div>
            <h2 class="ph-provider__name">{{ detail.name ?? "门店" }}</h2>
            <p class="ph-provider__meta">
              <span class="ph-provider__tag">{{ detail.type_name ?? "服务者" }}</span>
              <span class="ph-text-sub">评分 {{ detail.rating ?? "—" }}</span>
            </p>
          </div>
        </div>

        <p v-if="detail.intro" class="ph-provider__intro ph-text-sub">{{ detail.intro }}</p>

        <dl class="ph-provider__facts">
          <div>
            <dt>地址</dt>
            <dd>{{ detail.address ?? "—" }}</dd>
          </div>
          <div>
            <dt>电话</dt>
            <!-- 脱敏值（服务端给的，ADR-0049 第二节） -->
            <dd>{{ detail.phone || "—" }}</dd>
          </div>
          <div>
            <dt>营业时间</dt>
            <dd>{{ hoursText }}</dd>
          </div>
        </dl>
      </article>

      <section class="ph-provider__section">
        <h3 class="ph-provider__heading">服务项目与价格</h3>
        <p class="ph-provider__note ph-text-weak">
          价格是门店在平台区间内的定价；费用在门店直接付给服务者，平台不经手资金。
        </p>

        <p v-if="(detail.services?.length ?? 0) === 0" class="ph-card ph-provider__empty ph-text-sub">
          这家门店暂无在架服务，可以换一家看看或稍后再来。
        </p>
        <ul v-else class="ph-provider__services">
          <li v-for="service in detail.services" :key="service.id" class="ph-card ph-provider__service">
            <div class="ph-provider__service-main">
              <p class="ph-provider__service-name">{{ service.service_name ?? "服务项" }}</p>
              <p class="ph-provider__service-meta ph-text-sub">
                <span v-if="service.category_name">{{ service.category_name }}</span>
                <span v-if="service.price_unit">计价单位：{{ service.price_unit }}</span>
                <span v-if="durationText(service.duration_minutes)">{{ durationText(service.duration_minutes) }}</span>
              </p>
            </div>
            <div class="ph-provider__service-side">
              <span class="ph-provider__price">{{ formatAmount(service.price) }}</span>
              <RouterLink class="ph-button ph-button--primary" :to="bookTarget(service)">预约</RouterLink>
            </div>
          </li>
        </ul>
      </section>

      <section class="ph-provider__section">
        <h3 class="ph-provider__heading">资质</h3>
        <p v-if="(detail.qualifications?.length ?? 0) === 0" class="ph-provider__empty ph-text-sub">
          平台暂未展示这家的资质材料。
        </p>
        <ul v-else class="ph-provider__qualifications">
          <li v-for="qualification in detail.qualifications" :key="`${qualification.type}-${qualification.name}`">
            <span class="ph-provider__qualification-name">
              {{ qualification.name ?? qualification.type_name ?? "资质材料" }}
            </span>
            <span class="ph-text-weak">{{ validUntilText(qualification.valid_until) }}</span>
          </li>
        </ul>
        <p class="ph-provider__note ph-text-weak">
          只展示已通过平台审核且在有效期内的材料；证件编号不对外展示。
        </p>
      </section>

      <!-- 用户评价：评分是门店详情的聚合值，条数与列表来自评价接口（两者一起看才是「N 分 / M 条」） -->
      <section class="ph-provider__section">
        <h3 class="ph-provider__heading">用户评价</h3>
        <p class="ph-provider__review-summary ph-text-sub">
          评分 <strong>{{ ratingText }}</strong>
          <span v-if="session.isLoggedIn && !reviewsLoading && !reviewsError">· 共 {{ reviewTotal }} 条评价</span>
        </p>
        <p class="ph-provider__note ph-text-weak">
          评价只能由完成过服务的用户在订单里提交（一单一评），平台不筛选、不代写。
        </p>

        <!-- 未登录：不给「加载失败」，给登录入口（评价是内容面，要身份） -->
        <p v-if="!session.isLoggedIn" class="ph-card ph-provider__empty ph-text-sub">
          登录后可以查看这家的用户评价。
          <RouterLink class="ph-button ph-button--text" :to="loginTarget">去登录</RouterLink>
        </p>

        <p v-else-if="reviewsLoading && reviews.length === 0" class="ph-text-sub">评价加载中…</p>

        <p v-else-if="reviewsError" class="ph-provider__review-error">
          {{ reviewsError }}
          <button type="button" class="ph-button ph-button--text" @click="loadReviews(1)">重试</button>
        </p>

        <p v-else-if="reviews.length === 0" class="ph-card ph-provider__empty ph-text-sub">
          还没有评价。完成一次服务后，你可以在订单里评价这家门店。
        </p>

        <template v-else>
          <ul class="ph-provider__reviews">
            <li v-for="review in reviews" :key="review.id" class="ph-provider__review">
              <p class="ph-provider__review-head">
                <span class="ph-provider__review-stars" :aria-label="`${review.rating} 星`">
                  {{ reviewStars(review.rating) }}
                </span>
                <span class="ph-text-weak">{{ formatDate(review.created_at) }}</span>
              </p>
              <p class="ph-provider__review-content">{{ reviewContentText(review.content) }}</p>
            </li>
          </ul>

          <button
            v-if="reviewsHasMore"
            type="button"
            class="ph-button ph-button--secondary"
            :disabled="reviewsLoading"
            @click="loadReviews(reviewPage + 1)"
          >
            {{ reviewsLoading ? "加载中…" : "查看更多评价" }}
          </button>
        </template>
      </section>
    </template>

    <article v-else class="ph-card">
      <StateEmpty icon="🏠" title="没有找到这家门店" description="它可能已经被移除。">
        <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'services' }">回服务页</RouterLink>
      </StateEmpty>
    </article>
  </section>
</template>

<style scoped>
.ph-provider__head {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-provider__title {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
}

.ph-provider__logo {
  width: 52px;
  height: 52px;
  border-radius: var(--ph-radius-input);
  object-fit: cover;
  background: var(--ph-color-bg);
}

.ph-provider__name {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.ph-provider__meta {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-provider__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary);
}

.ph-provider__intro {
  margin: 0;
  font-size: 13px;
}

.ph-provider__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-4);
  margin: 0;
  font-size: 12px;
}

.ph-provider__facts dt {
  color: var(--ph-color-text-weak);
}

.ph-provider__facts dd {
  margin: 2px 0 0;
  color: var(--ph-color-text-sub);
}

.ph-provider__section {
  margin-top: var(--ph-space-5);
}

.ph-provider__heading {
  margin: 0 0 var(--ph-space-2);
  font-size: 16px;
  font-weight: 600;
}

.ph-provider__note {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
}

.ph-provider__empty {
  margin: 0;
  padding: var(--ph-space-4);
  border-radius: var(--ph-radius-card);
  font-size: 13px;
}

.ph-provider__services,
.ph-provider__qualifications {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-provider__service {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-provider__service-name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ph-provider__service-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-provider__service-side {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
}

.ph-provider__price {
  font-family: var(--ph-font-numeric);
  font-size: 20px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-provider__qualifications {
  gap: var(--ph-space-2);
  font-size: 13px;
}

.ph-provider__qualifications li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding: var(--ph-space-2) 0;
  border-bottom: 1px solid var(--ph-color-border);
}

.ph-provider__qualification-name {
  color: var(--ph-color-text);
}

/* 用户评价区块：列表与「查看更多」 */
.ph-provider__review-summary {
  margin: 0 0 var(--ph-space-1);
  font-size: 13px;
}

.ph-provider__review-error {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 13px;
  color: var(--ph-color-danger);
}

.ph-provider__reviews {
  list-style: none;
  margin: 0 0 var(--ph-space-3);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-provider__review {
  padding: var(--ph-space-3);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-card);
}

.ph-provider__review-head {
  margin: 0;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ph-space-3);
  font-size: 12px;
}

.ph-provider__review-stars {
  color: var(--ph-color-orange);
  font-size: 15px;
  letter-spacing: 2px;
}

.ph-provider__review-content {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ph-color-text-sub);
}
</style>
