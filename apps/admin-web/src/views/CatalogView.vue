<script setup lang="ts">
/**
 * 标准目录管理（交付文档 3.2 P034，路由 /admin/catalog）：标准服务项与价格区间。
 *
 * 这是平台控价与控标准化的地方：服务者只能从这份目录勾选品并在区间内定价，区间外的价格
 * 由后端拒绝（90001），目录里没有的服务项要审核后才进得来。三段都在这页：
 * 分类 → 目录项（含区间）→ 目录外提案审批。
 *
 * 分类是目录项与提案两段的公共输入（编码前缀决定项目编码），所以在这一层加载一次传下去：
 * 三个面板各拉一次不仅多两次往返，还会出现「分类刚改完、另一个面板还是旧的」。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import { adminApp, type CategoryRow } from "../api/adminApi";
import { useAdminSession } from "../session";
import { isIdentityError, toApiFailure } from "@pet-health/shared";
import CategoryPanel from "../components/CategoryPanel.vue";
import CatalogItemPanel from "../components/CatalogItemPanel.vue";
import ProposalReviewPanel from "../components/ProposalReviewPanel.vue";

const { status } = useAdminSession();

type Tab = "categories" | "items" | "proposals";
const tab = ref<Tab>("items");

const categories = ref<CategoryRow[]>([]);
const categoriesLoading = ref(false);
const categoriesForbidden = ref(false);
const categoriesError = ref("");
const categoriesRequestId = ref("");

async function loadCategories(): Promise<void> {
  categoriesLoading.value = true;
  categoriesForbidden.value = false;
  categoriesError.value = "";
  categoriesRequestId.value = "";
  try {
    categories.value = await adminApp.listCategories();
  } catch (error) {
    if (isIdentityError(error)) {
      categoriesForbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "分类加载失败，请稍后重试");
    categoriesError.value = failure.message;
    categoriesRequestId.value = failure.requestId;
  } finally {
    categoriesLoading.value = false;
  }
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    void loadCategories();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">标准目录管理</h2>
    <p class="ph-page-desc">
      平台统一维护标准服务项目录与每项的价格区间：服务者从这份目录勾选品、在区间内定价，区间外的价格由后端拒绝；目录里没有的服务项，由服务者提案、运营审核通过后才进得来。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <nav class="ph-tabs" aria-label="标准目录分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'items' }"
          @click="tab = 'items'"
        >
          目录项与区间
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'categories' }"
          @click="tab = 'categories'"
        >
          分类
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'proposals' }"
          @click="tab = 'proposals'"
        >
          目录外提案审批
        </button>
      </nav>

      <!-- 分类是两段的公共输入：加载失败在这里说一次，别在每个分段各报一次 -->
      <ConsoleState
        v-if="categoriesLoading"
        variant="loading"
        title="正在加载分类"
      />
      <template v-else-if="categoriesForbidden">
        <ConsoleState
          variant="forbidden"
          title="暂无权限"
          description="这个运营账号的令牌不能读取标准目录分类。"
        />
      </template>
      <p v-else-if="categoriesError" class="ph-alert ph-alert--error ph-catalog__alert">
        {{ categoriesError }}
        <span v-if="categoriesRequestId" class="ph-text-weak">（请求 ID：{{ categoriesRequestId }}）</span>
        <button type="button" class="ph-table__action ph-catalog__retry" @click="loadCategories">重新加载分类</button>
      </p>

      <CatalogItemPanel
        v-if="tab === 'items' && !categoriesLoading && !categoriesForbidden"
        :categories="categories"
        @changed="loadCategories"
      />
      <CategoryPanel v-else-if="tab === 'categories'" @changed="loadCategories" />
      <ProposalReviewPanel
        v-else-if="tab === 'proposals' && !categoriesLoading && !categoriesForbidden"
        :categories="categories"
        @changed="loadCategories"
      />
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-catalog__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-catalog__retry {
  margin-left: var(--ph-space-3);
}
</style>
