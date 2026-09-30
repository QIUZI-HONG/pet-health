<script setup lang="ts">
/**
 * 标准目录浏览与选品定价：**分类 → 项目 → 价格区间**，勾一个项目填价即可提交。
 *
 * 契约给的三条硬约束，界面上都要看得见：
 * - **区间来自平台**（`price_min` / `price_max`，与名称一起返回）：所以列表里直接显示区间，
 *   选品时把区间写在输入框旁边，越界当场给出「价格须在¥X-¥Y之间」——与后端 90001 的 message
 *   逐字一致（ADR-0034），而不是让用户提交后才知道边界在哪（交付文档 2.5 的验收标准）。
 * - **不能自由建项**：目录里没有的服务要去「目录外提案」提交申请，运营审核通过后平台才建正式目录项。
 * - **停用的分类与项目对服务者等于不存在**：接口只给启用的，所以这一页不需要「已停用」筛选。
 *
 * 越界判断在前端用整数分做（`utils/money.ts`），**不碰浮点**；后端仍会再判一次（90001），
 * 两边的结论必须一致——前端这一层只是为了让用户少等一个往返。
 */
import { computed, ref } from "vue";
import { ConsoleListState } from "@pet-health/ui";
import { providerApp, type ServiceItemView, type ServiceCategoryView } from "../api/providerApi";
import { usePagedList, useSubmitAction } from "@pet-health/ui";
import { applicablePetsLabel, formatAmountRange, isPriceFormatValid, isWithinRange, priceRangeMessage } from "@pet-health/shared";

const props = defineProps<{
  categories: ServiceCategoryView[];
  /** 能不能新增选品：要求入驻审核通过、未冻结、且有一份没过期的资质（契约里写明 40300） */
  writable: boolean;
  /** 不能选的原因（页面直接说清楚，别让按钮沉默地禁用） */
  blockReason: string;
}>();

const emit = defineEmits<{ created: []; gotoProposals: [] }>();

const categoryCode = ref("");
const keyword = ref("");

const items = usePagedList<ServiceItemView>(
  ({ page, pageSize }, signal) =>
    providerApp.listCatalogItems(
      { categoryCode: categoryCode.value || undefined, keyword: keyword.value.trim() || undefined, page, pageSize },
      signal,
    ),
  { failureText: "标准目录加载失败，请稍后重试" },
);

/** 正在定价的那个目录项 */
const selected = ref<ServiceItemView | null>(null);
const price = ref("");
const submit = useSubmitAction("提交失败，请稍后重试");

function selectCategory(code: string): void {
  categoryCode.value = code;
  void items.reload();
}

function search(): void {
  void items.reload();
}

function openPrice(item: ServiceItemView): void {
  submit.clear();
  selected.value = item;
  price.value = item.price_min ?? "";
}

function closePrice(): void {
  selected.value = null;
}

/** 区间校验的结果：`null` 表示还没填够，无法判断 */
const rangeVerdict = computed(() => {
  if (!selected.value) return null;
  if (!isPriceFormatValid(price.value)) return null;
  return isWithinRange(price.value, selected.value.price_min, selected.value.price_max);
});

const priceHint = computed(() => {
  const item = selected.value;
  if (!item) return "";
  // 越界那句与后端 90001 的 message 逐字一致（ADR-0034）：两边各拼一遍会让人以为是两条规则
  const range = priceRangeMessage(item.price_min, item.price_max);
  if (price.value.trim() === "") return `${range}（单位：${item.price_unit ?? "次"}）`;
  if (!isPriceFormatValid(price.value)) return "价格要大于 0，最多两位小数";
  if (rangeVerdict.value === false) return range;
  return "";
});

const priceInvalid = computed(() => priceHint.value !== "" && price.value.trim() !== "");

