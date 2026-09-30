<script setup lang="ts">
/**
 * 四态（空 / 加载 / 错误 / 无权限）的简版，两个后台共用。
 *
 * 与 C 端的 `components/states/*` 是两套东西：ADR-0015 让三个端的组件库分家，那边是 C 端自己的四个组件。
 * 这里合成一个带 `variant` 的组件——四种状态的骨架、图标位、动作区完全一样，只差图标与默认文案，
 * 拆成四个文件就是四份重复模板（「要么够通用，要么留在页面里」）。
 *
 * 这一波不接接口，页面实际用到的只有 `empty`；其余三种先备好，接数据时直接换 variant。
 *
 * 同 ConsoleShell：**不 import vue**（本包不声明依赖），所以这里只有 props、插槽与一个纯函数。
 */
import type { ConsoleStateVariant } from "./types";

withDefaults(
  defineProps<{
    variant?: ConsoleStateVariant;
    /** 主文案；留空按 variant 取默认 */
    title?: string;
    /** 副文案：说明「为什么空」「下一步做什么」，比主文案更需要按页面写 */
    description?: string;
    /** 图标；留空按 variant 取默认 */
    icon?: string;
    /** 错误态的按钮文案 */
    retryText?: string;
    /**
     * 错误态的请求 ID（ADR-0029）：出问题时报给客服/开发，能直接定位到后端日志那一条。
     * 只有 error 态渲染它——空态与无权限态没有可追的请求。
     */
    requestId?: string;
  }>(),
  { variant: "empty", title: "", description: "", icon: "", retryText: "重新加载", requestId: "" },
);

defineEmits<{ retry: [] }>();

/** 每个状态的默认图标与默认主文案（交付文档 4.16.9 的状态对照表）。 */
const DEFAULTS: Record<ConsoleStateVariant, { icon: string; title: string }> = {
  empty: { icon: "🐾", title: "这里还没有内容" },
  loading: { icon: "", title: "加载中" },
  error: { icon: "⚠️", title: "加载失败，请稍后重试" },
  forbidden: { icon: "🔒", title: "暂无权限" },
};

/** 状态语义：错误用 alert、加载用 status，空态与无权限不播报（读屏会先念主文案）。 */
function roleOf(variant: ConsoleStateVariant): "alert" | "status" | undefined {
  if (variant === "error") return "alert";
  if (variant === "loading") return "status";
  return undefined;
}
</script>

<template>
  <div class="ph-state" :class="`ph-state--${variant}`" :role="roleOf(variant)">
    <!-- 加载态用骨架条而不是转圈：后台一屏就是一张表，骨架能先撑住版式，转圈会让整页跳一下 -->
    <template v-if="variant === 'loading'">
      <span v-for="row in 3" :key="row" class="ph-state__skeleton" :style="{ width: `${96 - row * 12}%` }" />
    </template>

    <template v-else>
      <div class="ph-state__icon" aria-hidden="true">{{ icon || DEFAULTS[variant].icon }}</div>
      <p class="ph-state__title">{{ title || DEFAULTS[variant].title }}</p>
      <p v-if="description" class="ph-state__desc">{{ description }}</p>
      <p v-if="variant === 'error' && requestId" class="ph-state__trace">请求 ID：{{ requestId }}</p>
      <div class="ph-state__actions">
        <button
          v-if="variant === 'error'"
          type="button"
          class="ph-button ph-button--primary"
          @click="$emit('retry')"
        >
          {{ retryText }}
        </button>
        <slot />
      </div>
    </template>
  </div>
</template>

<style scoped>
.ph-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ph-space-2);
  padding: var(--ph-space-10) var(--ph-space-6);
  text-align: center;
}

.ph-state__icon {
  margin-bottom: var(--ph-space-2);
  font-size: 32px;
  line-height: 1;
}

.ph-state__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ph-color-text);
}

.ph-state__desc {
  max-width: 480px;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ph-color-text-sub);
}

/* 请求 ID 用等宽数字体：报障时是逐个字符念/抄的，比例字体容易抄错 0 与 O */
.ph-state__trace {
  margin: 0;
  font-family: var(--ph-font-numeric);
  font-size: 12px;
  color: var(--ph-color-text-weak);
}

.ph-state__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}

/* 加载态：撑住版式的骨架条 */
.ph-state--loading {
  align-items: stretch;
}

.ph-state__skeleton {
  height: 16px;
  border-radius: var(--ph-radius-input);
  background: linear-gradient(
    90deg,
    var(--ph-color-divider) 0%,
    var(--ph-color-bg) 50%,
    var(--ph-color-divider) 100%
  );
  background-size: 200% 100%;
  animation: ph-console-skeleton 1.2s ease-in-out infinite;
}

@keyframes ph-console-skeleton {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
</style>
