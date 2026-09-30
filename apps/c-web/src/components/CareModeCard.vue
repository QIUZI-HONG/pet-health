<script setup lang="ts">
/**
 * 专项照护模式卡（切片 #116，判定与四项变化见 ADR-0032）。
 *
 * 两条展示纪律：
 *
 *  1. **它是派生结果，不是开关状态**：显示「为什么开着」（老年 / 慢病）比显示一个
 *     「已开启」徽标有用得多——用户看到「9 岁」才明白这不是谁点开的。
 *  2. **开与关都会改变总分口径**（维数变了），所以 `notice` 必须显示，否则分数一波动
 *     会被读成宠物病了或好了（ADR-0032 的后果一节）。
 *
 * 关闭按钮是**唯一的写操作**：自动开启的部分用户改不了（生日与慢病是事实），
 * 能改的只有「我不想用它」这件意愿。
 */
import { ref } from "vue";
import { cApp, toUserMessage, type CareModeView } from "@pet-health/shared";

const props = defineProps<{ petId: number; careMode: CareModeView }>();
const emit = defineEmits<{ changed: [CareModeView] }>();

const saving = ref(false);
const errorMessage = ref("");

async function toggle(): Promise<void> {
  saving.value = true;
  errorMessage.value = "";
  try {
    // 打开 = 清除「用户关掉了」这枚意愿位，把判定交还给生日与慢病（ADR-0032 决定一）
    emit("changed", await cApp.setCareMode(props.petId, !props.careMode.active));
  } catch (error) {
    errorMessage.value = toUserMessage(error, "操作失败，请稍后重试");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <article class="ph-card">
    <div class="ph-care__head">
      <h3 class="ph-card__title">专项照护模式</h3>
      <span class="ph-care__badge" :class="{ 'ph-care__badge--on': careMode.active }">
        {{ careMode.active ? "已开启" : "未开启" }}
      </span>
    </div>

    <p v-if="careMode.reasons.length" class="ph-text-sub ph-care__reasons">
      {{ careMode.reasons.map((reason) => reason.label).join(" · ") }}
    </p>
    <p v-else class="ph-text-sub ph-care__reasons">
      {{ careMode.age_threshold_years }} 岁以上或有慢病时自动开启；当前年龄：
      {{ careMode.age_text ?? "未填生日" }}
    </p>

    <ul v-if="careMode.active" class="ph-care__effects">
      <li v-for="effect in careMode.effects" :key="effect">{{ effect }}</li>
    </ul>

    <p class="ph-care__notice">{{ careMode.notice }}</p>
    <p v-if="errorMessage" class="ph-form__error">{{ errorMessage }}</p>

    <div class="ph-care__actions">
      <button
        type="button"
        class="ph-button"
        :class="careMode.active ? 'ph-button--secondary' : 'ph-button--primary'"
        :disabled="saving"
        @click="toggle"
      >
        {{ saving ? "处理中…" : careMode.active ? "关闭照护模式" : "恢复照护模式" }}
      </button>
      <span class="ph-text-weak">
        {{ careMode.active ? "关闭后历史记录保留，只是不再按照护档提醒与计分" : "恢复后按生日与慢病自动判定" }}
      </span>
    </div>
  </article>
</template>

<style scoped>
.ph-care__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ph-space-4);
}

.ph-care__head .ph-card__title {
  margin-bottom: 0;
}

.ph-care__badge {
  padding: 2px var(--ph-space-3);
  border-radius: var(--ph-radius-input);
  background: var(--ph-color-divider);
  color: var(--ph-color-text-sub);
  font-size: 12px;
}

.ph-care__badge--on {
  background: var(--ph-color-primary-light);
  color: var(--ph-color-primary);
}

.ph-care__reasons {
  margin: var(--ph-space-3) 0 0;
  font-size: 13px;
}

.ph-care__effects {
  margin: var(--ph-space-3) 0 0;
  padding-left: var(--ph-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ph-space-1);
  font-size: 13px;
  color: var(--ph-color-text);
}

.ph-care__notice {
  margin: var(--ph-space-3) 0 0;
  font-size: 12px;
  color: var(--ph-color-text-weak);
  line-height: 1.6;
}

.ph-care__actions {
  display: flex;
  align-items: center;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
  font-size: 12px;
}
</style>
