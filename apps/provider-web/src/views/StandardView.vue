<script setup lang="ts">
/**
 * 服务标准（交付文档 3.2 P026，路由 /b/standard）。
 *
 * 这一页承载**标准目录这条线**：目录浏览（分类 → 项目 → 价格区间）、我的选品与上下架、
 * 目录外提案。选品与目录同屏是有意的——服务者要做的决定就是「目录里的哪一项、定多少钱」，
 * 拆成两页只会让人来回跳。
 *
 * P026 原本还含「接车检查 / 拍照 / 确认率」，那部分的数据源是订单履约与技师开工链路
 * （ADR-0040 的三道照片墙），接口还没进契约：**保留为说明，不造假数据**。
 *
 * 写操作的门禁来自 `GET /profile`：契约里新增选品要求「入驻通过 + 未冻结 + 有一份未过期的资质」，
 * 否则 40300。所以先把门禁读出来，把不能做的原因写在按钮旁边（而不是让用户点了才被拒）。
 */
import { computed, onMounted, ref } from "vue";
import { ApiError } from "@pet-health/shared";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import {
  providerApp,
  type ProviderProfileView,
  type ProviderQualificationView,
  type ServiceCategoryView,
} from "../api/providerApi";
import { useProviderSession } from "../session";
import { providerStatusLabel } from "@pet-health/shared";
import { hasNoValidQualification } from "../utils/labels";
import CatalogPanel from "../components/CatalogPanel.vue";
import MyServicesPanel from "../components/MyServicesPanel.vue";
import ProposalPanel from "../components/ProposalPanel.vue";

const { status } = useProviderSession();

type Tab = "catalog" | "services" | "proposals";
const tab = ref<Tab>("catalog");

const categories = ref<ServiceCategoryView[]>([]);
const categoriesLoading = ref(false);
const categoriesError = ref("");

const profile = ref<ProviderProfileView | null>(null);
/** 资质清单只能从入驻申请详情读（`ProviderProfileView` 里没有这个字段），这里只判「有没有一份有效的」 */
const qualifications = ref<ProviderQualificationView[]>([]);

/** 能不能写：入驻通过（status=1）、未冻结、且至少一份有效资质 —— 与后端 40300 的条件对齐 */
const writable = ref(false);
const blockReason = ref("");

const gateText = computed(() => (writable.value ? "" : blockReason.value));

/**
 * 门禁三段判断，顺序与服务端一致：有没有绑定服务者（40400）→ 状态是不是正常 → 有没有有效资质。
 * 三段都要给出**具体原因**：一句「无权限」服务者不知道该去补材料还是等审核。
 */
async function loadAccess(): Promise<void> {
  try {
    profile.value = await providerApp.getProfile();
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null;
    writable.value = false;
    blockReason.value =
      apiError?.code === 40400
        ? "这个账号还没有关联的服务者：先去「设置」提交入驻申请。"
        : `暂时读不到门店状态${apiError?.message ? `（${apiError.message}）` : ""}，选品与提案先按不可用处理。`;
    return;
  }

  const current = profile.value;
  if (current?.status !== 1) {
    writable.value = false;
    blockReason.value = `门店状态为「${providerStatusLabel(current?.status)}」：审核通过后才能选品、提案与上下架。`;
    return;
  }

  try {
    const page = await providerApp.listApplications({ page: 1, pageSize: 1 });
    const latest = page.list[0];
    const detail = latest ? await providerApp.getApplication(latest.id) : null;
    qualifications.value = detail?.qualifications ?? [];
  } catch {
    // 读不到材料就按「不知道」处理：不在这里拦人（后端会拦），但也不声称资质有效
    qualifications.value = [];
  }

  if (hasNoValidQualification(qualifications.value)) {
    writable.value = false;
    blockReason.value = "当前没有有效资质（过期或被驳回）：去「设置 → 资质材料」补交，之后即可选品与上架。";
    return;
  }

  writable.value = true;
  blockReason.value = "";
}

async function loadCategories(): Promise<void> {
  categoriesLoading.value = true;
  categoriesError.value = "";
  try {
    categories.value = await providerApp.listCategories();
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null;
    const failure = apiError?.message ?? "标准目录分类加载失败，请稍后重试";
    categoriesError.value = apiError?.requestId ? `${failure}（请求 ID：${apiError.requestId}）` : failure;
  } finally {
    categoriesLoading.value = false;
  }
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value !== "authenticated") return;
  void loadAccess();
  void loadCategories();
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">服务标准</h2>
    <p class="ph-page-desc">
      平台标准目录（分类 → 项目 → 价格区间）与我的选品：从目录里勾项目、在区间内定价，提交后经运营审核上架；目录里没有的服务要提案，审核通过后才由平台建项。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <p v-if="gateText" class="ph-alert ph-alert--warn ph-standard__gate">{{ gateText }}</p>

      <nav class="ph-tabs" aria-label="服务标准分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'catalog' }"
          @click="tab = 'catalog'"
        >
          标准目录
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'services' }"
          @click="tab = 'services'"
        >
          我的服务项
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'proposals' }"
          @click="tab = 'proposals'"
        >
          目录外提案
        </button>
      </nav>

      <!-- 分类是三个分段的公共输入：加载失败在这里说一次，别在每个分段各报一次 -->
      <p v-if="categoriesError" class="ph-alert ph-alert--error ph-standard__gate">
        {{ categoriesError }}
        <button type="button" class="ph-table__action ph-standard__retry" @click="loadCategories">重新加载分类</button>
      </p>
      <div v-else-if="categoriesLoading" class="ph-card ph-standard__gate">
        <ConsoleState variant="loading" title="正在加载标准目录分类" />
      </div>

      <CatalogPanel
        v-if="tab === 'catalog' && !categoriesLoading"
        :categories="categories"
        :writable="writable"
        :block-reason="blockReason"
        @created="tab = 'services'"
        @goto-proposals="tab = 'proposals'"
      />
      <MyServicesPanel
        v-else-if="tab === 'services'"
        :writable="writable"
        :block-reason="blockReason"
        @changed="loadAccess"
      />
      <ProposalPanel
        v-else-if="tab === 'proposals'"
        :categories="categories"
        :writable="writable"
        :block-reason="blockReason"
      />

      <section class="ph-card ph-standard__note">
        <h3 class="ph-card__title">服务标准执行情况（接宠检查 / 三道照片墙）</h3>
        <p class="ph-text-sub">
          待实现：这一段看的是接宠检查、服务防护、取宠对比三段照片的完成率（缺一道系统不允许报工），
          数据源在订单履约与技师开工链路上，接口还没进契约（ADR-0040 / ADR-0037）。这里先不摆数字——
          假的完成率比空着更容易被当成真的。
        </p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-standard__gate {
  margin-bottom: var(--ph-space-4);
}

.ph-standard__retry {
  margin-left: var(--ph-space-3);
}

.ph-standard__note {
  margin-top: var(--ph-space-6);
}
</style>
