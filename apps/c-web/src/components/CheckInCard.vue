<script setup lang="ts">
/**
 * 今日健康任务（打卡）卡片，切片 #97，规则见 ADR-0018。
 *
 * 桌面形态的两个关键取舍：
 *  1. **行内展开录入**，不弹层——弹层在桌面上是多余的一次交互；
 *  2. 给「全部正常」与「和昨天一样」两个一键操作——六项点完仍在 3 秒量级（F005 的要求）。
 *
 * 任一项提交即算当日已打卡，所以进度显示 n/6，但完成标记看 `done`。
 * 组件只负责交互与呈现；发请求、刷新评分都是页面的活（emit 出去）。
 */
import { computed, ref } from "vue";
import type { CheckInDay, CheckInItem, CheckInItemInput } from "@pet-health/shared";

const props = defineProps<{
  day: CheckInDay | null;
  streakDays: number;
  loading?: boolean;
  saving?: boolean;
  /** 昨天各项的取值，用于「和昨天一样」；没有则为空。 */
  yesterdayValues?: Record<number, { value?: string; note?: string }>;
}>();

const emit = defineEmits<{
  submit: [items: CheckInItemInput[]];
  undo: [category: number];
}>();

/** 展开录入的行（一次只开一行，避免桌面上一片输入框）。 */
const expandedCategory = ref<number | null>(null);
const draft = ref<{ abnormal: boolean; value: string; note: string }>({
  abnormal: false,
  value: "",
  note: "",
});

const items = computed<CheckInItem[]>(() => props.day?.items ?? []);
const progressText = computed(() =>
  props.day ? `${props.day.completed_count}/${props.day.total_count}` : "—",
);

function toggle(item: CheckInItem): void {
  if (expandedCategory.value === item.category) {
    expandedCategory.value = null;
    return;
  }
  expandedCategory.value = item.category;
  draft.value = {
    abnormal: item.abnormal,
    value: item.value ?? (item.category === 1 ? "" : "normal"),
    note: item.note ?? "",
  };
}

function submitRow(category: number): void {
  const raw = draft.value.value.trim();
  // 「正常」只是下拉的默认选项，不是用户填的内容：勾了异常还把它当值传上去，
  // 库里就会存下「异常 + normal」这种自相矛盾的记录（界面上出现过「异常 · normal」）
  const value = draft.value.abnormal && raw === "normal" ? "" : raw;
  emit("submit", [
    {
      category: category as 1 | 2 | 3 | 4 | 5 | 6,
      abnormal: draft.value.abnormal,
      value: value || undefined,
      note: draft.value.note.trim() || undefined,
    },
  ]);
  expandedCategory.value = null;
}

function submitAllNormal(): void {
  emit(
    "submit",
    items.value.map((item) => ({
      category: item.category as 1 | 2 | 3 | 4 | 5 | 6,
      abnormal: false,
      // 体重没有「正常」这种取值，留空让用户之后补；其余给 normal
      value: item.category === 1 ? item.value ?? undefined : "normal",
    })),
  );
}

/** 「和昨天一样」：把昨天的取值原样提交到今天（没有昨天的数据就不显示这个按钮）。 */
const hasYesterday = computed(() => Object.keys(props.yesterdayValues ?? {}).length > 0);

function submitSameAsYesterday(): void {
  const previous = props.yesterdayValues ?? {};
  const filled = Object.entries(previous).map(([category, item]) => ({
    category: Number(category) as 1 | 2 | 3 | 4 | 5 | 6,
    abnormal: false,
    value: item.value,
    note: item.note,
  }));
  if (filled.length > 0) {
    emit("submit", filled);
  }
}

/** 结构化取值的显示文案。契约里 `value` 是**线值**（normal / low / high），
 *  直接渲染会把英文枚举摆到用户面前（切片 #97 的缺陷：点了「全部正常」后界面显示 `normal`）。 */
const VALUE_LABELS: Record<string, string> = {
  normal: "正常",
  low: "偏少",
  high: "偏多",
};

function statusText(item: CheckInItem): string {
  if (!item.filled) return "未记录";
  if (item.abnormal) {
    // 异常项要让人一眼看到「哪里不对」——优先显示备注，没有备注就只说异常
    return item.note ? `异常 · ${item.note}` : "已标注异常";
  }
  if (!item.value) return "已记录";
  // 体重这类自由输入的值原样显示（用户自己填的 8.25 不需要翻译）
  return VALUE_LABELS[item.value] ?? item.value;
}
</script>

