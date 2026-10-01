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
 *
 * **补录（F005 的第二半）**：卡片顶部可以换日期，范围就是后端的可补录窗口
 * （今天 + 过去 7 天，`CheckInService.BACKFILL_WINDOW_DAYS`）。窗口两端由页面通过
 * `minDate` / `maxDate` 传进来（`maxDate` 用**服务端给的业务日期**，不用浏览器本地时区），
 * 越界的日期在这里被拦下、不发请求：后端也会拒（40001），但让用户走到「提交后才被拒」
 * 是白跑一趟。
 */
import { computed, ref } from "vue";
import { formatDate } from "@pet-health/shared";
import type { CheckInDay, CheckInItem, CheckInItemRequest } from "@pet-health/shared";

/**
 * 中文日期（`2026-09-30` → `2026年09月30日`）：交付文档 13.2 的展示口径。
 * 原生 `input[type=date]` 在浏览器里显示的是 `09/30/2026`，改不了它，所以补一行中文在旁边。
 */
const chineseDate = computed(() => {
  const raw = props.day?.date ?? "";
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw);
  return match ? `${match[1]}年${match[2]}月${match[3]}日` : raw;
});

const props = defineProps<{
  day: CheckInDay | null;
  streakDays: number;
  loading?: boolean;
  saving?: boolean;
  /** 昨天各项的取值，用于「和昨天一样」；没有则为空。 */
  yesterdayValues?: Record<number, { value?: string; note?: string }>;
  /** 可补录窗口的最早一天（`YYYY-MM-DD`）：后端只收今天 + 过去 7 天。 */
  minDate?: string;
  /** 窗口的最后一天 = 服务端的「今天」（业务日期，Asia/Shanghai）。 */
  maxDate?: string;
}>();

const emit = defineEmits<{
  submit: [items: CheckInItemRequest[]];
  undo: [category: number];
  /** 换一个业务日期（补录）：页面据此重新拉那一天的打卡状态。 */
  changeDate: [date: string];
}>();

/** 展开录入的行（一次只开一行，避免桌面上一片输入框）。 */
const expandedCategory = ref<number | null>(null);
const draft = ref<{ abnormal: boolean; value: string; note: string }>({
  abnormal: false,
  value: "",
  note: "",
});

/**
 * 体重这一项要挡住非法值。取值范围与建档一致（`0.01–999.99`，契约 `PetCreateRequest.weight`）。
 *
 * 为什么前端也要拦：后端这一项的 `value` 只当字符串存（`CheckInItemRequest.value`），
 * `abc` / `0` / `-5` 都收得下，会落进档案并参与体重趋势——首页会算出「体重下降了 2000080%」
 * 这种提醒（实测复现）。前端拦一道，用户至少当场知道哪里填错了。
 */
const weightInvalid = computed(() => {
  if (expandedCategory.value !== 1) return false;
  const raw = draft.value.value.trim();
  if (raw === "") return false; // 留空＝这一项先不记，允许
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) return true;
  const value = Number(raw);
  return value < 0.01 || value > 999.99;
});

const items = computed<CheckInItem[]>(() => props.day?.items ?? []);
const progressText = computed(() =>
  props.day ? `${props.day.completed_count}/${props.day.total_count}` : "—",
);

/**
 * 看的是不是今天：决定「补录」提示与下面那个按钮的叫法（见 template）。
 * 页面没给窗口（`maxDate` 缺失）时按「今天」处理——不因为拿不到窗口就把用户说成在补录。
 */
const viewingToday = computed(() => !props.maxDate || props.day?.date === props.maxDate);

/** 换日期时的越界提示；正常情况下是空串（日期输入的 min/max 先挡一道）。 */
const dateError = ref("");

/**
 * 换一个业务日期（补录）。**越界不发请求**：`min` / `max` 只是浏览器的提示，
 * 手输、粘贴、或将来换个控件都能绕过它，所以这里再判一次——
 * 送到后端才被拒（40001）用户已经白等一次往返，而且错误会显示在整页的错误条上。
 */
