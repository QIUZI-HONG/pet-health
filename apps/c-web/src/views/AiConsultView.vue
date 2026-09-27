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
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import StateForbidden from "../components/states/StateForbidden.vue";

const session = useSessionStore();
const draft = ref("");
</script>

<template>
  <section>
    <h2 class="ph-page-title">AI 管家</h2>
    <p class="ph-page-desc">描述症状、补一张照片，拿到风险等级与下一步该做什么。</p>

    <StateForbidden
      v-if="!session.isLoggedIn"
      description="AI 咨询会结合宠物的健康档案，所以需要先登录。"
    />

    <div v-else class="ph-ai">
      <!-- 对话区 -->
      <div class="ph-card ph-ai__chat">
        <StateEmpty
          icon="💬"
          title="还没有对话"
          description="AI 咨询链路（#98 决策、#101 接真知识库）落地后，这里会显示分级结论、可能原因与建议行动。"
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
        <p class="ph-note">
          输入框先禁用：纯图片分诊准确率只有 33%，因此接口层要求必须带一句症状描述（#61 的结论）；
          这条链路还没接上，先不放一个点了没反应的按钮。
        </p>
      </div>

      <!-- 右侧信息栏：宠物摘要 + 风险提示 -->
      <aside class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">本次咨询的对象</h3>
          <ul v-if="session.activePet" class="ph-summary">
            <li><span class="ph-text-sub">昵称</span><span>{{ session.activePet.name }}</span></li>
            <li><span class="ph-text-sub">物种</span><span>{{ session.activePet.species === 2 ? "猫" : "犬" }}</span></li>
            <li><span class="ph-text-sub">品种</span><span>{{ session.activePet.breed ?? "未填" }}</span></li>
            <li>
              <span class="ph-text-sub">生日</span>
              <span>{{ session.activePet.birthday ?? "未填" }}</span>
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
          <p class="ph-risk ph-risk--green">🟢 绿</p>
          <p class="ph-text-sub">目前没有进行中的咨询。</p>
          <p class="ph-note">
            红色风险会在这里直接给出最近 24 小时医院的入口——医疗场景宁严勿松
            （docs/conventions.md）。
          </p>
        </article>
      </aside>
    </div>
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

.ph-risk--green {
  color: var(--ph-color-success);
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
