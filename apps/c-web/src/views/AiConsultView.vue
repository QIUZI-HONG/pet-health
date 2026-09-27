<script setup lang="ts">
/**
 * AI 管家：对话区 + 右侧信息栏（ADR-0016）。
 *
 * 右侧栏是这次排布的关键：交付文档要求回答里有「风险等级 / 可能原因 / 建议行动」三段，
 * 这些结构化内容需要常驻位置，不能全塞进气泡。
 *
 * AI 链路（#98）还没实现，所以输入框禁用并说明原因——不做假对话。
 */
import { ref } from "vue";
import { formatDate, speciesLabel } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import SessionGate from "../components/SessionGate.vue";

const session = useSessionStore();
const draft = ref("");
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
          icon="💬"
          title="还没有对话"
          description="描述症状、补一张照片，就能拿到风险分级与下一步该做什么。功能正在接入。"
        />
        <div class="ph-ai__composer">
          <textarea
            v-model="draft"
            class="ph-ai__input"
            rows="2"
            placeholder="例如：我家狗今天吐了两次，精神不太好"
            disabled
          />
          <button type="button" class="ph-button ph-button--primary" disabled>发送</button>
        </div>
        <!-- 输入框禁用中：C 端到 AI 的链路（#98/#101）还没接；空按钮点了没反应比禁用更糟 -->
        <p class="ph-note">咨询功能即将开放。为了判断更准，届时需要补一句症状描述（宠物皮肤问题只看照片只有三成把握）。</p>
      </div>

      <!-- 右侧信息栏：宠物摘要 + 风险提示 -->
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

        <!-- 这里曾经常驻显示「🟢 绿」：没有任何咨询时就说「绿灯」是无依据的乐观暗示，
             医疗场景宁严勿松（docs/conventions.md），所以改成中性的「尚未咨询」 -->
        <article class="ph-card">
          <h3 class="ph-card__title">风险提示</h3>
          <p class="ph-risk">尚未咨询</p>
          <p class="ph-text-sub">发起一次咨询后，这里会显示风险等级与建议行动。</p>
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
