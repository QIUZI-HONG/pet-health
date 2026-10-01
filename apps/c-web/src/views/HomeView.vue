<script setup lang="ts">
/**
 * 首页：多栏布局（ADR-0016）——主栏放「评分 + 快捷服务 + 今日任务」，次栏放「需要你关注」。
 *
 * 打卡与评分是这一页真正活着的两块（切片 #97）：
 *  - 打卡：行内展开录入、一键「全部正常」「和昨天一样」，任一项即算当日打卡（ADR-0018）；
 *  - 评分：五维 + 趋势，未计入的维度如实标「待录入 / 未开启」，没有数据时不给 0 分。
 * 提交后立刻重新拉评分——F005 要求打卡「有反馈」。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  toUserMessage,
  cApp,
  createLatestGuard,
  formatDate,
  shiftDate,
  speciesLabel,
  type CheckInDay,
  type CheckInItemRequest,
  type HealthScoreView,
  type MessageView, riskTone } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import { useMessageStore } from "../stores/messages";
import { onInviteNotice, takeInviteNotice } from "../utils/invite";
import { reminderTypeLabel, reminderTypeTone } from "../utils/reminder";
import { commerce, type CouponView } from "../api/commerce";
import CheckInCard from "../components/CheckInCard.vue";
import HealthScoreCard from "../components/HealthScoreCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";

const session = useSessionStore();
const messageStore = useMessageStore();

const pet = computed(() => session.activePet);

/**
 * 可补录天数。**与后端的 `CheckInService.BACKFILL_WINDOW_DAYS` 是同一口径**（今天 + 过去 7 天）：
 * 前端只提前拦一道（别让用户选到会被后端拒的日期），真正的闸门仍在服务端。
 * 改这里就要同时改后端那个常量——两处不一致的表现是「日期选择器放行的日期被后端拒」。
 */
const CHECKIN_BACKFILL_DAYS = 7;

const score = ref<HealthScoreView | null>(null);
const today = ref<CheckInDay | null>(null);
const yesterday = ref<CheckInDay | null>(null);
const streakDays = ref(0);
/**
 * 补录（F005 的第二半）：卡片上可以换日期，范围是后端的可补录窗口（今天 + 过去 7 天）。
 * 空串 = 今天（不带 `date` 参数，由服务端按 Asia/Shanghai 定「今天」）。
 */
const checkInDate = ref("");
/**
 * **服务端的今天**（业务日期）。窗口的 `max` 用它而不是浏览器本地的今天：
 * 用户机器时区不对时，本地今天可能比业务日期早一天，那会让「今天」这个选项被自己拒掉。
 */
const checkInToday = ref("");
/** 可补录窗口：[今天 - 7 天, 今天]，与 `CheckInService.BACKFILL_WINDOW_DAYS` 同一口径。 */
const checkInMinDate = computed(() => (checkInToday.value ? shiftDate(checkInToday.value, -CHECKIN_BACKFILL_DAYS) : ""));
/**
 * 券提醒条与邀请入口条（交付文档 4.16.2 的第 6、7 块）。
 *
 * 两块都用**已有的接口**取数，不新增契约：券包（`/coupons?status=1`）与邀请中心（`/invites/center`）。
 * 取不到就不显示那一块（首页是「看一眼今天要做什么」的地方，一块取不到不该让整页报错）。
 */
const expiringCoupons = ref<CouponView[]>([]);
const inviteProgress = ref<{ effective: number; nextThreshold: number | null; nextRemaining: number | null } | null>(null);

/** 券提醒条只说实话：张数按服务端的券包算，天数按**最近到期**那张算。 */
const couponHint = computed(() => {
  const coupons = expiringCoupons.value;
  if (coupons.length === 0) {
    return null;
  }
  const soonest = coupons
    .map((coupon) => ({ coupon, days: daysUntil(coupon.valid_until) }))
    .filter((item) => item.days !== null)
    .sort((a, b) => (a.days ?? 0) - (b.days ?? 0))[0];
  return {
    count: coupons.length,
    days: soonest?.days ?? null,
    name: soonest?.coupon.template_name ?? "券",
  };
});

