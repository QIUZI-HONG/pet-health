<script setup lang="ts">
/**
 * 首页：多栏布局（ADR-0016）——主栏放「评分 + 快捷服务 + 今日任务」，次栏放「需要你关注」。
 *
 * 打卡与评分是这一页真正活着的两块（切片 #97）：
 *  - 打卡：行内展开录入、一键「全部正常」「和昨天一样」，任一项即算当日打卡（ADR-0018）；
 *  - 评分：五维 + 趋势，未计入的维度如实标「待录入 / 未开启」，没有数据时不给 0 分。
 * 提交后立刻重新拉评分——F005 要求打卡「有反馈」。
 */
import { computed, ref, watch } from "vue";
import {
  ApiError,
  cApp,
  createLatestGuard,
  formatDate,
  shiftDate,
  speciesLabel,
  type CheckInDay,
  type CheckInItemInput,
  type HealthScore,
  type MessageView,
} from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import { useMessageStore } from "../stores/messages";
import CheckInCard from "../components/CheckInCard.vue";
import HealthScoreCard from "../components/HealthScoreCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";

const session = useSessionStore();
const messageStore = useMessageStore();

const pet = computed(() => session.activePet);

const score = ref<HealthScore | null>(null);
const today = ref<CheckInDay | null>(null);
const yesterday = ref<CheckInDay | null>(null);
const streakDays = ref(0);
/** 首页强提醒流：未读的健康提醒，按风险等级与指向时间排序（交付文档 4.16.2 画的 6 张卡）。 */
const reminders = ref<MessageView[]>([]);
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");

/** 「和昨天一样」用：昨天已填分项 → 取值。 */
const yesterdayValues = computed(() => {
  const map: Record<number, { value?: string; note?: string }> = {};
  for (const item of yesterday.value?.items ?? []) {
    if (item.filled) {
      map[item.category] = { value: item.value ?? undefined, note: item.note ?? undefined };
    }
  }
  return map;
});

const quickEntries = [
  { label: "AI 问诊", hint: "描述症状，拿到风险分级", to: "/ai", icon: "💬" },
  { label: "去打卡", hint: "每天 3 秒记录", to: "/", icon: "✅" },
  { label: "找服务", hint: "医院 / 洗护 / 寄养", to: "/services", icon: "🩺" },
  { label: "看档案", hint: "疫苗与就医记录", to: "/records", icon: "📋" },
];

function dayBefore(date: string): string {
  return shiftDate(date, -1);
}

/**
 * 并发守卫：多宠家庭切宠物时，先发的请求可能后回来，把新宠物的数据盖成旧宠物的。
 * 领号 + 落地前比对（统一实现见 shared 的 createLatestGuard）——
 * 评分/打卡显示错宠物的数据，比慢更糟。
 */
const latest = createLatestGuard();

