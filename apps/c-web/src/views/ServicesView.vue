<script setup lang="ts">
/**
 * 服务页：六个分类与入口。
 *
 * 供给侧（标准目录 #104、服务者选品 #105、订单 #109）都还没落地，所以这里是**结构先立住、
 * 数据如实空着**：分类按交付文档 4.x 的六类列出，点进分类给空态。
 */
import StateEmpty from "../components/states/StateEmpty.vue";
import StateForbidden from "../components/states/StateForbidden.vue";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();

const categories = [
  { key: "hospital", label: "医院", hint: "体检、疫苗、常见病诊疗" },
  { key: "grooming", label: "洗护美容", hint: "洗澡、造型、药浴" },
  { key: "training", label: "训犬", hint: "行为纠正、基础服从" },
  { key: "boarding", label: "寄养喂养", hint: "上门喂养、宠物寄养" },
  { key: "goods", label: "食品用品", hint: "主粮、零食、用品" },
  { key: "indirect", label: "间接服务", hint: "托运、殡葬等转介" },
];
</script>

<template>
  <section>
    <h2 class="ph-page-title">服务</h2>
    <p class="ph-page-desc">按分类找到标准服务项，明码标价，选时段预约。</p>

    <StateForbidden v-if="!session.isLoggedIn" description="预约需要绑定宠物与账号信息。" />

    <template v-else>
      <div class="ph-categories">
        <button v-for="category in categories" :key="category.key" type="button" class="ph-category" disabled>
          <span class="ph-category__label">{{ category.label }}</span>
          <span class="ph-category__hint">{{ category.hint }}</span>
          <span class="ph-category__soon">即将开放</span>
        </button>
      </div>

      <article class="ph-card ph-services__empty">
        <StateEmpty
          icon="🩺"
          title="服务目录还在路上"
          description="平台统一的标准服务目录与定价区间在 #75 决策、#104 实现；服务者选品定价在 #105，下单与核销在 #109。目录一上线，这里就换成可筛选的服务列表。"
        />
      </article>
    </template>
  </section>
</template>

<style scoped>
.ph-categories {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ph-space-3);
  margin-bottom: var(--ph-space-4);
}

.ph-category {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ph-space-1);
  padding: var(--ph-space-4);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-card);
  font-family: inherit;
  text-align: left;
  cursor: not-allowed;
}

.ph-category__label {
  font-size: 15px;
  font-weight: 600;
  color: var(--ph-color-text);
}

.ph-category__hint {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-category__soon {
  margin-top: var(--ph-space-2);
  padding: 2px var(--ph-space-2);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-services__empty {
  padding: 0;
}

@media (max-width: 1080px) {
  .ph-categories {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