/** 到期还有几天（按业务日算整天的差；服务端给的 `valid_until` 是本地时间字符串）。 */
function daysUntil(validUntil: string | null | undefined): number | null {
  if (!validUntil) return null;
  const target = Date.parse(validUntil.replace(" ", "T"));
  if (Number.isNaN(target)) return null;
  return Math.max(0, Math.ceil((target - Date.now()) / 86_400_000));
}

async function loadHighlights(): Promise<void> {
  if (!session.isLoggedIn) return;
  const [coupons, invites] = await Promise.allSettled([
    commerce.listCoupons({ status: 1, pageSize: 100 }),
    commerce.getInviteCenter(),
  ]);
  if (coupons.status === "fulfilled") {
    expiringCoupons.value = coupons.value.list ?? [];
  }
  if (invites.status === "fulfilled") {
    inviteProgress.value = {
      effective: invites.value.effective_count ?? 0,
      nextThreshold: invites.value.next_threshold ?? null,
      nextRemaining: invites.value.next_remaining ?? null,
    };
  }
}

/** 首页强提醒流：未读的健康提醒，按风险等级与指向时间排序（交付文档 4.16.2 画的 6 张卡）。 */
const reminders = ref<MessageView[]>([]);
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");

/**
 * 注册归因的结果提示（ADR-0039 第一节：归因只有注册那一刻一次机会）。
 *
 * **为什么显示在首页**：那一次调用发生在注册页、且**故意不 await**（旁路调用不能把跳转压在
 * 一次网络往返上），所以它返回的那句话到达时注册页已经卸载了。首页是注册后的落点，
 * 两头都接：已经到达的取走（`takeInviteNotice`），还没到的订阅（`onInviteNotice`）——
 * 只接一头就会有时序上的漏（调用比挂载快或慢都发生过）。
 *
 * 这句话**来自服务端**（契约的 `data.notice`，见 utils/invite.ts）：反作弊判据的细节不外露
 * （ADR-0046 第三节），所以文案不是这里拼的，这里只负责展示。
 */
const inviteNotice = ref("");
let unsubscribeInviteNotice: () => void = () => {};

onMounted(() => {
  void loadHighlights();
  inviteNotice.value = takeInviteNotice();
  unsubscribeInviteNotice = onInviteNotice((notice) => {
    inviteNotice.value = notice;
  });
});

onBeforeUnmount(() => {
  unsubscribeInviteNotice();
});

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
  // 标签用页面自己的名字（「AI 管家」与侧边栏、路由标题、该页 h1 一致）。
  // 原先这里写「AI 问诊」，同一个目的地两个名字，用户与搜索都对不上（评审提的）
  { label: "AI 管家", hint: "描述症状，拿到风险分级", to: "/ai", icon: "💬" },
  { label: "去打卡", hint: "每天 3 秒记录", to: "/", icon: "✅" },
  { label: "找服务", hint: "医院 / 洗护 / 寄养", to: "/services", icon: "🩺" },
  // 稿子（4.16.2 快捷服务）里还有「找洗护 / 找训犬 / 上门喂养」三个直达入口：
  // 它们是服务页的分类深链（`?type=` 由服务页自己读），点进来就是筛好的那一类
  { label: "找洗护", hint: "洗护与美容", to: "/services?type=2", icon: "🛁" },
  { label: "找训犬", hint: "基础训练与矫正", to: "/services?type=3", icon: "🐕" },
  { label: "上门喂养", hint: "寄养与上门", to: "/services?type=4", icon: "🏠" },
  { label: "看档案", hint: "疫苗与就医记录", to: "/records", icon: "📋" },
  // 社区（切片 #84 / F020）：ADR-0041 定的是「嵌在首页与档案页的侧栏，不做独立 Tab」，
  // 所以它的入口在这里，而不是左栏——不动导航的信息架构
  { label: "社区", hint: "经验卡片与问答", to: "/community", icon: "🐾" },
  // 知识库（F024 / F025）：与社区同一情形（不是主页面，进不了左栏），入口一样放这里
  { label: "知识库", hint: "疫苗、分诊与常见病", to: "/knowledge", icon: "📚" },
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
      // 空串 = 今天：不带 date 参数，让服务端按业务时区定「今天」
      cApp.getCheckInDay(petId, checkInDate.value || undefined, signal),
      cApp.getCheckInStreak(petId, signal),
      cApp.getMessageHighlights(6, signal),
    ]);
    if (!latest.isCurrent(seq)) return;
    score.value = scoreData;
    today.value = dayData;
    // 只在「看今天」时记录业务日期：补录别的日期时，那一天的 date 不是今天
    if (!checkInDate.value) checkInToday.value = dayData.date;
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
    errorMessage.value = toUserMessage(error, "加载失败，请稍后重试");
  } finally {
    if (latest.isCurrent(seq)) {
      loading.value = false;
    }
  }
}