async function confirmCreate(): Promise<void> {
  const item = selected.value;
  if (!item || !item.code) return;
  if (priceHint.value !== "") {
    submit.errorMessage.value = priceHint.value;
    return;
  }
  const outcome = await submit.run(
    () => providerApp.createService({ service_code: item.code!, price: price.value.trim() }),
    "已提交审核：运营通过后即上架",
  );
  if (!outcome.ok) return;
  selected.value = null;
  price.value = "";
  emit("created");
}
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <button
        type="button"
        class="ph-tabs__item"
        :class="{ 'ph-tabs__item--active': categoryCode === '' }"
        @click="selectCategory('')"
      >
        全部分类
      </button>
      <button
        v-for="category in props.categories"
        :key="category.code ?? category.name"
        type="button"
        class="ph-tabs__item"
        :class="{ 'ph-tabs__item--active': categoryCode === (category.code ?? '') }"
        @click="selectCategory(category.code ?? '')"
      >
        {{ category.name }}（{{ category.item_count ?? 0 }}）
      </button>
      <span class="ph-toolbar__spacer" />
      <input
        v-model="keyword"
        class="ph-input ph-catalog__search"
        maxlength="64"
        placeholder="按项目名称搜索"
        @keyup.enter="search"
      />
      <button type="button" class="ph-button ph-button--secondary" @click="search">搜索</button>
    </div>

    <p v-if="!props.writable && props.blockReason" class="ph-alert ph-alert--warn ph-catalog__alert">
      {{ props.blockReason }}
    </p>

    <ConsoleListState
      :loading="items.loading.value"
      :forbidden="items.forbidden.value"
      :error-message="items.errorMessage.value"
      :request-id="items.requestId.value"
      :is-empty="items.isEmpty.value"
      loading-title="正在加载标准目录"
      forbidden-title="暂无权限"
      forbidden-description="这个账号的令牌不能读取标准目录。"
      empty-title="这个分类下还没有可选项"
      empty-description="目录由平台维护：需要目录里没有的服务时，去「目录外提案」提交申请。"
      @retry="items.reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>编码</th>
              <th>项目</th>
              <th>分类</th>
              <th>价格区间</th>
              <th>单位</th>
              <th>时长</th>
              <th>适用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items.items.value" :key="item.code ?? item.id">
              <td class="ph-table__num">{{ item.code ?? "—" }}</td>
              <td>
                {{ item.name ?? "—" }}
                <span v-if="item.description" class="ph-text-weak ph-catalog__desc">{{ item.description }}</span>
              </td>
              <td>{{ item.category_name ?? "—" }}</td>
              <td class="ph-table__num">{{ formatAmountRange(item.price_min, item.price_max) }}</td>
              <td>{{ item.price_unit ?? "—" }}</td>
              <td>{{ item.duration_minutes ? `${item.duration_minutes} 分钟` : "—" }}</td>
              <td>{{ applicablePetsLabel(item.applicable_pets) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="!props.writable"
                    :title="props.writable ? '' : props.blockReason"
                    @click="openPrice(item)"
                  >
                    选品定价
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ items.total.value }} 项</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="items.page.value <= 1 || items.loading.value"
          @click="items.prevPage"
        >
          上一页
        </button>
        <span>第 {{ items.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!items.hasMore.value || items.loading.value"
          @click="items.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="selected" class="ph-card ph-catalog__form" @submit.prevent="confirmCreate">
      <h4 class="ph-card__title">选品定价：{{ selected.name }}</h4>
      <dl class="ph-kv">
        <dt>目录项编码</dt>
        <dd class="ph-table__num">{{ selected.code }}</dd>
        <dt>平台区间</dt>
        <dd class="ph-table__num">{{ formatAmountRange(selected.price_min, selected.price_max) }}</dd>
        <dt>计价单位</dt>
        <dd>{{ selected.price_unit ?? "次" }}</dd>
      </dl>

      <label class="ph-field ph-catalog__price">
        <span class="ph-field__label">你的定价（元）</span>
        <input
          v-model="price"
          class="ph-input"
          :class="{ 'ph-input--invalid': priceInvalid }"
          inputmode="decimal"
          placeholder="如 128.00"
        />
        <span class="ph-field__hint" :class="{ 'ph-field__hint--error': priceInvalid }">
          {{ priceHint || priceRangeMessage(selected.price_min, selected.price_max) }}
        </span>
      </label>

      <p class="ph-field__hint">提交后进入待审核，运营通过即上架；同一目录项不能重复提交（会提示已在服务列表里）。</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <div class="ph-catalog__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "提交审核" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="closePrice">取消</button>
        <button type="button" class="ph-button ph-button--secondary" @click="emit('gotoProposals')">
          目录里没有？去提案
        </button>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-catalog__alert">{{ submit.doneMessage.value }}</p>
  </div>
</template>

<style scoped>
.ph-catalog__search {
  width: 220px;
}

.ph-catalog__desc {
  display: block;
  font-size: 12px;
}

.ph-catalog__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-catalog__form {
  margin-top: var(--ph-space-5);
}

.ph-catalog__price {
  max-width: 320px;
  margin-top: var(--ph-space-4);
}

.ph-catalog__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
