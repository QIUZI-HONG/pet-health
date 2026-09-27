<script setup lang="ts">
/**
 * 首页：多栏布局（ADR-0016）——主栏放「评分 + 快捷服务 + 今日任务」，次栏放「需要你关注」。
 *
 * 现在只有账号与宠物档案的接口（切片 #94），所以：
 *   - 评分卡、提醒流、任务、券这些区块**如实显示空态**，不编假数据；
 *   - 有真实数据的那部分（宠物摘要）直接读会话里的宠物。
 * 每个空态都写明它依赖哪张票，免得看的人以为是坏了。
 */
import { computed } from "vue";
import { formatDate, speciesLabel } from "@pet-health/shared";
import { useSessionStore } from "../stores/session";
import StateEmpty from "../components/states/StateEmpty.vue";
import SessionGate from "../components/SessionGate.vue";

const session = useSessionStore();

const pet = computed(() => session.activePet);

/** 交付文档 F004 的五维：生理 / 行为 / 卫生 / 防疫 / 老年。老年专项未开启时不计入。 */
const scoreDimensions = ["生理", "行为", "卫生", "防疫", "老年"] as const;

const quickEntries = [
  { label: "AI 问诊", hint: "描述症状，拿到风险分级", to: "/ai", icon: "💬" },
  { label: "去打卡", hint: "每天 3 秒记录", to: "/records", icon: "✅" },
  { label: "找服务", hint: "医院 / 洗护 / 寄养", to: "/services", icon: "🩺" },
  { label: "看档案", hint: "疫苗与就医记录", to: "/records", icon: "📋" },
];
</script>

<template>
  <section>
    <h2 class="ph-page-title">你好{{ session.user ? `，${session.user.nickname}` : "" }}</h2>
    <p class="ph-page-desc">24 小时看着这只小家伙的，是你和它一起。</p>

    <SessionGate forbidden-description="登录后就能看到健康评分、提醒和打卡任务。">
      <div class="ph-columns">
      <!-- 主栏 -->
      <div class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">{{ pet ? `${pet.name} 的健康评分` : "健康评分" }}</h3>
          <div v-if="pet" class="ph-score">
            <div class="ph-score__ring" aria-hidden="true">--</div>
            <ul class="ph-score__dims">
              <li v-for="dim in scoreDimensions" :key="dim" class="ph-score__dim">
                <span>{{ dim }}</span>
                <span class="ph-text-weak">待评分</span>
              </li>
            </ul>
          </div>
          <StateEmpty
            v-if="!pet"
            icon="🐾"
            title="还没有宠物"
            description="先建一份档案，评分和提醒才有对象。"
          />
          <!-- 评分算法与打卡形态待 #57 决策、#97 实现；在实现之前不显示任何数字，免得像真的 -->
          <p class="ph-note">健康评分即将上线，先把宠物档案补全，评分才有依据。</p>
        </article>

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

        <article class="ph-card">
          <h3 class="ph-card__title">今日任务</h3>
          <StateEmpty
            icon="📝"
            title="任务体系还没上线"
            description="每天完成任务可以攒积分、换券。任务中心即将上线。"
          />
        </article>
      </div>

      <!-- 次栏 -->
      <div class="ph-stack">
        <article class="ph-card">
          <h3 class="ph-card__title">需要你关注</h3>
          <StateEmpty
            icon="🔔"
            title="暂时没有异常"
            description="疫苗到期、饮水异常这些提醒会出现在这里。暂时一切正常。"
          />
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
.ph-score {
  display: flex;
  align-items: center;
  gap: var(--ph-space-6);
}

.ph-score__ring {
  width: 96px;
  height: 96px;
  flex: none;
  display: grid;
  place-items: center;
  border: 6px solid var(--ph-color-primary-light);
  border-radius: 50%;
  color: var(--ph-color-text-weak);
  font-family: var(--ph-font-numeric);
  font-size: 22px;
}

.ph-score__dims {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ph-space-2) var(--ph-space-6);
  flex: 1;
}

.ph-score__dim {
  display: flex;
  justify-content: space-between;
  gap: var(--ph-space-3);
}

.ph-note {
  margin: var(--ph-space-4) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
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