function changeDate(event: Event): void {
  const input = event.target as HTMLInputElement;
  const value = input.value;
  if (value === "") return;
  if (props.minDate && value < props.minDate) {
    dateError.value = `只能补录最近 7 天（${props.minDate} 起）的记录。`;
    input.value = props.day?.date ?? "";   // 回写：输入框停在一个不会被后端接受的日期上会误导用户
    return;
  }
  if (props.maxDate && value > props.maxDate) {
    dateError.value = "不能给将来的日期打卡。";
    input.value = props.day?.date ?? "";
    return;
  }
  dateError.value = "";
  if (value !== props.day?.date) {
    emit("changeDate", value);
  }
}

/** 展开 / 收起一行录入：一次只开一行；打开时用这一行现有取值初始化草稿，体重项不给默认值。 */
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

/** 单项提交（提完收起录入行）：非法体重不发（后端这一项不校验），值要洗掉「异常 + normal」组合。 */
function submitRow(category: number): void {
  if (category === 1 && weightInvalid.value) return;   // 非法体重不发出去（后端这一项不校验）
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

/** 一键「全部正常」：六项一次提交（F005 的 3 秒量级）；体重没有「正常」这种取值，留空等用户补。 */
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

/** 「和昨天一样」：把昨天的取值原样提交到今天；没有昨天数据时按钮不显示，这里是兜底不发空请求。 */
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

/** 行内状态文案：异常优先显示备注；线值按 VALUE_LABELS 翻译，用户自己填的数值原样显示。 */
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
      <!-- 补录入口：日期范围就是后端的可补录窗口（今天 + 过去 7 天）。回写与拦截见 changeDate -->
      <div class="ph-checkin__dates">
        <label class="ph-checkin__date">
          <span class="ph-field__label">日期</span>
          <input
            type="date"
            class="ph-field__input"
            :value="props.day.date"
            :min="props.minDate"
            :max="props.maxDate"
            aria-label="打卡日期"
            @change="changeDate"
          />
          <!-- 原生 date 输入在中文环境里显示成 09/30/2026；交付文档 13.2 要的是
               「YYYY年MM月DD日」，所以旁边补一行中文日期（输入框保持原生：它是能用的选择器） -->
          <span class="ph-text-weak ph-checkin__date-text">{{ chineseDate }}</span>
        </label>
        <span v-if="!viewingToday" class="ph-checkin__backfill">
          正在补录 {{ formatDate(props.day.date) }} 的记录（只能补最近 7 天）
        </span>
        <button
          v-if="!viewingToday && props.maxDate"
          type="button"
          class="ph-button ph-button--text"
          @click="emit('changeDate', props.maxDate ?? '')"
        >
          回到今天
        </button>
      </div>
      <p v-if="dateError" class="ph-field__hint">{{ dateError }}</p>

      <p v-if="props.day.done && props.day.completed_count < props.day.total_count" class="ph-text-sub ph-checkin__note">
        {{ viewingToday ? "今天已打卡（记一项就算完成，剩下的随时补）。" : "这一天已打卡（记一项就算完成，剩下的随时补）。" }}
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
              :class="{ 'ph-field__input--invalid': weightInvalid }"
              inputmode="decimal"
              maxlength="6"
              placeholder="体重 kg，例如 12.50"
            />
            <span v-if="weightInvalid" class="ph-field__hint">体重需为 0.01–999.99 之间的数字，最多两位小数。</span>
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
              <button type="button" class="ph-button ph-button--primary" :disabled="props.saving || weightInvalid" @click="submitRow(item.category)">
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
          {{ viewingToday ? "和昨天一样" : "和前一天一样" }}
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

/* 补录那一行：日期选择 + 一句「你在看哪一天」。它是卡片顶部的一条，不参与下面的逐项列表 */
.ph-checkin__dates {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-3);
  padding-bottom: var(--ph-space-3);
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-checkin__date {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-checkin__backfill {
  padding-bottom: var(--ph-space-2);
  font-size: 12px;
  color: var(--ph-color-orange);
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
