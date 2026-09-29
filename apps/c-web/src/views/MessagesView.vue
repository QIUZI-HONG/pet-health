<script setup lang="ts">
/**
 * 消息中心（切片 #99，决策见 ADR-0019）。
 *
 * 两件事在同一页：**消息列表**（健康提醒 + 业务通知，未读/已读）与**提醒开关**。
 * 桌面上左右分栏比「列表页 + 设置页」两个路由更省一次跳转。
 *
 * 口径提醒：本项目没有推送通道，所以页面上要说清「主动」的含义——系统主动**生成**，
 * 用户回到站内就能看到；不要写成「实时推送」（ADR-0019）。
 */
import { computed, ref, watch } from "vue";
import {
  ApiError,
  toUserMessage,
  cApp,
  createLatestGuard,
  formatDateTime,
  type MessageView,
  type ReminderSetting,
} from "@pet-health/shared";
import { useMessageStore } from "../stores/messages";
import { useSessionStore } from "../stores/session";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateError from "../components/states/StateError.vue";
import StateLoading from "../components/states/StateLoading.vue";

const messageStore = useMessageStore();
const session = useSessionStore();
const messages = ref<MessageView[]>([]);
const settings = ref<ReminderSetting[]>([]);
const loading = ref(true);
const saving = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const unreadOnly = ref(false);
const page = ref(1);
const hasMore = ref(false);

/**
 * 未读数以**服务端为准**（`unread-count`）：按当前这一页去数会在未读超过一页时少报。
 * store 里的值由 load() 与各种标记动作刷新。
 */
const unreadCount = computed(() => messageStore.unread);

/**
 * 并发守卫：快速切「只看未读」或连点「加载更多」时，先发的请求可能后回来。
 * 不拦的话会把上一轮的 page2 追加到重新过滤后的 page1 后面（重复 + 乱序），
 * 或者用旧筛选的结果盖掉新筛选的（统一实现见 shared 的 createLatestGuard）。
 */
const latest = createLatestGuard();

async function load(append = false): Promise<void> {
  const { token: seq, signal } = latest.claim();
  loading.value = !append;
  errorMessage.value = "";
  try {
    const current = append ? page.value + 1 : 1;
    const [result, settingList] = await Promise.all([
      cApp.listMessages({ unreadOnly: unreadOnly.value, page: current, pageSize: 20 }, signal),
      cApp.listReminderSettings(signal),
    ]);
    if (!latest.isCurrent(seq)) return;
    messages.value = append ? [...messages.value, ...result.list] : result.list;
    page.value = current;
    hasMore.value = result.has_more;
    settings.value = settingList;
    await messageStore.refresh();
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      requestId.value = error.requestId;
    } else {
      errorMessage.value = "加载失败，请稍后重试";
    }
  } finally {
    if (latest.isCurrent(seq)) {
      loading.value = false;
    }
  }
}

/**
 * 会话就绪后再拉数据。**不能无条件 onMounted(load)**：未登录时这一页的请求会拿回 40100，
 * 而错误态排在闸门前面，游客看到的就是「⚠️ 未登录（请求 ID）」，而不是「登录后查看 + 去登录」
 * （其它四个主页面都是闸门态，只有这一页不一样——已踩过）。
 */
watch(
  () => session.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void load();
  },
  { immediate: true },
);

async function markRead(message: MessageView): Promise<void> {
  if (message.read) return;
  try {
    const updated = await cApp.markMessageRead(message.id);
    messages.value = messages.value.map((item) => (item.id === updated.id ? updated : item));
    await messageStore.refresh();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "标记已读失败");
  }
}

/** 删除单条（软删除）。删掉一条不等于关闭该类型——要停某一类去右边的开关。 */
async function remove(message: MessageView): Promise<void> {
  try {
    await cApp.deleteMessage(message.id);
    messages.value = messages.value.filter((item) => item.id !== message.id);
    await messageStore.refresh();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "删除失败");
  }
}

async function markAllRead(): Promise<void> {
  saving.value = true;
  try {
    await cApp.markAllMessagesRead();
    await load();
    await messageStore.refresh();
  } catch (error) {
    errorMessage.value = toUserMessage(error, "操作失败");
  } finally {
    saving.value = false;
  }
}

async function toggleSetting(setting: ReminderSetting, event: Event): Promise<void> {
  if (!setting.closable) return;   // 不可关闭的那类，开关是灰的，点了也不该发请求
  const input = event.target as HTMLInputElement;
  saving.value = true;
  try {
    settings.value = await cApp.updateReminderSetting(setting.type, !setting.enabled);
  } catch (error) {
    // 失败要把复选框拨回去：绑定的是 `:checked`，模型没变时 Vue 不会重绘它，
    // 不回写就会停在与服务端不一致的位置（看着开着，其实没开）
    input.checked = setting.enabled;
    errorMessage.value = toUserMessage(error, "修改失败");
  } finally {
    saving.value = false;
  }
}

function riskClass(message: MessageView): string {
  if (message.risk_level === 3) return "ph-msg__risk--red";
  if (message.risk_level === 2) return "ph-msg__risk--yellow";
  return "ph-msg__risk--green";
}

