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
import { computed, onMounted, ref } from "vue";
import {
  formatDate,
  speciesLabel,
  uploadFiles,
  cApp,
  toUserMessage,
  createLatestGuard,
  type AiConsultView,
  riskTone,
  type RiskTone,
  type MessageView,
} from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import SessionGate from "../components/SessionGate.vue";

const session = useSessionStore();
/** 已上传的图片 file_id（最多 4 张，与契约的上限一致）；随咨询一起发给 AI。 */
const fileIds = ref<number[]>([]);
const uploading = ref(false);
const uploadError = ref("");
const draft = ref("");
const sending = ref(false);
const errorMessage = ref("");
const result = ref<AiConsultView | null>(null);

/** 风险等级的中性文案：绿/黄/红只说就医紧迫程度，不说病名（CONTEXT.md 的 RiskLevel）。 */
const RISK_LABEL: Record<RiskTone, string> = {
  green: "🟢 可以居家观察",
  yellow: "🟡 建议尽快就医",
  red: "🔴 建议立即就医",
};

const riskLabel = computed(() => (result.value ? RISK_LABEL[riskTone(result.value.risk_level)] : ""));

/** 契约里 `file_ids` 最多 4 张（`maxItems: 4`）。 */
const MAX_IMAGES = 4;

/** 契约里 `question` 是 2–500 字：一个字的描述信息量不够，后端也会按 40001 拒。 */
const MIN_QUESTION_LENGTH = 2;
const canSend = computed(() => draft.value.trim().length >= MIN_QUESTION_LENGTH && !sending.value);

/** 转人工的提交结果（幂等：重复提交返回同一条工单，所以这里只说「已受理」）。 */
const transferring = ref(false);
const transferError = ref("");
const transferMessage = ref("");

/**
 * 选图 → 直传（先经 /files 拿凭证再直传，切片 #95 的路径）→ 记下 file_id。
 *
 * 上限 4 张与契约一致：多了后端会按 40001 拒，不如在这里就说清楚。
 * 上传失败不拦咨询：图片是增强（皮肤问题只看照片只有三成把握），文字描述才是必填的那一项。
 */
async function pickImages(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  if (files.length === 0) return;
  const petId = session.activePet?.id;
  if (!petId) {
    uploadError.value = "先添加一只宠物再上传照片。";
    return;
  }
  const room = MAX_IMAGES - fileIds.value.length;
  if (files.length > room) {
    uploadError.value = `最多 ${MAX_IMAGES} 张，还能再加 ${room} 张。`;
    return;
  }
  uploading.value = true;
  uploadError.value = "";
  try {
    const uploaded = await uploadFiles({ petId, bizType: "ai_consult", files });
    fileIds.value = [...fileIds.value, ...uploaded.map((file) => file.id).filter((id): id is number => id != null)];
  } catch (error) {
    uploadError.value = toUserMessage(error, "图片上传失败，可以先只发文字描述。");
  } finally {
    uploading.value = false;
  }
}

function removeImage(fileId: number): void {
  fileIds.value = fileIds.value.filter((id) => id !== fileId);
}

/**
 * 转人工：把这次咨询交给平台人工跟进。
 *
 * 幂等由服务端保证（一次咨询只能转一次），所以这里不做「防重复提交」的额外判断——
 * 连点两下拿到的是同一条工单，界面如实说「已受理」即可。
 * 口径上它**不收费**：本项目钱在门店付、平台不经手资金（ADR-0036），所以按钮上不写价格。
 */
async function transfer(): Promise<void> {
  const petId = session.activePet?.id;
  const consultId = result.value?.id;
  if (!petId || !consultId || transferring.value) return;
  transferring.value = true;
  transferError.value = "";
  try {
    await cApp.transferToHuman(petId, consultId);
    transferMessage.value = "已受理：人工会查看这次咨询，回复会出现在消息中心（预计 2 小时内）。";
  } catch (error) {
    transferError.value = toUserMessage(error, "提交失败，请稍后重试。");
  } finally {
    transferring.value = false;
  }
}

/**
 * 并发守卫：一次咨询要花几秒，期间用户可能切了宠物。
 * 不拦的话，**旧宠物的回答会挂在新宠物名下**——「豆豆的结论」显示在咪咪的页面上，
 * 而结论是结合档案给的（评审发现：这一页原先没有守卫）。
 */
