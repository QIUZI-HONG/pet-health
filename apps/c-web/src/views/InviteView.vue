<script setup lang="ts">
/**
 * 邀请有礼（切片 #111；决策见 ADR-0039 第一节 / ADR-0046）。
 *
 * 邀请有两个入口（契约写死）：**分享链接**（带 `?invite=CODE`，落地页记住 7 天）与
 * **注册表单手工填码**。归因只在**注册那一刻**发生——注册之后没有任何补填出口，因为补填就是
 * 刷券的入口（这条口径在接口层就没有出口）。所以这一页要把「注册时填」这件事说清楚。
 *
 * 进度按**有效邀请数**算：被邀请人完成建档**且** 24 小时内有行为才算有效（ADR-0046 第二节）。
 * 按注册数算的话，同一批刷出来的号当晚就能把阶梯奖领走。阶梯 1 / 3 / 5 / 10 / 15 也按有效数计。
 *
 * 奖励物没配的档位**照记达成**（`reward_desc` 为空）：界面照实说「该档未配奖励」，
 * 而不是把它显示成没达成（ADR-0046 第五节）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { createLatestGuard, formatDate, newIdempotencyKey, toApiFailure } from "@pet-health/shared";
import { commerce, type InviteCenterView } from "../api/commerce";
import { useSessionStore } from "../stores/session";
import { startVisiblePolling } from "../utils/poll";
import { deviceId } from "../utils/invite";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";
import { useSubmitAction } from "@pet-health/ui";

const session = useSessionStore();
const center = ref<InviteCenterView | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const requestId = ref("");
const copied = ref(false);
const latest = createLatestGuard();
const createAction = useSubmitAction("生成邀请码失败，请稍后重试");

/** 主展示的码：最新的一条（列表按生成时间倒序）。 */
const primaryCode = computed(() => (center.value?.codes ?? [])[0] ?? null);

/** 完整分享链接：契约给的 `invite_path` 是相对路径，域名由前端拼（ADR-0039 第一节）。 */
const shareLink = computed(() => {
  const path = primaryCode.value?.invite_path;
  if (!path) return "";
  return `${window.location.origin}${path}`;
});

async function load(silent = false): Promise<void> {
  const { token, signal } = latest.claim();
  // 轮询走 silent：不进加载态（否则每 30 秒整页闪一次骨架），失败也不显示错误条——
  // 后台刷新失败不该把用户正在看的页面变成错误态，下一个周期还会再试
  if (!silent) {
    loading.value = true;
    errorMessage.value = "";
    requestId.value = "";
  }
  try {
    const result = await commerce.getInviteCenter(signal);
    if (!latest.isCurrent(token)) return;
    center.value = result;
    if (silent) {
      errorMessage.value = "";
      requestId.value = "";
    }
  } catch (error) {
    if (!latest.isCurrent(token)) return;
    if (silent) return;
    const failure = toApiFailure(error, "加载邀请信息失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (!silent && latest.isCurrent(token)) {
      loading.value = false;
    }
  }
}

/**
 * 轮询兜底（ADR-0040 第三节）：邀请进度是「别人做了什么」的数据，页面上没有推送通道，
 * 只能靠定时刷新。**页面不可见时停表**（实现见 utils/poll）。
 */
let stopPolling: () => void = () => {};

onMounted(() => {
  // 未登录时不发：这一页的主体在闸门后面，轮询只会每 30 秒换回一个 40100
  stopPolling = startVisiblePolling(() => {
    if (session.isLoggedIn) void load(true);
  });
});

onBeforeUnmount(() => {
  stopPolling();
});

watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load();
  },
  { immediate: true },
);

/**
 * 生成一条新码。**不删旧码**：旧码已经分享出去了，删掉等于让那些链接失效（契约）。
 *
 * 带上本机设备号：它是 `SAME_DEVICE` 那一层反作弊的**比较基准**（注册设备与建码设备相同时拦下）。
 * 存的是本机生成一次、之后一直用同一个值（与注册归因用的是同一个 `deviceId()`），
 * 所以「换账号再自己注册」不会绕过这一层——要绕过得换设备。
 */
async function createCode(): Promise<void> {
  const result = await createAction.run(
    () => commerce.createInviteCode({ channel: 1, device_id: deviceId() }, newIdempotencyKey()),
    "邀请码已生成",
  );
  if (result.ok) await load();
}

