<script setup lang="ts">
/**
 * AI 管家：对话区 + 右侧信息栏（ADR-0016）。
 *
 * 右侧栏是这次排布的关键：交付文档要求回答里有「风险等级 / 可能原因 / 建议行动」三段，
 * 这些结构化内容需要常驻位置，不能全塞进气泡。
 *
 * 链路已打通（切片 #98）：文字提问 → ph-ai → AI 服务 → 分级落库。三条行为在界面上要看得见：
 *  - **红线命中**时结论不是模型给的（`red_flag_hits` 非空），文案要写明「命中急症信号」；
 *  - **降级**答复（`degraded`）要标出来，别让用户以为是模型结论；
 *  - 到量的提示**不拦人**（ADR-0024）：照常给结果，只是劝一句邀请好友。
 */
import { computed, ref } from "vue";
import {
  formatDate,
  speciesLabel,
  cApp,
  ApiError,
  createLatestGuard,
  type AiConsultView,
} from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import SessionGate from "../components/SessionGate.vue";

const session = useSessionStore();
const draft = ref("");
const sending = ref(false);
const errorMessage = ref("");
const result = ref<AiConsultView | null>(null);

/** 风险等级的中性文案：绿/黄/红只说就医紧迫程度，不说病名（CONTEXT.md 的 RiskLevel）。 */
const RISK_LABEL: Record<number, string> = {
  1: "🟢 可以居家观察",
  2: "🟡 建议尽快就医",
  3: "🔴 建议立即就医",
};

const riskLabel = computed(() => (result.value ? RISK_LABEL[result.value.risk_level] ?? "" : ""));

/** 契约里 `question` 是 2–500 字：一个字的描述信息量不够，后端也会按 40001 拒。 */
const MIN_QUESTION_LENGTH = 2;
const canSend = computed(() => draft.value.trim().length >= MIN_QUESTION_LENGTH && !sending.value);

/**
 * 并发守卫：一次咨询要花几秒，期间用户可能切了宠物。
 * 不拦的话，**旧宠物的回答会挂在新宠物名下**——「豆豆的结论」显示在咪咪的页面上，
 * 而结论是结合档案给的（评审发现：这一页原先没有守卫）。
 */
const latest = createLatestGuard();

