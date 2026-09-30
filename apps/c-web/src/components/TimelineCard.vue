<script setup lang="ts">
/**
 * 健康时间轴（切片 #102，事件范围见 ADR-0030 第四条）。
 *
 * **只收四类事件**（本轮拍板）：就医记录、疫苗/驱虫、**异常**打卡、服务者报工。
 * 正常打卡刻意不进——一天最多 6 条日常记录会把时间轴淹成流水账，
 * 「回头找那次就医 / 那次异常」的检索价值就没了（那些在打卡卡与分项里看）。
 */
import { computed, ref, watch } from "vue";
import { cApp, formatDate, toApiFailure, type TimelineEventView } from "@pet-health/shared";
import StateEmpty from "./states/StateEmpty.vue";
import StateError from "./states/StateError.vue";
import StateLoading from "./states/StateLoading.vue";

const props = defineProps<{ petId: number }>();

const TYPES = [
  { value: "", label: "全部" },
  { value: "medical", label: "就医" },
  { value: "vaccine", label: "疫苗" },
  { value: "deworm", label: "驱虫" },
  { value: "abnormal", label: "异常" },
  { value: "provider", label: "服务" },
];

const events = ref<TimelineEventView[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 10;
const filter = ref("");
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");

const hasMore = computed(() => page.value * pageSize < total.value);

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const result = await cApp.listTimeline(props.petId, {
      page: page.value,
      pageSize,
      type: filter.value || undefined,
    });
    events.value = result.list;
    total.value = result.total;
  } catch (error) {
    const failure = toApiFailure(error, "时间轴加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

watch([() => props.petId, filter], () => {
  page.value = 1;
  void load();
}, { immediate: true });

function changePage(delta: number): void {
  page.value += delta;
  void load();
}

/** 事件类型的视觉标记（颜色全部来自 token；这里是 class 名，颜色在样式里取 var）。 */
function toneClass(event: TimelineEventView): string {
  return `ph-timeline__dot--${event.type}`;
}
</script>

<template>
  <article class="ph-card">
    <div class="ph-timeline__head">
      <h3 class="ph-card__title">时间轴</h3>
      <div class="ph-timeline__filters">
        <button
          v-for="type in TYPES"
          :key="type.value"
          type="button"
          class="ph-timeline__filter"
          :class="{ 'ph-timeline__filter--active': filter === type.value }"
          @click="filter = type.value"
        >
          {{ type.label }}
        </button>
      </div>
    </div>
    <p class="ph-text-sub ph-card__note">
      这里只放就医、疫苗驱虫、异常与服务记录；日常打卡在「今日任务」与分项里看。
    </p>

    <StateError v-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />
    <StateLoading v-else-if="loading" :rows="4" />
    <StateEmpty
      v-else-if="events.length === 0"
      icon="📋"
      title="还没有事件"
      description="就医、疫苗、异常打卡与服务记录都会汇到这条时间轴。"
    />

    <template v-else>
      <ol class="ph-timeline">
        <li v-for="event in events" :key="event.id" class="ph-timeline__item">
          <span class="ph-timeline__dot" :class="toneClass(event)" aria-hidden="true" />
          <div class="ph-timeline__body">
            <p class="ph-timeline__title">
              <span class="ph-timeline__type">{{ event.type_name }}</span>
              {{ event.title }}
            </p>
            <p class="ph-text-weak ph-timeline__meta">
              {{ formatDate(event.date) }} · {{ event.source_label }}
              <template v-if="event.backfilled"> · 补录</template>
            </p>
            <p v-if="event.summary" class="ph-text-sub ph-timeline__summary">{{ event.summary }}</p>
          </div>
        </li>
      </ol>

      <div class="ph-timeline__pager">
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="page <= 1"
          @click="changePage(-1)"
        >
          上一页
        </button>
        <span class="ph-text-weak">第 {{ page }} 页 · 共 {{ total }} 条</span>
        <button type="button" class="ph-button ph-button--secondary" :disabled="!hasMore" @click="changePage(1)">
          下一页
        </button>
      </div>
    </template>
  </article>
</template>

<style scoped>
.ph-timeline__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
  flex-wrap: wrap;
}

.ph-timeline__head .ph-card__title {
  margin-bottom: 0;
}

.ph-timeline__filters {
  display: flex;
  gap: var(--ph-space-2);
}

.ph-timeline__filter {
  padding: 2px var(--ph-space-3);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-surface);
  color: var(--ph-color-text-sub);
  font-size: 12px;
  cursor: pointer;
}

.ph-timeline__filter--active {
  border-color: var(--ph-color-primary);
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-4);
}

.ph-timeline__item {
  display: flex;
  gap: var(--ph-space-3);
}

.ph-timeline__dot {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  flex: none;
  border-radius: 50%;
  background: var(--ph-color-border);
}

.ph-timeline__dot--medical {
  background: var(--ph-color-blue);
}

.ph-timeline__dot--vaccine,
.ph-timeline__dot--deworm {
  background: var(--ph-color-primary);
}

.ph-timeline__dot--abnormal {
  background: var(--ph-color-danger);
}

.ph-timeline__dot--provider {
  background: var(--ph-color-purple);
}

.ph-timeline__body {
  min-width: 0;
}

.ph-timeline__title {
  margin: 0;
  font-size: 14px;
}

.ph-timeline__type {
  margin-right: var(--ph-space-2);
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-divider);
  color: var(--ph-color-text-sub);
  font-size: 12px;
}

.ph-timeline__meta {
  margin: 2px 0 0;
  font-size: 12px;
}

.ph-timeline__summary {
  margin: 2px 0 0;
  font-size: 13px;
}

.ph-timeline__pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
  font-size: 12px;
}
</style>