async function load(petId: number): Promise<void> {
  const { token: seq, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  try {
    const [scoreData, dayData, streakData, remindersData] = await Promise.all([
      cApp.getHealthScore(petId, signal),
      cApp.getCheckInDay(petId, undefined, signal),
      cApp.getCheckInStreak(petId, signal),
      cApp.getMessageHighlights(6, signal),
    ]);
    if (!latest.isCurrent(seq)) return;
    score.value = scoreData;
    today.value = dayData;
    streakDays.value = streakData.streak_days;
    reminders.value = remindersData;
    // 这一页的强提醒流会**惰性补算**提醒，所以加载完要刷新一次未读角标——
    // 未读数接口本身不补算（角标每次切页都拉，不该写库），不刷新的话角标会停在旧值（踩过）
    await messageStore.refresh();
    // 昨天只为「和昨天一样」按钮服务；失败不影响主流程
    // 日期按字符串减一天：`shiftDate` 不走本地时区，否则东八区会取成前天的值（踩过）
    const previous = await cApp.getCheckInDay(petId, dayBefore(dayData.date), signal).catch(() => null);
    if (latest.isCurrent(seq)) {
      yesterday.value = previous;
    }
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    errorMessage.value = error instanceof ApiError ? error.message : "加载失败，请稍后重试";
  } finally {
    if (latest.isCurrent(seq)) {
      loading.value = false;
    }
  }
}

async function submitCheckIn(items: CheckInItemInput[]): Promise<void> {
  if (!pet.value || !today.value) return;
  saving.value = true;
  errorMessage.value = "";
  try {
    today.value = await cApp.submitCheckIn(pet.value.id, { date: today.value.date, items });
    // 打卡会重算评分、也可能生成异常提醒，所以三条都要刷新
    const [scoreData, streakData, remindersData] = await Promise.all([
      cApp.getHealthScore(pet.value.id),
      cApp.getCheckInStreak(pet.value.id),
      cApp.getMessageHighlights(6),
    ]);
    score.value = scoreData;
    streakDays.value = streakData.streak_days;
    reminders.value = remindersData;
    // 打卡可能即时生成异常提醒，角标要跟着动（否则铃铛还是旧的）
    await messageStore.refresh();
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "打卡失败，请稍后重试";
  } finally {
    saving.value = false;
  }
}

async function undoCheckIn(category: number): Promise<void> {
  if (!pet.value || !today.value) return;
  saving.value = true;
  try {
    today.value = await cApp.undoCheckIn(pet.value.id, today.value.date, category);
    score.value = await cApp.getHealthScore(pet.value.id);
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "撤销失败，请稍后重试";
  } finally {
    saving.value = false;
  }
}

// 宠物切换后整页数据跟着换（多宠家庭）
watch(
  () => pet.value?.id,
  (petId) => {
    if (petId) {
      void load(petId);
    }
  },
  { immediate: true },
);
</script>

<template>
  <section>
    <h2 class="ph-page-title">你好{{ session.user ? `，${session.user.nickname}` : "" }}</h2>
    <p class="ph-page-desc">24 小时看着这只小家伙的，是你和它一起。</p>

    <SessionGate forbidden-description="登录后就能看到健康评分、提醒和打卡任务。">
      <p v-if="errorMessage" class="ph-home__error">{{ errorMessage }}</p>

      <div class="ph-columns">
        <!-- 主栏 -->
        <div class="ph-stack">
          <HealthScoreCard :score="score" :loading="loading" :key="pet?.id ?? 0" />

          <CheckInCard
            :day="today"
            :streak-days="streakDays"
            :loading="loading"
            :saving="saving"
            :yesterday-values="yesterdayValues"
            @submit="submitCheckIn"
            @undo="undoCheckIn"
          />

          <article class="ph-card">
            <h3 class="ph-card__title">快捷服务</h3>
            <div class="ph-quick">
              <RouterLink v-for="entry in quickEntries" :key="entry.label" class="ph-quick__item" :to="entry.to">
                <span class="ph-quick__icon" aria-hidden="true">{{ entry.icon }}</span>
                <span class="ph-quick__label">{{ entry.label }}</span>
                <span class="ph-text-weak">{{ entry.hint }}</span>
              </RouterLink>
            </div>
          </article>
        </div>

        <!-- 次栏 -->
        <div class="ph-stack">
          <article class="ph-card">
            <h3 class="ph-card__title">需要你关注</h3>
            <ul v-if="reminders.length" class="ph-reminders">
              <li v-for="message in reminders" :key="message.id" class="ph-reminders__item">
                <span
                  class="ph-reminders__bar"
                  :class="{
                    'ph-reminders__bar--red': message.risk_level === 3,
                    'ph-reminders__bar--yellow': message.risk_level === 2,
                    'ph-reminders__bar--green': !message.risk_level || message.risk_level <= 1,
                  }"
                  aria-hidden="true"
                />
                <div class="ph-reminders__body">
                  <p class="ph-reminders__title">{{ message.title }}</p>
                  <p v-if="message.content" class="ph-reminders__content">{{ message.content }}</p>
                  <div class="ph-reminders__actions">
                    <RouterLink
                      v-if="message.action_hint && message.action_target"
                      class="ph-button ph-button--text"
                      :to="message.action_target"
                    >
                      {{ message.action_hint }}
                    </RouterLink>
                    <RouterLink class="ph-button ph-button--text" :to="{ name: 'messages' }">看全部消息</RouterLink>
                  </div>
                </div>
              </li>
            </ul>
            <StateEmpty
              v-else
              icon="🔔"
              title="暂时一切正常"
              description="疫苗到期、体重异常这类提醒会自动出现在这里。"
            />
          </article>

          <article class="ph-card">
            <h3 class="ph-card__title">我的宠物</h3>
            <ul v-if="session.pets.length" class="ph-pet-list">
              <li v-for="item in session.pets" :key="item.id" class="ph-pet-list__row">
                <span>{{ item.name }}</span>
                <span class="ph-text-weak">
                  {{ item.breed ?? speciesLabel(item.species) }}
                  <template v-if="item.birthday"> · {{ formatDate(item.birthday) }}</template>
                </span>
              </li>
            </ul>
            <StateEmpty v-else icon="🐾" title="还没有宠物" description="到「我的」里建第一份档案。" />
          </article>
        </div>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
/* 评分卡与打卡卡自己的样式在各自组件里（components/HealthScoreCard.vue、CheckInCard.vue）；
   这里只放首页自己的排布。 */
.ph-home__error {
  margin: 0 0 var(--ph-space-4);
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-danger-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-danger);
  font-size: 13px;
}

.ph-quick {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-3);
}

.ph-quick__item {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: auto auto;
  gap: 0 var(--ph-space-3);
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-card);
  color: var(--ph-color-text);
}

.ph-quick__item:hover {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-text);
}

.ph-quick__icon {
  grid-row: span 2;
  align-self: center;
  font-size: 18px;
}

.ph-quick__label {
  font-weight: 600;
}

.ph-reminders {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-reminders__item {
  display: flex;
  gap: var(--ph-space-3);
}

.ph-reminders__bar {
  width: 4px;
  flex: none;
  border-radius: 999px;
  background: var(--ph-color-border);
}

.ph-reminders__bar--red {
  background: var(--ph-color-danger);
}

.ph-reminders__bar--yellow {
  background: var(--ph-color-orange);
}

.ph-reminders__bar--green {
  background: var(--ph-color-primary);
}

.ph-reminders__body {
  min-width: 0;
}

.ph-reminders__title {
  margin: 0;
  font-weight: 600;
  font-size: 13px;
}

.ph-reminders__content {
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ph-color-text-sub);
}

.ph-reminders__actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-1);
}

.ph-pet-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-pet-list__row {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding-bottom: var(--ph-space-2);
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-pet-list__row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
</style>
