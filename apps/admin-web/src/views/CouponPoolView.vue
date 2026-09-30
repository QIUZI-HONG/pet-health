<script setup lang="ts">
/**
 * 券池管理（交付文档 3.2 P035，路由 /admin/coupon-pool）：券目录与全局发放口径。
 *
 * 券池的三方关系（CONTEXT.md「券池」）：服务者贡献券 → 进券池 → 用户完成任务（邀请 / 打卡 /
 * 积分兑换）或平台定向发放拿到券 → 下单时自动匹配 → 到店核销。这一页管**平台这一侧**：
 * 券模板（只能平台建）、券池的账、券实例的排查，以及平台补贴券的发放入口。
 * 服务者那一边的「选券并承诺额度」在他自己的后台（`/provider/coupon-contributions`）。
 *
 * 四段在这一页，因为它们读的是同一批数据的不同切面：模板改了 → 总览的计数跟着动（所以模板面板
 * 结束后让总览重挂）；券实例是「哪一张卡住了」的排查面；补贴券的发放是模板与额度的出口。
 *
 * 分类（标准目录）在这一层加载一次传下去：券的适用范围与**标准目录编码**挂钩（`scope_type=1`
 * 用分类编码），理由与 CatalogView 相同——面板各拉一次不仅多两次往返，还会出现
 * 「分类刚改完、表单里还是旧的」。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import { adminApp, type CategoryRow } from "../api/adminApi";
import { useAdminSession } from "../session";
import { isIdentityError, toApiFailure } from "@pet-health/shared";
import CouponTemplatePanel from "../components/CouponTemplatePanel.vue";
import CouponPoolOverviewPanel from "../components/CouponPoolOverviewPanel.vue";
import CouponInstancePanel from "../components/CouponInstancePanel.vue";
import CouponIssuePanel from "../components/CouponIssuePanel.vue";

const { status } = useAdminSession();

type Tab = "templates" | "overview" | "instances" | "issue";
const tab = ref<Tab>("templates");

const categories = ref<CategoryRow[]>([]);
const categoriesLoading = ref(false);
const categoriesForbidden = ref(false);
const categoriesError = ref("");
const categoriesRequestId = ref("");

/** 总览面板的重新加载口：模板启停会改变总览里的计数（启用中的模板数、可发放额度） */
const overviewKey = ref(0);

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

/** 模板面板改动后：重载分类（可能新加了分类）并让总览面板重挂（计数变了） */
function onTemplatesChanged(): void {
  overviewKey.value += 1;
  void loadCategories();
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
    <h2 class="ph-page-title">券池管理</h2>
    <p class="ph-page-desc">
      平台统一定义的券模板与券池的账：面额 / 门槛 / 有效期 / 适用范围 / 成本归属只能由平台维护，服务者侧只能选券并承诺可核销额度。券不做抢券，发放全部定向；成本归属只影响核销统计与考核，不产生资金（ADR-0036）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <nav class="ph-tabs" aria-label="券池管理分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'templates' }"
          @click="tab = 'templates'"
        >
          券模板
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'overview' }"
          @click="tab = 'overview'"
        >
          券池总览与对账
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'instances' }"
          @click="tab = 'instances'"
        >
          券实例查询
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'issue' }"
          @click="tab = 'issue'"
        >
          平台补贴券发放
        </button>
      </nav>

      <!-- 分类是券模板那一段的输入（适用范围勾选）。这里**只提一句、不挡面板**：把面板写成
           `v-else` 会在分类加载完成时把它重挂一次，输入到一半的表单与刚提交成功的提示会一起消失，
           顺带多发一次模板请求。券模板本身不依赖分类，只有「适用范围」那一块需要。 -->
      <p v-if="tab === 'templates' && categoriesLoading" class="ph-alert ph-alert--info ph-coupon__alert">
        正在加载标准目录分类…
      </p>
      <p v-else-if="tab === 'templates' && categoriesError" class="ph-alert ph-alert--error ph-coupon__alert">
        {{ categoriesError }}
        <span v-if="categoriesRequestId" class="ph-text-weak">（请求 ID：{{ categoriesRequestId }}）</span>
        <button type="button" class="ph-table__action ph-coupon__retry" @click="loadCategories">重新加载分类</button>
      </p>

      <template v-if="tab === 'templates'">
        <ConsoleState
          v-if="categoriesForbidden"
          variant="forbidden"
          title="暂无权限"
          description="这个运营账号的令牌不能读取标准目录分类，券模板的适用范围选不了。"
        />
        <CouponTemplatePanel v-else :categories="categories" @changed="onTemplatesChanged" />
      </template>
      <CouponPoolOverviewPanel v-else-if="tab === 'overview'" :key="overviewKey" />
      <CouponInstancePanel v-else-if="tab === 'instances'" />
      <CouponIssuePanel v-else-if="tab === 'issue'" />
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-coupon__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-coupon__retry {
  margin-left: var(--ph-space-3);
}
</style>
