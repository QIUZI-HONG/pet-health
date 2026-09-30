<script setup lang="ts">
/**
 * AI 帮我找服务：描述症状 → 推荐项目（F011 规则版，ADR-0050 第二节）。
 *
 * 这一页**不调模型**：症状由知识侧的受控词表认出来，症状到项目的对应关系是运营维护的映射表，
 * 所以每条推荐都有一条说得出来的理由（「因为你说腹泻，建议先做常见病诊疗」）。
 *
 * 三件事在界面上要分清楚（契约把它们的字段分开了，界面就不能揉成一句）：
 *   - **认出了症状**：列出症状（带就医紧迫程度）+ 推荐项目（带平台区间价与理由）；
 *   - **没认出症状**（`matched` 空、`degraded=false`）：换个说法试试，并给浏览目录的出口；
 *   - **AI 侧这次没读出来**（`degraded=true`）：稍后再试，同样是给出口——**不是**「没有推荐」。
 *
 * `notice` 由服务端拼好（含免责声明与红色时的「立即就医」），**这一页原样展示**：
 * 安全相关的文案只有一个来源，前端不许自己写一句「建议就医」。
 */
import { computed, ref } from "vue";
import { formatAmountRange, riskTone, type RiskTone } from "@pet-health/shared";
import { recommendations, type ServiceRecommendationView } from "../api/recommendations";
import { useSubmitAction } from "@pet-health/ui";
import SessionGate from "../components/SessionGate.vue";
import StateEmpty from "../components/states/StateEmpty.vue";

/** 风险等级的中性文案（与 AI 咨询页同一套：只说就医紧迫程度，不说病名）。 */
const RISK_LABEL: Record<RiskTone, string> = {
  green: "🟢 可以居家观察",
  yellow: "🟡 建议尽快就医",
  red: "🔴 建议立即就医",
};

const draft = ref("");
const result = ref<ServiceRecommendationView | null>(null);
const submit = useSubmitAction("没有找到合适的服务，请稍后重试");

const riskLabel = computed(() =>
  result.value?.risk_level == null ? "" : RISK_LABEL[riskTone(result.value.risk_level)],
);

/** 契约里 `text` 是 2–500 字：与 AI 咨询同一口径，前端先拦一道（后端仍会按 40001 再判）。 */
const canSubmit = computed(() => {
  const length = draft.value.trim().length;
  return length >= 2 && length <= 500 && !submit.submitting.value;
});

async function find(): Promise<void> {
  if (!canSubmit.value) return;
  const outcome = await submit.run(() => recommendations.recommend(draft.value.trim()), "已给出建议");
  if (outcome.ok) {
    result.value = outcome.value;
  }
}

/** 再问一次：清掉上一次的结果与提示（描述留着，用户多半是在它上面改）。 */
function reset(): void {
  result.value = null;
  submit.clear();
}

/** 快捷症状：点一下就把词填进输入框（这些词都来自受控词典，认得出）。 */
const QUICK = ["拉稀", "呕吐", "没精神", "一直挠", "腿瘸", "甩头"];
</script>