const latest = createLatestGuard();

/**
 * 问候卡与快捷标签（交付文档 4.16.3 的第 1、2 块）。
 *
 * **「今天状态不错」这句话不编**：稿子上写的是它，但我们能证明的只是「有没有需要关注的事」——
 * 所以文案按真实数据分两岔（有 N 项 / 暂时没有），不去替它下「状态不错」这个结论。
 * 「需要关注」的口径与首页同一处：未读的健康提醒（`/messages/highlights`）。
 */
const attention = ref<MessageView[]>([]);

/** 按服务端时区的钟点问候。上午/下午/晚上三档足够，不必更细。 */
const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 6) return "夜深了";
  if (hour < 12) return "早上好";
  if (hour < 18) return "下午好";
  return "晚上好";
});

const petName = computed(() => session.activePet?.name ?? "毛孩子");

/** 快捷标签：点一下**直接发送**（稿子原话「点击即作为提问内容发送」）——比让用户再点一次发送快。 */
const QUICK = ["皮肤问题", "呕吐", "疫苗", "行为异常"];

onMounted(() => {
  void loadAttention();
});

async function loadAttention(): Promise<void> {
  if (!session.isLoggedIn) return;
  try {
    attention.value = (await cApp.getMessageHighlights()) ?? [];
  } catch {
    // 取不到就不显示「需要关注」那半句：宁可少说一句，也不要瞎说一句
    attention.value = [];
  }
}

/** 点快捷标签：填进输入框立刻发出去（发送函数里还会做一次宠物与长度的校验）。 */
function askQuick(word: string): void {
  draft.value = word;
  void send();
}