async function copyLink(): Promise<void> {
  if (!shareLink.value) return;
  try {
    await navigator.clipboard.writeText(shareLink.value);
    copied.value = true;
    window.setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch (error) {
    // 剪贴板不可用（非 HTTPS、浏览器策略）不算失败：下面把链接明文摆着，让用户自己复制
    console.warn("[invite] 写入剪贴板失败，请手动复制链接", error);
    copied.value = false;
  }
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">邀请有礼</h2>
    <p class="ph-page-desc">
      好友通过你的链接或邀请码注册，并在 24 小时内完成建档与行为，就算一次有效邀请；阶梯奖励按有效数算。
    </p>

    <SessionGate forbidden-description="登录后查看你的邀请码与进度。">
      <StateLoading v-if="loading" :rows="3" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />

      <div v-else-if="center" class="ph-stack">
        <!-- 还没有码：先引导生成，而不是给一个空卡片 -->
        <article v-if="!primaryCode" class="ph-card">
          <StateEmpty
            icon="🎉"
            title="还没有邀请码"
            description="生成一条就能分享给好友。邀请码不删旧码：重新生成不会让你已经发出去的链接失效。"
          >
            <button
              type="button"
              class="ph-button ph-button--primary"
              :disabled="createAction.submitting.value"
              @click="createCode"
            >
              {{ createAction.submitting.value ? "生成中…" : "生成我的邀请码" }}
            </button>
          </StateEmpty>
        </article>

        <div v-else class="ph-columns">
          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">我的邀请码</h3>
              <p class="ph-invite__code">{{ primaryCode.code }}</p>
              <p class="ph-text-sub ph-invite__link">{{ shareLink }}</p>

              <div class="ph-form__actions">
                <button type="button" class="ph-button ph-button--primary" @click="copyLink">
                  {{ copied ? "已复制" : "复制邀请链接" }}
                </button>
                <button
                  type="button"
                  class="ph-button ph-button--secondary"
                  :disabled="createAction.submitting.value"
                  @click="createCode"
                >
                  再生成一条
                </button>
              </div>

              <p class="ph-invite__note">
                <strong>邀请码要在注册时填写</strong>：归因只发生在注册那一刻，注册完成后无法补填——
                这是为了防止「事后补码」被用来刷奖励。分享链接会带上邀请码，落地页会记住 7 天。
              </p>
              <p v-if="createAction.errorMessage.value" class="ph-form__error">
                {{ createAction.errorMessage.value }}
                <span v-if="createAction.requestId.value" class="ph-text-weak">
                  （请求 ID：{{ createAction.requestId.value }}）
                </span>
              </p>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">阶梯进度</h3>
              <StateEmpty
                v-if="(center.ladder ?? []).length === 0"
                icon="🪜"
                title="还没有阶梯"
                description="运营配置阶梯门槛与奖励后会出现在这里。"
              />
              <ul v-else class="ph-invite__ladder">
                <li
                  v-for="tier in center.ladder ?? []"
                  :key="tier.threshold"
                  class="ph-invite__tier"
                  :class="{ 'ph-invite__tier--done': tier.achieved }"
                >
                  <span class="ph-invite__tier-threshold">{{ tier.threshold }} 人</span>
                  <span class="ph-invite__tier-reward">
                    {{ tier.reward_desc ?? "该档未配奖励（达成照记）" }}
                  </span>
                  <span class="ph-text-weak">
                    {{ tier.achieved ? `已达成 ${formatDate(tier.achieved_at)}` : "未达成" }}
                  </span>
                </li>
              </ul>

              <p v-if="center.next_threshold" class="ph-invite__next">
                还差 <strong>{{ center.next_remaining ?? 0 }}</strong> 个有效邀请到 {{ center.next_threshold }} 人档。
              </p>
              <p v-else class="ph-invite__next">五档全部达成。</p>
            </article>
          </div>

          <div class="ph-stack">
            <article class="ph-card">
              <h3 class="ph-card__title">邀请进度</h3>
              <dl class="ph-invite__counts">
                <div class="ph-invite__count">
                  <dt>有效邀请</dt>
                  <dd class="ph-invite__count-value">{{ center.effective_count ?? 0 }}</dd>
                </div>
                <div class="ph-invite__count"><dt>已注册</dt><dd>{{ center.registered_count ?? 0 }}</dd></div>
                <div class="ph-invite__count"><dt>待生效</dt><dd>{{ center.pending_count ?? 0 }}</dd></div>
                <div class="ph-invite__count"><dt>无效</dt><dd>{{ center.invalid_count ?? 0 }}</dd></div>
              </dl>
              <p class="ph-invite__note">
                有效邀请 = 被邀请人完成建档，且在 24 小时内有过行为。阶梯与积分都按有效数算，
                不按注册数——否则刷出来的号当晚就能把奖励领走。
              </p>
            </article>

            <article class="ph-card">
              <h3 class="ph-card__title">奖励是什么</h3>
              <p class="ph-text-sub ph-invite__note">
                阶梯奖励由运营配置：可能是券，也可能是权益码。奖励物没配的档位<strong>达成记录照记</strong>，
                只是不发东西。
              </p>
              <RouterLink class="ph-button ph-button--text" :to="{ name: 'coupons' }">看看我的券</RouterLink>
              <RouterLink class="ph-button ph-button--text" :to="{ name: 'rights' }">看看我的权益</RouterLink>
            </article>
          </div>
        </div>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-invite__code {
  margin: 0;
  font-family: var(--ph-font-numeric);
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 4px;
  color: var(--ph-color-orange);
}

.ph-invite__link {
  margin: var(--ph-space-2) 0 var(--ph-space-3);
  font-size: 12px;
  word-break: break-all;
}

.ph-invite__note {
  margin: var(--ph-space-3) 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ph-color-text-sub);
}

.ph-invite__ladder {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-invite__tier {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  padding: var(--ph-space-2) var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
  font-size: 13px;
}

.ph-invite__tier--done {
  background: var(--ph-color-orange-light);
}

.ph-invite__tier-threshold {
  font-family: var(--ph-font-numeric);
  font-weight: 600;
  min-width: 48px;
}

.ph-invite__tier-reward {
  flex: 1;
  color: var(--ph-color-text-sub);
}

.ph-invite__next {
  margin: var(--ph-space-3) 0 0;
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-invite__counts {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-3);
}

.ph-invite__count {
  padding: var(--ph-space-3);
  background: var(--ph-color-bg);
  border-radius: var(--ph-radius-input);
}

.ph-invite__count dt {
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-invite__count dd {
  margin: var(--ph-space-1) 0 0;
  font-family: var(--ph-font-numeric);
  font-size: 20px;
  font-weight: 600;
}

.ph-invite__count-value {
  color: var(--ph-color-primary);
}
</style>