<template>
  <section>
    <p class="ph-text-weak">
      <RouterLink :to="{ name: 'services' }">← 返回服务页</RouterLink>
    </p>
    <h2 class="ph-page-title">AI 帮我找服务</h2>
    <p class="ph-page-desc">
      说说它怎么了，我们按症状帮你找到该做的项目；具体去哪家店、多少钱，点开项目就能看到。
    </p>

    <SessionGate forbidden-description="登录后使用 AI 找服务（与 AI 咨询同一口径）。">
      <article class="ph-card ph-finder__form">
        <label class="ph-finder__label" for="finder-text">描述症状</label>
        <textarea
          id="finder-text"
          v-model="draft"
          class="ph-finder__input"
          rows="3"
          maxlength="500"
          placeholder="例如：我家猫今天拉稀两次，精神还行"
        />
        <div class="ph-finder__chips">
          <button
            v-for="word in QUICK"
            :key="word"
            type="button"
            class="ph-finder__chip"
            @click="draft = word"
          >
            {{ word }}
          </button>
        </div>
        <div class="ph-finder__actions">
          <span class="ph-text-weak">{{ draft.trim().length }}/500</span>
          <button
            type="button"
            class="ph-button ph-button--primary"
            :disabled="!canSubmit"
            @click="find"
          >
            {{ submit.submitting.value ? "正在匹配…" : "找服务" }}
          </button>
        </div>
      </article>

      <p v-if="submit.errorMessage.value" class="ph-finder__error" role="alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>

      <template v-if="result">
        <article v-if="result.risk_level" class="ph-card ph-finder__risk" :class="`ph-finder__risk--${riskTone(result.risk_level)}`">
          <p class="ph-finder__risk-label">{{ riskLabel }}</p>
          <p class="ph-finder__risk-hint ph-text-sub">
            这是<strong>就医紧迫程度</strong>，不是诊断结论；它不改变下面的推荐。
          </p>
        </article>

        <article v-if="result.matched?.length" class="ph-card ph-finder__matched">
          <h3 class="ph-finder__heading">认出来的症状</h3>
          <p class="ph-finder__symptoms">
            <span v-for="item in result.matched" :key="item.symptom" class="ph-finder__symptom">
              {{ item.symptom }}
            </span>
          </p>
          <p v-if="result.matched.some((item) => item.title)" class="ph-text-weak">
            {{ result.matched.find((item) => item.title)?.title }}
          </p>
        </article>

        <template v-if="result.recommendations?.length">
          <h3 class="ph-finder__heading">建议做的项目</h3>
          <ul class="ph-finder__list">
            <li v-for="item in result.recommendations" :key="item.item_code" class="ph-card ph-finder__item">
              <div class="ph-finder__main">
                <div>
                  <p class="ph-finder__name">{{ item.name ?? "服务项目" }}</p>
                  <p class="ph-finder__meta ph-text-sub">
                    <span v-if="item.category_name">{{ item.category_name }}</span>
                    <span v-if="item.price_unit">计价单位：{{ item.price_unit }}</span>
                    <span v-if="item.duration_minutes">{{ item.duration_minutes }} 分钟</span>
                  </p>
                  <!-- 推荐理由：服务端拼好的整句，逐字展示 -->
                  <p class="ph-finder__reason">{{ item.reason }}</p>
                </div>
                <div class="ph-finder__price">
                  <span class="ph-finder__range">{{ formatAmountRange(item.price_min, item.price_max) }}</span>
                  <span class="ph-text-weak">平台区间价</span>
                </div>
              </div>
              <div class="ph-finder__item-actions">
                <RouterLink
                  class="ph-button ph-button--secondary"
                  :to="{ name: 'catalog-item', params: { code: item.item_code } }"
                >
                  哪些门店能做
                </RouterLink>
              </div>
            </li>
          </ul>
        </template>

        <article v-else class="ph-card">
          <StateEmpty
            icon="🐾"
            title="这次没有给出推荐项目"
            :description="
              result.degraded
                ? 'AI 服务暂时不可用，稍后再试；也可以直接浏览服务目录。'
                : '可以把症状说得再具体一点，或者直接按分类浏览门店与项目。'
            "
          >
            <RouterLink class="ph-button ph-button--secondary" :to="{ name: 'catalog' }">浏览服务目录</RouterLink>
          </StateEmpty>
        </article>

        <!-- 免责声明与（红色时的）就医提示由服务端给，逐字展示 -->
        <p class="ph-finder__notice ph-text-weak">{{ result.notice }}</p>
        <p class="ph-finder__again">
          <button type="button" class="ph-button ph-button--text" @click="reset">再问一次</button>
        </p>
      </template>
    </SessionGate>
  </section>
</template>

<style scoped>
.ph-finder__form {
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-2);
}

.ph-finder__label {
  font-size: 13px;
  color: var(--ph-color-text-sub);
}

.ph-finder__input {
  padding: var(--ph-space-3);
  background: var(--ph-color-surface);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-input);
  font-family: inherit;
  font-size: 14px;
  color: var(--ph-color-text);
  resize: vertical;
}

.ph-finder__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
}

.ph-finder__chip {
  height: 28px;
  padding: 0 var(--ph-space-3);
  background: var(--ph-color-bg);
  border: 1px solid var(--ph-color-border);
  border-radius: var(--ph-radius-button);
  font-family: inherit;
  font-size: 12px;
  color: var(--ph-color-text-sub);
  cursor: pointer;
}

.ph-finder__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--ph-space-1);
}

.ph-finder__error {
  margin: var(--ph-space-3) 0 0;
  font-size: 13px;
  color: var(--ph-color-danger);
}

.ph-finder__risk {
  margin-top: var(--ph-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
}

.ph-finder__risk--green {
  border-left: 4px solid var(--ph-color-primary);
}

.ph-finder__risk--yellow {
  border-left: 4px solid var(--ph-color-orange);
}

.ph-finder__risk--red {
  border-left: 4px solid var(--ph-color-danger);
}

.ph-finder__risk-label {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ph-finder__risk-hint {
  margin: 0;
  font-size: 12px;
}

.ph-finder__matched {
  margin-top: var(--ph-space-3);
}

.ph-finder__heading {
  margin: var(--ph-space-4) 0 var(--ph-space-2);
  font-size: 16px;
  font-weight: 600;
}

.ph-finder__symptoms {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-2);
  margin: 0 0 var(--ph-space-2);
}

.ph-finder__symptom {
  padding: 2px var(--ph-space-3);
  background: var(--ph-color-primary-light);
  border-radius: var(--ph-radius-input);
  font-size: 13px;
  color: var(--ph-color-primary);
}

.ph-finder__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-3);
}

.ph-finder__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-finder__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.ph-finder__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ph-space-3);
  margin: var(--ph-space-1) 0 0;
  font-size: 12px;
}

.ph-finder__reason {
  margin: var(--ph-space-2) 0 0;
  font-size: 13px;
  color: var(--ph-color-text);
}

.ph-finder__price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  font-size: 12px;
  text-align: right;
  white-space: nowrap;
}

.ph-finder__range {
  font-family: var(--ph-font-numeric);
  font-size: 16px;
  font-weight: 600;
  color: var(--ph-color-primary);
}

.ph-finder__item-actions {
  display: flex;
  gap: var(--ph-space-2);
  margin-top: var(--ph-space-3);
}

.ph-finder__notice {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ph-finder__again {
  margin: var(--ph-space-2) 0 0;
}
</style>
