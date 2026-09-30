<script setup lang="ts">
/**
 * 健康报告卡（切片 #115，决策见 ADR-0031）。
 *
 * 三条展示纪律：
 *
 *  1. **不写「AI 生成」**：报告由规则模板拼装、没调模型，界面统一叫「健康周报 / 健康月报」
 *     （ADR-0028：不许声称做了没做过的事）。
 *  2. **按段渲染，顺序由后端给**：四段结构是产品口径，不是每个端自己排的东西。
 *  3. **需要权益的段落照常展示，只打标签**：权益引擎未落地时拦截会把用户挡在一扇没有出口的门后面
 *     （ADR-0024 的第一条判断），所以这里只标「邀请解锁」。
 */
import { ref } from "vue";
import { cApp, formatDate, toApiFailure, type HealthReportView } from "@pet-health/shared";
import StateEmpty from "./states/StateEmpty.vue";
import StateError from "./states/StateError.vue";
import StateLoading from "./states/StateLoading.vue";

const props = defineProps<{ petId: number }>();

const reports = ref<HealthReportView[]>([]);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    // 读取会**惰性补齐**最近一期（幂等），所以第一次打开也有内容（ADR-0031 决定三）
    reports.value = (await cApp.listHealthReports(props.petId, { pageSize: 12 })).list;
  } catch (error) {
    const failure = toApiFailure(error, "报告加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    loading.value = false;
  }
}

void load();

defineExpose({ reload: load });
</script>

<template>
  <article class="ph-card">
    <h3 class="ph-card__title">健康报告</h3>
    <p class="ph-text-sub ph-card__note">
      每周一生成上周的周报、每月 1 日生成上月月报。数字来自你的记录与评分，不含 AI 生成内容。
    </p>

    <StateError v-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />
    <StateLoading v-else-if="loading" :rows="3" />
    <StateEmpty
      v-else-if="reports.length === 0"
      icon="📈"
      title="还没有报告"
      description="记录一周之后就会出第一份周报（周报覆盖上一个完整自然周）。"
    />

    <div v-else class="ph-reports">
      <section v-for="report in reports" :key="report.id" class="ph-report">
        <header class="ph-report__head">
          <span class="ph-report__name">{{ report.type_name }}</span>
          <span class="ph-text-weak">
            {{ formatDate(report.period_start) }} – {{ formatDate(report.period_end) }}
          </span>
          <span v-if="typeof report.total_score === 'number'" class="ph-report__score">
            {{ report.total_score }} 分<template v-if="report.grade"> · {{ report.grade }}</template>
          </span>
          <span v-else class="ph-text-weak">这一期没有评分</span>
        </header>

        <div v-for="section in report.payload.sections" :key="section.code" class="ph-report__section">
          <p class="ph-report__section-title">
            {{ section.name }}
            <span v-if="section.requires_privilege" class="ph-report__lock" :title="report.tier_note">
              邀请解锁
            </span>
          </p>
          <ul class="ph-report__lines">
            <li v-for="(line, index) in section.lines" :key="index">{{ line }}</li>
          </ul>
        </div>

        <p class="ph-report__notice">{{ report.payload.notice }}</p>
      </section>
    </div>
  </article>
</template>

<style scoped>
.ph-reports {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-5);
}

.ph-report + .ph-report {
  border-top: 1px solid var(--ph-color-divider);
  padding-top: var(--ph-space-5);
}

.ph-report__head {
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-3);
  flex-wrap: wrap;
  font-size: 13px;
}

.ph-report__name {
  font-weight: 600;
}

.ph-report__score {
  color: var(--ph-color-primary);
  font-family: var(--ph-font-numeric);
}

.ph-report__section {
  margin-top: var(--ph-space-3);
}

.ph-report__section-title {
  margin: 0 0 var(--ph-space-1);
  font-size: 13px;
  font-weight: 600;
}

.ph-report__lock {
  margin-left: var(--ph-space-2);
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
  font-size: 12px;
  font-weight: 500;
}

.ph-report__lines {
  margin: 0;
  padding-left: var(--ph-space-4);
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  line-height: 1.6;
}

.ph-report__notice {
  margin: var(--ph-space-3) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}
</style>