<template>
  <article class="ph-card">
    <div class="ph-checkin__head">
      <h3 class="ph-card__title">今日健康任务</h3>
      <span class="ph-checkin__meta">
        <span class="ph-text-sub">{{ progressText }}</span>
        <span v-if="props.streakDays > 0" class="ph-checkin__streak">连续 {{ props.streakDays }} 天</span>
      </span>
    </div>

    <p v-if="props.loading" class="ph-text-sub">加载中…</p>

    <template v-else-if="props.day">
      <p v-if="props.day.done && props.day.completed_count < props.day.total_count" class="ph-text-sub ph-checkin__note">
        今天已打卡（记一项就算完成，剩下的随时补）。
      </p>

      <ul class="ph-checkin__list">
        <li v-for="item in items" :key="item.category" class="ph-checkin__row">
          <button type="button" class="ph-checkin__line" @click="toggle(item)">
            <span class="ph-checkin__mark" :class="{ 'ph-checkin__mark--done': item.filled }">
              {{ item.filled ? "✓" : "○" }}
            </span>
            <span class="ph-checkin__name">{{ item.name }}</span>
            <span class="ph-checkin__value" :class="{ 'ph-text-weak': !item.filled, 'ph-checkin__value--abnormal': item.abnormal }">
              {{ statusText(item) }}
            </span>
            <span v-if="item.backfilled" class="ph-checkin__tag">补录</span>
          </button>

          <!-- 行内展开：桌面上一行输入比弹层快 -->
          <div v-if="expandedCategory === item.category" class="ph-checkin__editor">
            <input
              v-if="item.category === 1"
              v-model="draft.value"
              class="ph-field__input ph-checkin__input"
              inputmode="decimal"
              placeholder="体重 kg，例如 12.50"
            />
            <label class="ph-checkin__switch">
              <input v-model="draft.abnormal" type="checkbox" />
              <span>这次不太正常</span>
            </label>
            <input
              v-model="draft.note"
              class="ph-field__input ph-checkin__input"
              maxlength="200"
              :placeholder="draft.abnormal ? '说明一下情况（例如：吃得很少）' : '备注（可选）'"
            />
            <div class="ph-checkin__actions">
              <button type="button" class="ph-button ph-button--primary" :disabled="props.saving" @click="submitRow(item.category)">
                保存
              </button>
              <button
                v-if="item.filled"
                type="button"
                class="ph-button ph-button--text"
                :disabled="props.saving"
                @click="emit('undo', item.category)"
              >
                撤销这一项
              </button>
              <button type="button" class="ph-button ph-button--secondary" @click="expandedCategory = null">取消</button>
            </div>
          </div>
        </li>
      </ul>

      <div class="ph-checkin__bulk">
        <button type="button" class="ph-button ph-button--secondary" :disabled="props.saving" @click="submitAllNormal">
          全部正常
        </button>
        <button
          v-if="hasYesterday"
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="props.saving"
          @click="submitSameAsYesterday"
        >
          和昨天一样
        </button>
      </div>
    </template>

    <p v-else class="ph-text-sub">还没有宠物档案，先建档才能打卡。</p>
  </article>
</template>

<style scoped>
.ph-checkin__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-checkin__head .ph-card__title {
  margin-bottom: 0;
}

.ph-checkin__meta {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
}

.ph-checkin__streak {
  padding: 2px var(--ph-space-2);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary);
  font-size: 12px;
}

.ph-checkin__note {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
}

.ph-checkin__list {
  list-style: none;
  margin: var(--ph-space-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.ph-checkin__row {
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-checkin__row:last-child {
  border-bottom: 0;
}

.ph-checkin__line {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  padding: var(--ph-space-3) 0;
  background: transparent;
  border: 0;
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
  text-align: left;
  cursor: pointer;
}

.ph-checkin__line:hover .ph-checkin__name {
  color: var(--ph-color-primary);
}

.ph-checkin__mark {
  width: 18px;
  color: var(--ph-color-text-weak);
}

.ph-checkin__mark--done {
  color: var(--ph-color-primary);
}

.ph-checkin__name {
  flex: 1;
}

.ph-checkin__value--abnormal {
  color: var(--ph-color-warning);
}

.ph-checkin__tag {
  padding: 1px var(--ph-space-2);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-checkin__editor {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
  padding: 0 0 var(--ph-space-3);
}

.ph-checkin__input {
  width: 100%;
}

.ph-checkin__switch {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-checkin__actions {
  display: flex;
  gap: var(--ph-space-2);
}

.ph-checkin__bulk {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-4);
}
</style>
