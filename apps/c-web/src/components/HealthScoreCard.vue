<script setup lang="ts">
/**
 * 健康评分卡（切片 #97）。
 *
 * 两条展示纪律（ADR-0018）：
 *  1. **没有数据时显示「还没有评分」，不显示 0 分**——0 分会被读成「健康状况极差」；
 *  2. **每一维都要能看到「为什么是这个分」**——分数低多半是因为记录不连续，
 *     用户不能因此以为宠物病了（所以 detail 一定要展示，不能只给一个数字）。
 *
 * 未计入的维度（防疫待录入、老年未开启）显示灰色与原因，不参与总分平均。
 */
import { computed } from "vue";
import type { HealthScoreView, HealthScoreDimension } from "@pet-health/shared";

const props = defineProps<{ score: HealthScoreView | null; loading?: boolean }>();

/**
 * 记录天数还少时给一句提醒。
 *
 * 为什么需要它：新用户刚把六项都打完，分数也只有 40 上下（完整度按 7 天算），
 * 大字容易被读成「宠物病了」——这正是 ADR-0018 要防的误读（低分多半是记录不足，不是健康差）。
 * 用趋势长度当「有记录的天数」的近似：评分一天一行。
 */
const recordedDays = computed(() => props.score?.trend?.length ?? 0);
const showLowDataHint = computed(
  () => typeof props.score?.total_score === "number" && recordedDays.value < 7,
);

/** 档位对应的圆环颜色：中性三档，不用「健康/优秀」这类医学化措辞。 */
function ringColor(grade: string): string {
  if (grade === "良好") return "var(--ph-color-primary)";
  if (grade === "尚可") return "var(--ph-color-warning)";
  if (grade === "暂无数据") return "var(--ph-color-border)";
  return "var(--ph-color-danger)";
}

function barColor(score: number): string {
  if (score >= 85) return "var(--ph-color-primary)";
  if (score >= 70) return "var(--ph-color-warning)";
  return "var(--ph-color-danger)";
}

function dimensionLabel(dimension: HealthScoreDimension): string {
  if (dimension.included && typeof dimension.score === "number") return `${dimension.score}`;
  return dimension.excluded_reason ?? "未计入";
}
</script>

<template>
  <article class="ph-card">
    <h3 class="ph-card__title">{{ props.score ? "健康评分" : "健康评分" }}</h3>

    <div v-if="props.loading" class="ph-score__loading">正在计算…</div>

    <!-- 没有数据：说清「怎么才有分」，而不是给 0 分。
         判空用 typeof 而不是 `=== null`：接口给的是 JSON null，但生成类型里字段是可选的，
         写成宽松判断免得 undefined 漏过去显示成 0 分。 -->
    <div v-else-if="!props.score || typeof props.score.total_score !== 'number'" class="ph-score__empty">
      <p class="ph-score__empty-title">还没有评分</p>
      <p class="ph-text-sub">记录几天健康任务（打卡）之后就会出现评分。评分看的是「记录是否持续」与「有没有异常」。</p>
    </div>

    <template v-else>
      <p v-if="showLowDataHint" class="ph-score__hint">
        记录天数还少（{{ recordedDays }} 天），分数偏低主要因为记录不连续——坚持记录会自己涨上来。
      </p>

      <div class="ph-score">
        <div class="ph-score__ring" :style="{ borderColor: ringColor(props.score.grade) }">
          <span class="ph-score__value">{{ props.score.total_score }}</span>
          <span class="ph-score__grade" :style="{ color: ringColor(props.score.grade) }">{{ props.score.grade }}</span>
        </div>
        <ul class="ph-score__dims">
          <li v-for="dimension in props.score.dimensions" :key="dimension.key" class="ph-score__dim">
            <div class="ph-score__dim-head">
              <span :class="{ 'ph-text-weak': !dimension.included }">{{ dimension.name }}</span>
              <span class="ph-score__dim-value" :class="{ 'ph-text-weak': !dimension.included }">
                {{ dimensionLabel(dimension) }}
              </span>
            </div>
            <div class="ph-score__bar" :title="dimension.detail ?? dimension.excluded_reason ?? ''">
              <div
                v-if="dimension.included && typeof dimension.score === 'number'"
                class="ph-score__bar-fill"
                :style="{ width: `${dimension.score}%`, background: barColor(dimension.score) }"
              />
            </div>
            <!-- 分数必须可解释：为什么低、为什么没计入，都写出来 -->
            <p v-if="dimension.detail" class="ph-score__detail">{{ dimension.detail }}</p>
          </li>
        </ul>
      </div>

      <p class="ph-score__disclaimer">{{ props.score.disclaimer }}</p>
    </template>
  </article>
</template>

<style scoped>
.ph-score__hint {
  margin: 0 0 var(--ph-space-4);
  padding: var(--ph-space-2) var(--ph-space-3);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-score {
  display: flex;
  align-items: center;
  gap: var(--ph-space-6);
}

.ph-score__ring {
  width: 96px;
  height: 96px;
  flex: none;
  display: grid;
  place-items: center;
  border: 6px solid var(--ph-color-border);
  border-radius: 50%;
}

.ph-score__value {
  font-family: var(--ph-font-numeric);
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
}

.ph-score__grade {
  margin-top: var(--ph-space-1);
  font-size: 12px;
}

.ph-score__dims {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-score__dim-head {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-score__dim-value {
  font-family: var(--ph-font-numeric);
}

.ph-score__bar {
  height: 6px;
  margin-top: var(--ph-space-1);
  background: var(--ph-color-divider);
  border-radius: 999px;
  overflow: hidden;
}

.ph-score__bar-fill {
  height: 100%;
  border-radius: 999px;
}

.ph-score__detail {
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-score__disclaimer {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}

.ph-score__empty {
  padding: var(--ph-space-4) 0;
}

.ph-score__empty-title {
  margin: 0 0 var(--ph-space-2);
  font-weight: 600;
}

.ph-score__loading {
  padding: var(--ph-space-6);
  color: var(--ph-color-text-sub);
}
</style>
