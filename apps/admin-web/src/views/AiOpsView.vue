<script setup lang="ts">
/**
 * AI 运营（路由 `/admin/ai-ops`）：ADR-0010 第二层（业务可调项）的写入口。
 *
 * 在此之前，改红线词只能直连 API 或改库——**与 ADR-0010 的分层不符**（业务可调项要入库 + 运营后台），
 * 而这一层的每一项都会立刻改变线上对用户的答复：提示词版本（跑的是哪一版、灰度多少）、
 * 硬红线词（命中即短路、不经过模型）、分级规则（命中词抬风险下限）、护栏词（输出侧过滤药名与越界表述）、
 * 运行时开关（一键降级）。所以这一页的性质是**配置面**，不是只读看板。
 *
 * 六段在一页，因为它们同属一组配置、且读的是同一批表（`knowledge_*`，Python 带 TTL 缓存直读）：
 * 改任何一段都是「即时生效、最多滞后一个 TTL」。分成六页只会让「刚才改的是哪一段」更难对。
 *
 * <p>第六段「知识条目」与那五段不同：改的是**内容**而不是调参，而且改的是「能不能进引用」——
 * 它没有 TTL 一说，AI 服务每次检索都直读库。
 *
 * 两处**刻意不做**，都是契约里没有的能力：
 * - 没有「新建开关」：开关的语义在代码里，加一个没人读的 code 比没有开关更糟（ADR-0010）；
 * - 没有「改复核状态」的**通用**入口：`review_status` 是人工复核的产物，通用的配置接口给它开后门
 *   等于把背书降格成一次开关操作（ADR-0040 第二节）。第六段「知识条目」是**唯一**的复核入口，
 *   且必须填复核人与资质（ADR-0054）。
 */
import { ref } from "vue";
import { ConsoleGate } from "@pet-health/ui";
import { useAdminSession } from "../session";
import AiPromptPanel from "../components/AiPromptPanel.vue";
import AiRedFlagPanel from "../components/AiRedFlagPanel.vue";
import AiGradingRulePanel from "../components/AiGradingRulePanel.vue";
import AiGuardTermPanel from "../components/AiGuardTermPanel.vue";
import AiSwitchPanel from "../components/AiSwitchPanel.vue";
import AiKnowledgePanel from "../components/AiKnowledgePanel.vue";

const { status } = useAdminSession();

type Tab = "prompts" | "red-flags" | "grading" | "guard-terms" | "switches" | "knowledge";
const tab = ref<Tab>("prompts");
</script>

<template>
  <section>
    <h2 class="ph-page-title">AI 运营</h2>
    <p class="ph-page-desc">
      AI 的业务可调项：提示词版本、硬红线词、分级规则、护栏词、运行时开关。这些**全都在库里**（ADR-0010 的第二层），改完即时生效——AI 服务带 TTL 缓存直读，最多滞后一个 TTL（默认 60 秒），不需要重启进程。技术参数（模型名、超时、日预算、API Key）不在这里，它们在环境变量里：那些改错一次会全线超时，不该由运营在界面上随手改。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <p class="ph-alert ph-alert--warn ph-aiops__notice">
        <strong>这一页改的是线上行为</strong>：红线词少一条就多一类不被短路的咨询，护栏词停一个就多一类可能出口的表述，
        开关一开一关直接改变 AI 通道（例如 `force_rule_only` 打开后全量走规则通道、不调模型）。
        每个写操作都有成功 / 失败反馈，危险动作（停用红线、切换开关）要二次确认。
      </p>

      <nav class="ph-tabs" aria-label="AI 运营分段">
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'prompts' }" @click="tab = 'prompts'">
          提示词版本
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'red-flags' }" @click="tab = 'red-flags'">
          硬红线词
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'grading' }" @click="tab = 'grading'">
          分级规则
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'guard-terms' }" @click="tab = 'guard-terms'">
          护栏词
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'switches' }" @click="tab = 'switches'">
          运行时开关
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'knowledge' }" @click="tab = 'knowledge'">
          知识条目
        </button>
      </nav>

      <!-- 面板靠 v-if 挂载与卸载：每段自己加载自己的数据，切回来就是重新读一遍（配置面最怕看的是旧值） -->
      <AiPromptPanel v-if="tab === 'prompts'" />
      <AiRedFlagPanel v-else-if="tab === 'red-flags'" />
      <AiGradingRulePanel v-else-if="tab === 'grading'" />
      <AiGuardTermPanel v-else-if="tab === 'guard-terms'" />
      <AiSwitchPanel v-else-if="tab === 'switches'" />
      <AiKnowledgePanel v-else />

      <div class="ph-card ph-aiops__gap">
        <h4 class="ph-card__title">这一段没做的</h4>
        <ul class="ph-aiops__list">
          <li>
            **调用留痕抽检**（`GET /api/v1/admin/ai/consults`：按 `prompt_version`、风险等级、是否降级查）
            有接口没有页——它是排查面，不是配置面，且不含问题原文（`question_enc` 是字段级加密的病历口径，
            解密给运营看属权限与合规问题、还没定，ADR-0033 的待澄清），所以这一波不摆它。
          </li>
          <li>
            **复核状态不可改**（`review_status`）：人工复核是流程，代码里给它开个后门等于假造复核状态
            （ADR-0040 第二节），契约里也没有这个接口——三段列表里它只展示。
          </li>
          <li>
            **工具定义（`tool_schema`）只读**：归代码常量那一层，改动走发版（ADR-0010）；新建版本时照抄一份。
          </li>
        </ul>
      </div>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-aiops__notice {
  margin-bottom: var(--ph-space-4);
  max-width: 880px;
}

.ph-aiops__gap {
  margin-top: var(--ph-space-6);
}

.ph-aiops__list {
  margin: var(--ph-space-2) 0 0;
  padding-left: var(--ph-space-5);
  font-size: 13px;
  line-height: 1.9;
  color: var(--ph-color-text-sub);
}
</style>