async function submitCheckIn(items: CheckInItemRequest[]): Promise<void> {
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
    errorMessage.value = toUserMessage(error, "打卡失败，请稍后重试");
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
    errorMessage.value = toUserMessage(error, "撤销失败，请稍后重试");
  } finally {
    saving.value = false;
  }
}

/**
 * 换一天打卡（补录）：重拉那一整页——打卡状态跟着日期走，而「和昨天一样」用的是
 * **被看的那一天的前一天**（load 里按 `dayData.date` 减一天），所以它会跟着换。
 */
function changeCheckInDate(date: string): void {
  if (!pet.value || date === checkInDate.value || !date) return;
  checkInDate.value = date;
  void load(pet.value.id);
}

// 宠物切换后整页数据跟着换（多宠家庭）；**补录的日期要一起回到今天**，
// 否则新宠物的标题下会显示上一只宠物那一天的打卡状态
watch(
  () => pet.value?.id,
  (petId) => {
    checkInDate.value = "";
    checkInToday.value = "";
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
      <!-- 注册时填了邀请码的那句话（服务端给的文案，见 inviteNotice）：只展示一次，用户可关掉 -->
      <p v-if="inviteNotice" class="ph-home__notice">
        {{ inviteNotice }}
        <button type="button" class="ph-home__notice-close" aria-label="知道了" @click="inviteNotice = ''">
          ×
        </button>
      </p>

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
            :min-date="checkInMinDate"
            :max-date="checkInToday"
            @submit="submitCheckIn"
            @undo="undoCheckIn"
            @change-date="changeCheckInDate"
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
                  :class="`ph-reminders__bar--${riskTone(message.risk_level)}`"
                  aria-hidden="true"
                />
                <div class="ph-reminders__body">
                  <p class="ph-reminders__head">
                    <span class="ph-reminders__pill" :class="`ph-reminders__pill--${reminderTypeTone(message.type)}`">
                      {{ reminderTypeLabel(message.type) }}
                    </span>
                    <span class="ph-reminders__title">{{ message.title }}</span>
                  </p>
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

          <!-- 券提醒条（交付文档 4.16.2 第 6 块）：暖橙浅底，只说实话——几张、几天后过期 -->
          <article v-if="couponHint" class="ph-bar ph-bar--coupon">
            <span class="ph-bar__icon" aria-hidden="true">🎟️</span>
            <div class="ph-bar__body">
              <p class="ph-bar__title">
                您有 {{ couponHint.count }} 张券{{ couponHint.days !== null ? `，${couponHint.name} ${couponHint.days} 天后过期` : "" }}
              </p>
              <p class="ph-text-weak">券是到店抵扣凭证：到店出示给门店核销，平台不经手资金（ADR-0036）。</p>
            </div>
            <RouterLink class="ph-bar__action" :to="{ name: 'coupons' }">去使用 →</RouterLink>
          </article>

          <!-- 邀请入口条（同第 7 块）：主色浅底，进度按服务端给的有效邀请数算 -->
          <article v-if="inviteProgress" class="ph-bar ph-bar--invite">
            <span class="ph-bar__icon" aria-hidden="true">👥</span>
            <div class="ph-bar__body">
              <p class="ph-bar__title">邀请好友，双方各得洗护券</p>
              <p class="ph-text-weak">
                已有效邀请 {{ inviteProgress.effective }} 人{{
                  inviteProgress.nextThreshold !== null && inviteProgress.nextRemaining !== null
                    ? `；再邀 ${inviteProgress.nextRemaining} 人到 ${inviteProgress.nextThreshold} 人档`
                    : ""
                }}
              </p>
            </div>
            <RouterLink class="ph-bar__action" :to="{ name: 'invites' }">立即邀请 →</RouterLink>
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

/* 注册归因的那句话：与错误条同一形状、换成主色（它是「已记录」的结果，不是失败） */
.ph-home__notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  margin: 0 0 var(--ph-space-4);
  padding: var(--ph-space-3) var(--ph-space-4);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  color: var(--ph-color-primary-dark);
  font-size: 13px;
}

.ph-home__notice-close {
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
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

/* 分类 pill（4.16.2 的彩色分类标签）：与风险色条分工不同——色条说「多紧急」，pill 说「是哪一类」 */
.ph-reminders__head {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  margin: 0;
}

.ph-reminders__pill {
  flex: none;
  padding: 1px var(--ph-space-2);
  border-radius: 999px;
  font-size: 12px;
  line-height: 18px;
}

/* 色调与 4.16.10 的用色纪律一致：红=异常，橙=券/疫苗这类待办，绿=按期的驱虫，紫=长期跟踪，蓝=信息 */
.ph-reminders__pill--danger {
  background: var(--ph-color-danger-light);
  color: var(--ph-color-danger);
}

.ph-reminders__pill--warning {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-reminders__pill--success {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-reminders__pill--purple {
  background: var(--ph-color-purple-light);
  color: var(--ph-color-purple);
}

.ph-reminders__pill--info {
  background: var(--ph-color-bg);
  color: var(--ph-color-blue);
}

.ph-reminders__pill--neutral {
  background: var(--ph-color-bg);
  color: var(--ph-color-text-sub);
}

/* 券提醒条（暖橙浅底）与邀请入口条（主色浅底）：4.16.2 的第 6、7 块 */
.ph-bar {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  padding: var(--ph-space-4);
  border-radius: var(--ph-radius-card);
  border: 1px solid var(--ph-color-border);
  /* 首页次栏只有约 1/3 宽（ADR-0016 的两栏）：放不下时让动作换到下一行并靠右，
     而不是把标题挤成「30 天后过\n期」这种断法 */
  flex-wrap: wrap;
}

.ph-bar--coupon {
  background: var(--ph-color-orange-light);
}

.ph-bar--invite {
  background: var(--ph-color-primary-light);
}

.ph-bar__icon {
  font-size: 20px;
}

.ph-bar__body {
  /* 占满一行：首页次栏只有约 1/3 宽，标题与动作挤在一行会把文案断成「30 天后过\n期」。
     让动作换到下一行（右对齐），条内读起来才是「一句话 + 一个动作」。 */
  flex: 1 1 100%;
  min-width: 0;
}

.ph-bar__title {
  margin: 0;
  font-weight: 600;
}

.ph-bar__action {
  flex: none;
  margin-left: auto;
  color: var(--ph-color-orange);
  font-weight: 500;
  text-decoration: none;
}

.ph-bar--invite .ph-bar__action {
  color: var(--ph-color-primary);
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