function riskLabel(message: MessageView): string {
  if (message.risk_level === 3) return "紧急";
  if (message.risk_level === 2) return "请关注";
  return "";
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">消息中心</h2>
    <p class="ph-page-desc">
      系统为你生成的健康提醒与业务通知都汇总在这里。没有推送通道（ADR-0019），所以提醒是「回到站内就能看到」。
    </p>

    <SessionGate forbidden-description="登录后查看你的提醒与通知。">
      <StateLoading v-if="loading" :rows="4" />
      <StateError v-else-if="errorMessage" :message="errorMessage" :request-id="requestId" @retry="load" />

      <div v-else class="ph-columns">
        <div class="ph-stack">
          <article class="ph-card">
            <div class="ph-msg__head">
              <h3 class="ph-card__title">消息（{{ unreadCount }} 条未读）</h3>
              <div class="ph-msg__head-actions">
                <button type="button" class="ph-button ph-button--text" @click="unreadOnly = !unreadOnly; load()">
                  {{ unreadOnly ? "看全部" : "只看未读" }}
                </button>
                <button type="button" class="ph-button ph-button--secondary" :disabled="saving || unreadCount === 0" @click="markAllRead">
                  全部已读
                </button>
              </div>
            </div>

            <StateEmpty
              v-if="messages.length === 0"
              icon="🔔"
              title="暂时没有消息"
              description="疫苗到期、指标异常这类提醒会自动出现在这里。"
            />

            <ul v-else class="ph-msg__list">
              <li
                v-for="message in messages"
                :key="message.id"
                class="ph-msg__item"
                :class="{ 'ph-msg__item--unread': !message.read }"
              >
                <div class="ph-msg__row">
                  <span class="ph-msg__title">
                    <span v-if="!message.read" class="ph-msg__dot" aria-label="未读" />
                    {{ message.title }}
                  </span>
                  <span v-if="riskLabel(message)" class="ph-msg__risk" :class="riskClass(message)">
                    {{ riskLabel(message) }}
                  </span>
                </div>
                <p v-if="message.content" class="ph-msg__content">{{ message.content }}</p>
                <div class="ph-msg__foot">
                  <span class="ph-text-weak">{{ formatDateTime(message.created_at) }}</span>
                  <div class="ph-msg__foot-actions">
                    <RouterLink
                      v-if="message.action_hint && message.action_target"
                      class="ph-button ph-button--text"
                      :to="message.action_target"
                    >
                      {{ message.action_hint }}
                    </RouterLink>
                    <button v-if="!message.read" type="button" class="ph-button ph-button--text" @click="markRead(message)">
                      标记已读
                    </button>
                    <button type="button" class="ph-button ph-button--text" @click="remove(message)">删除</button>
                  </div>
                </div>
              </li>
            </ul>

            <div v-if="hasMore" class="ph-msg__more">
              <button type="button" class="ph-button ph-button--secondary" :disabled="loading" @click="load(true)">
                加载更多
              </button>
            </div>
          </article>
        </div>

        <div class="ph-stack">
          <article class="ph-card">
            <h3 class="ph-card__title">提醒设置</h3>
            <p class="ph-text-sub ph-card__note">按类型开关。安全相关的提醒不能关闭（ADR-0019）。</p>
            <ul class="ph-msg__settings">
              <li v-for="setting in settings" :key="setting.type" class="ph-msg__setting">
                <span :class="{ 'ph-text-weak': !setting.closable }">{{ setting.name }}</span>
                <label class="ph-msg__switch">
                  <input
                    type="checkbox"
                    :checked="setting.enabled"
                    :disabled="!setting.closable || !setting.platform_enabled || saving"
                    @change="toggleSetting(setting, $event)"
                  />
                  <span class="ph-text-weak">
                    {{ !setting.closable ? "不可关闭" : setting.platform_enabled ? "" : "平台暂停" }}
                  </span>
                </label>
              </li>
            </ul>
          </article>
        </div>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-msg__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-msg__head .ph-card__title {
  margin-bottom: 0;
}

.ph-msg__head-actions {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-msg__list {
  list-style: none;
  margin: var(--ph-space-4) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.ph-msg__item {
  padding: var(--ph-space-3) 0;
  border-bottom: 1px solid var(--ph-color-divider);
}

.ph-msg__item:last-child {
  border-bottom: 0;
}

.ph-msg__item--unread .ph-msg__title {
  font-weight: 600;
}

.ph-msg__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-msg__title {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-msg__dot {
  width: 8px;
  height: 8px;
  flex: none;
  background: var(--ph-color-primary);
  border-radius: 50%;
}

.ph-msg__risk {
  padding: 1px var(--ph-space-2);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
}

.ph-msg__risk--red {
  background: var(--ph-color-danger-light);
  color: var(--ph-color-danger);
}

.ph-msg__risk--yellow {
  background: var(--ph-color-orange-light);
  color: var(--ph-color-orange);
}

.ph-msg__risk--green {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-msg__content {
  margin: var(--ph-space-2) 0 0;
  color: var(--ph-color-text-sub);
  font-size: 13px;
  line-height: 1.6;
}

.ph-msg__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-2);
  font-size: 12px;
}

.ph-msg__foot-actions {
  display: flex;
  gap: var(--ph-space-2);
}

.ph-msg__more {
  display: flex;
  justify-content: center;
  margin-top: var(--ph-space-4);
}

.ph-msg__settings {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-msg__setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-msg__switch {
  display: flex;
  align-items: center;
  gap: var(--ph-space-2);
  font-size: 12px;
}
</style>
