<script setup lang="ts">
/**
 * 积分中心（切片 #113；决策见 ADR-0038 第四节 / ADR-0046）。
 *
 * 一页给出四块：**余额**、**任务进度**、**行为分值表**、**兑换档位**。三条口径写在页面上：
 *
 *  1. 积分是**另一套账**（与权益并列）：只有分值与余额，**不能兑换现金、不能提现**；
 *  2. 任务**本身不额外发分**——`points` 是「完成该行为本来就有的分」，进度按行为流水聚合；
 *  3. 兑换**只兑平台补贴券**（成本归平台，不消耗服务者的贡献额度）。
 *
 * 兑换是这一页唯一的写操作：扣分与发券在服务端同一个事务里，余额不足整笔不发（40900）。
 * **幂等键必须带**（契约点名）：一次兑换没有天然的业务引用，不带键的重复提交会把分扣两次。
 *
 * **积分流水在这一页不展示**：契约的 `/points` 只给了余额与任务，没有流水列表接口——
 * 所以这里写清「待接口」，而不是画一个假列表（不许伪造能力）。
 */
import { computed, ref, watch } from "vue";
import { createLatestGuard, newIdempotencyKey, toApiFailure } from "@pet-health/shared";
import { commerce, type CouponView, type PointExchangeOptionView, type PointTaskProgressView, type PointsCenterView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import CouponCard from "../components/CouponCard.vue";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { formatAmount } from "../utils/money";
import { useSubmitAction } from "@pet-health/ui";

const session = useSessionStore();
const center = ref<PointsCenterView | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const latest = createLatestGuard();

const exchangeAction = useSubmitAction("兑换失败，请稍后重试");
/** 兑换成功后展示的那张券（服务端在同一次调用里发出来的）与档位名。 */
const issuedCoupon = ref<CouponView | null>(null);
const exchangedOptionName = ref("");

/** 每日任务在前、每周在后；同档内按 sort_order（服务端已经排好，这里只分组展示）。 */
const dailyTasks = computed(() => (center.value?.tasks ?? []).filter((task: PointTaskProgressView) => task.period === 1));
const weeklyTasks = computed(() => (center.value?.tasks ?? []).filter((task: PointTaskProgressView) => task.period === 2));
const balance = computed(() => center.value?.balance ?? 0);
const dailyLimit = computed(() => center.value?.daily_earn_limit ?? 0);
const todayEarned = computed(() => center.value?.today_earned ?? 0);

async function load(): Promise<void> {
  const { token, signal } = latest.claim();
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  try {
    const result = await commerce.getPoints(signal);
    if (!latest.isCurrent(token)) return;
    center.value = result;
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    const failure = toApiFailure(error, "加载积分中心失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load();
  },
  { immediate: true },
);

/** 分数不够时按钮就是灰的（服务端仍会再判一次 40900——前端这一层只是少一次无用往返）。 */
function affordable(option: PointExchangeOptionView): boolean {
  return option.status === 1 && balance.value >= (option.points_cost ?? 0);
}

/**
 * 今天的签到是不是已完成：**看服务端的任务进度**，前端不另记状态。
 *
 * 任务进度按行为流水聚合（ADR-0046 第七节），所以「签到过没有」这件事在服务端只有一个答案；
 * 前端自己再存一个 localStorage 标记，就会在换设备、清缓存之后与事实不一致。
 */
const signedInToday = computed(() => (center.value?.tasks ?? [])
  .some((task) => task.code === "DAILY_SIGN_IN" && task.completed === true));

const signInAction = useSubmitAction("签到失败，请稍后重试");
const signInNotice = ref("");

/**
 * 签到。**不需要幂等键**：契约里「今天已经签过」是 200 + `awarded=false` 的正常结果，
 * 所以这里不必防重放（`useSubmitAction` 的 2 秒闸门只是为了少几次无用往返）。
 */
async function signIn(): Promise<void> {
  const result = await signInAction.run(() => commerce.signIn());
  if (!result.ok) return;
  signInNotice.value = result.value.notice ?? "";
  if (center.value && typeof result.value.balance === "number") {
    center.value = { ...center.value, balance: result.value.balance };
  }
  // 任务进度与「今日已获得」都变了，整页重拉一次（与兑换后的做法一致）
  await load();
}

async function exchange(option: PointExchangeOptionView): Promise<void> {
  const optionId = option.id;
  if (!optionId) return;
  const result = await exchangeAction.run(
    () => commerce.exchangePoints(optionId, newIdempotencyKey()),
    "兑换成功",
  );
  if (!result.ok) return;
  exchangedOptionName.value = option.name ?? "券";
  issuedCoupon.value = result.value.coupon ?? null;
  // 余额以服务端返回的为准（`balance_after`），随后再整体刷一次（任务进度也可能变了）
  if (center.value) {
    center.value = { ...center.value, balance: result.value.balance_after ?? center.value.balance };
  }
  await load();
}

function taskProgress(task: PointTaskProgressView): number {
  const target = task.target_count ?? 0;
  if (target <= 0) return 0;
  return Math.min(100, Math.round(((task.current_count ?? 0) / target) * 100));
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">积分中心</h2>
    <p class="ph-page-desc">积分只能兑换券，不能兑换现金、不能提现。兑换到的是平台补贴券。</p>

    <SessionGate forbidden-description="登录后查看你的积分与兑换。">
      <StateLoading v-if="loading" :rows="4" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />

      <div v-else-if="center" class="ph-stack">
        <div class="ph-columns">
          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">积分余额</h3>
              <p class="ph-points__balance">
                <span class="ph-points__value">{{ balance }}</span>
                <span class="ph-text-sub">分</span>
              </p>
              <p class="ph-text-sub ph-points__cap">
                今日已获得 {{ todayEarned }} 分，每日上限 {{ dailyLimit }} 分。
                邀请与一次性项不占这个上限——否则一次 20 分的邀请会把当天额度吃光。
              </p>
              <div class="ph-points__signin">
                <button
                  class="ph-btn ph-btn--primary"
                  type="button"
                  :disabled="signInAction.submitting.value || signedInToday"
                  @click="signIn"
                >
                  {{ signedInToday ? "今天已签到" : "签到" }}
                </button>
                <span v-if="signInAction.errorMessage.value" class="ph-form__error">
                  {{ signInAction.errorMessage.value }}
                </span>
                <span v-else class="ph-text-sub">
                  {{ signInNotice || "每天可签到一次，分值由运营配置" }}
                </span>
              </div>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">兑换档位</h3>
              <p class="ph-card__note ph-text-sub">
                兑换只兑平台补贴券：成本归平台，不消耗服务者的贡献额度。
              </p>

              <StateEmpty
                v-if="(center.exchange_options ?? []).length === 0"
                icon="🎁"
                title="暂无可兑换档位"
                description="运营配置好兑换档位后会出现在这里。"
              />

              <ul v-else class="ph-points__options">
                <li v-for="option in center.exchange_options ?? []" :key="option.id" class="ph-points__option">
                  <div>
                    <p class="ph-points__option-name">{{ option.name ?? "兑换档位" }}</p>
                    <p class="ph-text-sub ph-points__option-meta">
                      {{ option.points_cost ?? 0 }} 分 → {{ option.coupon_template_name ?? "券" }}
                      （面额 {{ formatAmount(option.coupon_face_value) }}）
                    </p>
                  </div>
                  <button
                    type="button"
                    class="ph-button ph-button--primary"
                    :disabled="!affordable(option) || exchangeAction.submitting.value"
                    @click="exchange(option)"
                  >
                    {{
                      option.status !== 1
                        ? "已停用"
                        : affordable(option)
                          ? exchangeAction.submitting.value
                            ? "兑换中…"
                            : "兑换"
                          : "积分不足"
                    }}
                  </button>
                </li>
              </ul>

              <p v-if="exchangeAction.errorMessage.value" class="ph-form__error">
                {{ exchangeAction.errorMessage.value }}
                <span v-if="exchangeAction.requestId.value" class="ph-text-weak">
                  （请求 ID：{{ exchangeAction.requestId.value }}）
                </span>
              </p>

              <div v-if="issuedCoupon" class="ph-points__issued">
                <p class="ph-text-sub">已兑换「{{ exchangedOptionName }}」，券已经放进你的券包：</p>
                <CouponCard :coupon="issuedCoupon" />
                <RouterLink class="ph-button ph-button--text" :to="{ name: 'coupons' }">去我的券看看</RouterLink>
              </div>
            </article>
          </div>

          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">任务进度</h3>
              <p class="ph-card__note ph-text-sub">
                任务本身不额外发分：进度按行为流水聚合，分是「完成该行为本来就有的分」。
              </p>

              <StateEmpty
                v-if="(center.tasks ?? []).length === 0"
                icon="✅"
                title="当前没有任务"
                description="运营配置任务后会出现在这里。"
              />

              <template v-else>
                <h4 class="ph-points__group">每日任务</h4>
                <ul class="ph-points__tasks">
                  <li v-for="task in dailyTasks" :key="task.id" class="ph-points__task">
                    <div class="ph-points__task-head">
                      <span>{{ task.name ?? task.code }}</span>
                      <span class="ph-text-weak">
                        {{ task.current_count ?? 0 }} / {{ task.target_count ?? 0 }}
                        {{ task.completed ? "· 已达标" : "" }}
                      </span>
                    </div>
                    <div class="ph-points__bar"><span :style="{ width: `${taskProgress(task)}%` }" /></div>
                  </li>
                </ul>

                <h4 class="ph-points__group">每周任务</h4>
                <ul class="ph-points__tasks">
                  <li v-for="task in weeklyTasks" :key="task.id" class="ph-points__task">
                    <div class="ph-points__task-head">
                      <span>{{ task.name ?? task.code }}</span>
                      <span class="ph-text-weak">
                        {{ task.current_count ?? 0 }} / {{ task.target_count ?? 0 }}
                        {{ task.completed ? "· 已达标" : "" }}
                      </span>
                    </div>
                    <div class="ph-points__bar"><span :style="{ width: `${taskProgress(task)}%` }" /></div>
                  </li>
                </ul>
              </template>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">行为分值</h3>
              <table class="ph-points__table">
                <thead>
                  <tr>
                    <th>行为</th>
                    <th>分值</th>
                    <th>频次</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="behavior in center.behaviors ?? []" :key="behavior.code">
                    <td>{{ behavior.name ?? behavior.code }}</td>
                    <td>{{ behavior.points ?? 0 }} 分</td>
                    <td class="ph-text-sub">
                      {{
                        behavior.once_only === 1
                          ? "一次性"
                          : [
                              behavior.daily_count_limit ? `每日 ${behavior.daily_count_limit} 次` : "",
                              behavior.monthly_count_limit ? `每月 ${behavior.monthly_count_limit} 次` : "",
                            ].filter(Boolean).join(" · ") || "不限次"
                      }}
                      {{ behavior.counts_toward_daily_cap === 0 ? "· 不占日上限" : "" }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">积分流水</h3>
              <p class="ph-text-sub">
                待接口：C 端目前只提供余额与任务进度，<strong>还没有积分流水的查询接口</strong>，
                所以这里不列假数据。明细可以在积分变化后重新打开这一页核对余额。
              </p>
            </article>
          </div>
        </div>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-points__signin {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-3);
  flex-wrap: wrap;
}

.ph-points__balance {
  display: flex;
  align-items: baseline;
  gap: var(--ph-space-2);
  margin: 0;
}

.ph-points__value {
  font-family: var(--ph-font-numeric);
  font-size: 36px;
  font-weight: 600;
  color: var(--ph-color-purple);
}

.ph-points__cap {
  margin: var(--ph-space-2) 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ph-points__options,
.ph-points__tasks {
  list-style: none;
  margin: 0 0 var(--ph-space-3);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-points__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding: var(--ph-space-3);
  background: var(--ph-color-purple-light);
  border-radius: var(--ph-radius-input);
}

.ph-points__option-name {
  margin: 0;
  font-weight: 600;
}

.ph-points__option-meta {
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-points__issued {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-points__group {
  margin: var(--ph-space-3) 0 var(--ph-space-2);
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-points__task-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ph-space-3);
  font-size: 13px;
}

.ph-points__bar {
  height: 6px;
  margin-top: var(--ph-space-2);
  background: var(--ph-color-divider);
  border-radius: 999px;
  overflow: hidden;
}

.ph-points__bar span {
  display: block;
  height: 100%;
  background: var(--ph-color-primary);
}

.ph-points__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.ph-points__table th,
.ph-points__table td {
  padding: var(--ph-space-2) 0;
  text-align: left;
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-points__table th {
  color: var(--ph-color-text-sub);
  font-weight: 500;
}
</style>
