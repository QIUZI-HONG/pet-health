<script setup lang="ts">
/**
 * 内容审核（交付文档 3.1 模块树「内容审核（社区 / 经验卡片）」）。
 *
 * 审核对象是**社区的三种内容**：经验卡片、提问、回答。契约把「按类型分别查」定成了硬口径
 * （`/community/contents` 的 `content_type` 必填，三种内容各住一张表，合并成跨表 UNION 只会
 * 让「总条数」变成三次 COUNT 的和），所以这一页的分段就是三个类型 + 一个词表——不做一个
 * 「全部类型」的合并视图：那个视图的总数与排序会让运营看到一个自己无法解释的数字。
 *
 * 第四段是**敏感词管理**：机审词表与内容审核是同一件事的两半（词表决定哪些内容直接落在已驳回、
 * 运营再改判），所以它们在一页里，切分段就能对起来看。词表在库里、改完即时生效（ADR-0010）。
 *
 * 术语提醒：这里的「敏感词」属内容审核域，与 AI 的**硬红线词**（RedFlag，医疗分级用）不是一回事，
 * 两套词表不要混（CONTEXT.md「硬红线」条目专门划了这条线，页面提示里也再说一遍）。
 *
 * 交付文档 2.2 的权限矩阵里还有「置顶 / 加精」：**契约里没有这两个接口**，所以这一页不摆这两个按钮
 * ——摆了没有地方生效。缺口写在报告里。
 */
import { ref } from "vue";
import { ConsoleGate } from "@pet-health/ui";
import { useAdminSession } from "../session";
import ContentQueuePanel from "../components/ContentQueuePanel.vue";
import SensitiveWordPanel from "../components/SensitiveWordPanel.vue";

const { status } = useAdminSession();

type Tab = "card" | "question" | "answer" | "words";
const tab = ref<Tab>("card");

/** 分段 → 契约的 `content_type` 取值（1 经验卡片 / 2 提问 / 3 回答） */
const CONTENT_TYPE = { card: 1, question: 2, answer: 3 } as const;

/** 面板靠 :key 重建：切类型时把上一类的筛选、分页与驳回输入区一起清掉，不串到另一类上 */
const contentType = ref<number>(CONTENT_TYPE.card);

function selectTab(next: Tab): void {
  tab.value = next;
  if (next !== "words") {
    contentType.value = CONTENT_TYPE[next];
  }
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">内容审核</h2>
    <p class="ph-page-desc">
      社区内容一律先过机审、再进人工队列：卡片 / 提问 / 回答三类各自成队，通过后立刻对其他用户可见，驳回或下架都必须给理由（理由会原样展示给作者）。医疗相关的表述按「宁严勿松」处理：疑似诊断、用药剂量这类内容不放行。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <nav class="ph-tabs" aria-label="内容审核分段">
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'card' }"
          @click="selectTab('card')"
        >
          经验卡片
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'question' }"
          @click="selectTab('question')"
        >
          提问
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'answer' }"
          @click="selectTab('answer')"
        >
          回答
        </button>
        <button
          type="button"
          class="ph-tabs__item"
          :class="{ 'ph-tabs__item--active': tab === 'words' }"
          @click="selectTab('words')"
        >
          敏感词管理
        </button>
      </nav>

      <ContentQueuePanel v-if="tab !== 'words'" :key="contentType" :content-type="contentType" />
      <SensitiveWordPanel v-else />
    </ConsoleGate>
  </section>
</template>