async function send(): Promise<void> {
  const question = draft.value.trim();
  if (!question || sending.value) return;
  const petId = session.activePet?.id;
  if (!petId) {
    errorMessage.value = "先添加一只宠物，AI 才能结合它的档案判断。";
    return;
  }
  const { token: seq, signal } = latest.claim();
  sending.value = true;
  errorMessage.value = "";
  try {
    const answer = await cApp.consultAi(
      petId,
      // file_ids 只在有图时带上：契约里它是可选的，传空数组等于说「有图但没传」
      fileIds.value.length > 0 ? { question, file_ids: [...fileIds.value] } : { question },
      signal,
    );
    // 两道判断：期间又发了一次（守卫），或者**换过宠物**（结论的对象已经变了）
    if (!latest.isCurrent(seq) || session.activePet?.id !== petId) return;
    result.value = answer;
    draft.value = "";
    fileIds.value = [];
    transferMessage.value = "";
  } catch (error) {
    if (!latest.isCurrent(seq)) return;
    errorMessage.value = toUserMessage(error, "咨询失败，请稍后重试");
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
        <!-- 问候卡（4.16.3 第 1 块）：主色浅底 + 宠物名 + 「需要关注」的真实条数 -->
        <div class="ph-card ph-ai__greet">
          <span class="ph-ai__greet-avatar" aria-hidden="true">🐾</span>
          <div class="ph-ai__greet-text">
            <p class="ph-ai__greet-title">{{ greeting }}，{{ petName }}</p>
            <p class="ph-text-sub">
              {{ attention.length > 0
                ? `有 ${attention.length} 项需要关注：${attention.map((item) => item.title).join("；")}`
                : "暂时没有需要特别关注的事，有异常随时问我。" }}
            </p>
          </div>
        </div>

        <!-- 快捷标签（同第 2 块）：点一下直接作为提问发送 -->
        <div class="ph-ai__quick">
          <button
            v-for="word in QUICK"
            :key="word"
            type="button"
            class="ph-ai__quick-chip"
            :disabled="sending"
            @click="askQuick(word)"
          >
            {{ word }}
          </button>
        </div>

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
            <p v-if="result.need_hospital" class="ph-answer__flag">
              建议尽快就医；拿到结果后可以点下面的「转人工」让平台人工再看一次。
            </p>
            <div v-if="(result.citations ?? []).length > 0" class="ph-answer__block">
              <h4>参考的知识条目</h4>
              <ul>
                <li v-for="citation in result.citations ?? []" :key="citation.entry_id">
                  {{ citation.title ?? citation.entry_id }}
                  <span class="ph-text-weak">（{{ citation.entry_id }}）</span>
                </li>
              </ul>
              <!-- 未复核提醒：服务端只说「这次用到了未复核条目」，不说是哪几条（ADR-0033） -->
              <p v-if="result.unvetted_used" class="ph-text-weak">
                其中部分条目尚未经兽医复核，仅供参考。
              </p>
            </div>
            <div class="ph-answer__block">
              <h4>还需要人帮忙？</h4>
              <p class="ph-text-sub">
                转人工后由平台人工查看这次咨询并回复（预计 2 小时内），**不收费**——
                本项目钱在门店直接付给服务者，平台不经手资金。
              </p>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="transferring || transferMessage !== ''"
                @click="transfer"
              >
                {{ transferring ? "提交中…" : transferMessage !== "" ? "已受理" : "转人工咨询" }}
              </button>
              <p v-if="transferMessage" class="ph-text-sub">{{ transferMessage }}</p>
              <p v-if="transferError" class="ph-answer__error">{{ transferError }}</p>
            </div>
            <p class="ph-note">{{ result.disclaimer }}</p>
            <p class="ph-note">
              今日免费咨询还剩 {{ result.remaining_today }} 次（每天 {{ result.quota_per_day }} 次）。
              <template v-if="result.remaining_today === 0">
                邀请好友可以增加次数；到量不影响继续提问。
              </template>
            </p>
          </article>

          <div class="ph-ai__uploads">
            <label class="ph-button ph-button--secondary ph-ai__upload">
              {{ uploading ? "上传中…" : "加照片（最多 4 张）" }}
              <input
                type="file"
                accept="image/jpeg,image/png"
                multiple
                :disabled="uploading || fileIds.length >= MAX_IMAGES"
                @change="pickImages"
              />
            </label>
            <span v-for="fileId in fileIds" :key="fileId" class="ph-ai__thumb">
              图 {{ fileId }}
              <button type="button" class="ph-ai__thumb-remove" @click="removeImage(fileId)">×</button>
            </span>
          </div>
          <p v-if="uploadError" class="ph-answer__error">{{ uploadError }}</p>

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
            图片可以一起发（皮肤问题只看照片只有三成把握，61 号调研实测），所以文字描述仍要写清楚；
            多轮对话与视频输入还没做（视频按 ADR-0004 不做）。
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

/* 问候卡与快捷标签（4.16.3 的第 1、2 块）：贴在同一栏的顶部，对话区在下面 */
.ph-ai__greet {
  /* 这一页是两栏网格（ADR-0016）：问候卡与标签在**对话区之上**、横跨整行，
     否则网格会把标签排到右栏去（截图核实过）。 */
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  margin-bottom: var(--ph-space-3);
  background: var(--ph-color-primary-light);
}

.ph-ai__greet-avatar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--ph-color-surface);
  font-size: 18px;
}

.ph-ai__greet-text {
  min-width: 0;
}

.ph-ai__greet-title {
  margin: 0 0 2px;
  font-weight: 600;
}

.ph-ai__quick {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-3);
}

.ph-ai__quick-chip {
  padding: var(--ph-space-2) var(--ph-space-3);
  border: 1px solid var(--ph-color-border);
  border-radius: 999px;
  background: var(--ph-color-surface);
  font: inherit;
  color: var(--ph-color-text-sub);
  cursor: pointer;
}

.ph-ai__quick-chip:hover:not(:disabled) {
  border-color: var(--ph-color-primary);
  color: var(--ph-color-primary);
}

.ph-ai__quick-chip:disabled {
  opacity: 0.6;
  cursor: default;
}

.ph-ai__chat {
  display: flex;
  flex-direction: column;
  min-height: 420px;
}

.ph-ai__uploads {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin-bottom: var(--ph-space-2);
}

/* 文件选择框藏起来，靠 label 当按钮——原生控件的样式在三端没法统一 */
.ph-ai__upload input {
  display: none;
}

.ph-ai__thumb {
  display: inline-flex;
  align-items: center;
  gap: var(--ph-space-1);
  padding: 2px var(--ph-space-2);
  background: var(--ph-color-bg);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-size: 12px;
  color: var(--ph-color-text-sub);
}

.ph-ai__thumb-remove {
  border: none;
  background: none;
  padding: 0 2px;
  font-size: 14px;
  color: var(--ph-color-text-weak);
  cursor: pointer;
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