async function send(): Promise<void> {
  const question = draft.value.trim();
  if (!question || sending.value) return;
  const petId = session.activePet?.id;
  if (!petId) {
    errorMessage.value = "先添加一只宠物，AI 才能结合它的档案判断。";
    return;
  }
  const seq = latest.claim();
  sending.value = true;
  errorMessage.value = "";
  try {
    const answer = await cApp.consultAi(petId, { question });
    // 两道判断：期间又发了一次（守卫），或者**换过宠物**（结论的对象已经变了）
    if (!latest.isCurrent(seq) || session.activePet?.id !== petId) return;
    result.value = answer;
    draft.value = "";
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    errorMessage.value = error instanceof ApiError ? error.message : "咨询失败，请稍后重试";
  } finally {
    if (latest.isCurrent(seq)) {
      sending.value = false;
    }
  }
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">AI 管家</h2>
    <p class="ph-page-desc">描述症状、补一张照片，拿到风险等级与下一步该做什么。</p>

    <SessionGate forbidden-description="AI 咨询会结合宠物的健康档案，所以需要先登录。">
      <div class="ph-ai">
        <!-- 对话区 -->
        <div class="ph-card ph-ai__chat">
          <StateEmpty
            v-if="!result"
            icon="💬"
            title="还没有对话"
            description="描述症状（部位、多久、有没有变化），就能拿到风险分级与下一步该做什么。"
          />
          <article v-else class="ph-answer">
            <p class="ph-answer__risk">{{ riskLabel }}</p>
            <p v-if="(result.red_flag_hits ?? []).length > 0" class="ph-answer__flag">
              命中急症信号（{{ (result.red_flag_hits ?? []).join("、") }}）：这一条由规则判定，没有经过模型。
            </p>
            <p v-if="result.degraded" class="ph-answer__flag">
              这次是降级答复（{{ result.degrade_reason }}），不是模型结论。
            </p>
            <div v-if="(result.possible_causes ?? []).length > 0" class="ph-answer__block">
              <h4>可能原因</h4>
              <ol>
                <li v-for="(cause, index) in result.possible_causes ?? []" :key="index">{{ cause }}</li>
              </ol>
            </div>
            <div class="ph-answer__block">
              <h4>建议行动</h4>
              <p>{{ result.action_suggestion }}</p>
            </div>
            <div v-if="(result.care_tips ?? []).length > 0" class="ph-answer__block">
              <h4>照护要点</h4>
              <ul>
                <li v-for="(tip, index) in result.care_tips ?? []" :key="index">{{ tip }}</li>
              </ul>
            </div>
            <p class="ph-note">{{ result.disclaimer }}</p>
            <p class="ph-note">
              今日免费咨询还剩 {{ result.remaining_today }} 次（每天 {{ result.quota_per_day }} 次）。
              <template v-if="result.remaining_today === 0">
                邀请好友可以增加次数；到量不影响继续提问。
              </template>
            </p>
          </article>

          <div class="ph-ai__composer">
            <textarea
              v-model="draft"
              class="ph-ai__input"
              rows="2"
              maxlength="500"
              placeholder="例如：我家狗今天吐了两次，精神不太好"
              :disabled="sending"
              @keydown.enter.exact.prevent="send"
            />
            <button type="button" class="ph-button ph-button--primary" :disabled="!canSend" @click="send">
              {{ sending ? "分析中…" : "发送" }}
            </button>
          </div>
          <p v-if="errorMessage" class="ph-answer__error">{{ errorMessage }}</p>
          <p class="ph-note">
            图片上传与多轮对话在后面接；目前先支持文字描述——皮肤问题只看照片只有三成把握，
            所以症状文本必须写清楚。
          </p>
        </div>

        <!-- 右侧信息栏：宠物摘要 + 最近一次的风险等级 -->
        <aside class="ph-stack">
          <article class="ph-card">
            <h3 class="ph-card__title">本次咨询的对象</h3>
            <ul v-if="session.activePet" class="ph-summary">
              <li><span class="ph-text-sub">昵称</span><span>{{ session.activePet.name }}</span></li>
              <li><span class="ph-text-sub">物种</span><span>{{ speciesLabel(session.activePet.species) }}</span></li>
              <li><span class="ph-text-sub">品种</span><span>{{ session.activePet.breed ?? "未填" }}</span></li>
              <li>
                <span class="ph-text-sub">生日</span>
                <span>{{ session.activePet.birthday ? formatDate(session.activePet.birthday) : "未填" }}</span>
              </li>
              <li>
                <span class="ph-text-sub">慢病</span>
                <span>{{ session.activePet.is_chronic ? session.activePet.chronic_desc ?? "已标记" : "无" }}</span>
              </li>
            </ul>
            <StateEmpty v-else icon="🐾" title="还没有宠物" description="先建档，AI 才能结合它的档案判断。" />
          </article>

          <article class="ph-card">
            <h3 class="ph-card__title">风险提示</h3>
            <p class="ph-risk">{{ riskLabel || "尚未咨询" }}</p>
            <p v-if="result" class="ph-text-sub">
              模型 {{ result.model_version || "未参与" }} · 提示词 {{ result.prompt_version }} ·
              耗时 {{ result.latency_ms }}ms
            </p>
            <p v-else class="ph-text-sub">发起一次咨询后，这里会显示风险等级与建议行动。</p>
            <p class="ph-note">红色风险会直接给出最近 24 小时医院的入口。</p>
          </article>
        </aside>
      </div>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-ai {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--ph-space-4);
  align-items: start;
}

.ph-ai__chat {
  display: flex;
  flex-direction: column;
  min-height: 420px;
}

.ph-ai__composer {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: auto;
  padding-top: var(--ph-space-4);
  border-top: 1px solid var(--ph-color-divider);
}

.ph-ai__input {
  flex: 1;
  resize: vertical;
  padding: var(--ph-space-3);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
  background: var(--ph-color-bg);
}

.ph-answer {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-answer__risk {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.ph-answer__flag {
  margin: 0;
  padding: var(--ph-space-2) var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-danger-light);
  color: var(--ph-color-danger);
  font-size: 13px;
}

.ph-answer__block h4 {
  margin: 0 0 var(--ph-space-1);
  font-size: 14px;
  font-weight: 600;
}

.ph-answer__block ol,
.ph-answer__block ul {
  margin: 0;
  padding-left: var(--ph-space-5);
}

.ph-answer__block p {
  margin: 0;
}

.ph-answer__error {
  margin: var(--ph-space-3) 0 0;
  font-size: 14px;
  color: var(--ph-color-danger);
}

.ph-summary {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-summary li {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-risk {
  margin: 0 0 var(--ph-space-2);
  font-size: 18px;
  font-weight: 600;
}

.ph-note {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}

@media (max-width: 1080px) {
  .ph-ai {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
