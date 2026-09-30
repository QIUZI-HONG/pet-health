<script setup lang="ts">
/**
 * 营销中心（交付文档 3.2 P027，路由 /b/marketing）：物料、推广码与活动。
 *
 * 拉新是考核的 40%，链路是「店内铺推广二维码 → 用户扫码注册后把这个服务者绑成推荐人」。
 * **但服务者侧的推广码 / 拉新数据接口在契约里不存在**（`contract/provider.yaml` 里没有 invite 一组），
 * 而这条链路的口径本身也还没定：邀请关系现在是**用户对用户**（`invite_relation.inviter_user_id`
 * 是用户，不是门店），门店维度的绑定与归因没裁决——这正是 ADR-0052「需要协调」的第 1 条，
 * 也是 `ProviderGrowthFactsApi`（拉新与券的只读事实接口）至今没有接线的原因。
 *
 * 所以这一页的做法是：
 *   - **不摆「生成推广码 / 下载二维码」的假按钮**，也不放一个二维码占位图——那会让人以为链路已经通了；
 *   - **能接的接上**：本店拉新在**考核明细**里的那一项（`INVITE` 的原始值、得分、达标线、说明），
 *     这是服务者现在唯一能看到自己拉新成绩的地方（考核侧的口径见 ADR-0039 第三节）；
 *   - **缺口的影响写清**：没有拉新入口时，考核的拉新项按「平台侧无该维度要求」**不参与、权重按
 *     参与项重算**（ADR-0050 第四节），不会静默记 0 分——服务者该知道这一点，否则会以为被扣了分。
 */
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import { isIdentityError, toApiFailure } from "@pet-health/shared";
import { providerApp, type AssessmentItemView } from "../api/providerApi";
import { useProviderSession } from "../session";
import { assessmentItemLabel, scoreText } from "../utils/labels";

const { status } = useProviderSession();

/** 拉新项来自最近一期的考核明细（列表按账期倒序，第一条就是最近一期） */
const period = ref("");
const inviteItem = ref<AssessmentItemView | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const forbidden = ref(false);

/** 在飞的那一次：点重试时先作废它（旧响应后回来会把新结果盖掉，与 usePagedList 同一手法） */
let inFlight: AbortController | null = null;

async function loadInvite(): Promise<void> {
  inFlight?.abort();
  const controller = new AbortController();
  inFlight = controller;
  loading.value = true;
  errorMessage.value = "";
  requestId.value = "";
  forbidden.value = false;
  try {
    const page = await providerApp.listAssessments({ page: 1, pageSize: 1 }, controller.signal);
    if (controller.signal.aborted) return;
    const latest = page.list[0];
    if (!latest) {
      inviteItem.value = null;
      period.value = "";
      return;
    }
    period.value = latest.period;
    const detail = await providerApp.getAssessment(latest.period, controller.signal);
    if (controller.signal.aborted) return;
    inviteItem.value = (detail.items ?? []).find((item) => item.item_code === "INVITE") ?? null;
  } catch (error) {
    if (controller.signal.aborted) return;
    if (isIdentityError(error)) {
      forbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "拉新成绩加载失败，请稍后重试");
    errorMessage.value = failure.message;
    requestId.value = failure.requestId;
  } finally {
    if (!controller.signal.aborted) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    void loadInvite();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">营销中心</h2>
    <p class="ph-page-desc">
      拉新的成绩、以及在店里铺物料要做的事。推广码与物料下载需要服务者侧的邀请接口，目前还没有（见下方说明）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <section class="ph-card">
        <h3 class="ph-card__title">本店拉新（考核口径）</h3>

        <ConsoleState v-if="loading" variant="loading" title="正在加载拉新成绩" />
        <ConsoleState
          v-else-if="forbidden"
          variant="forbidden"
          title="暂无权限"
          description="这个账号的令牌不能读取本店考核明细。"
        />
        <ConsoleState
          v-else-if="errorMessage"
          variant="error"
          :title="errorMessage"
          :request-id="requestId"
          @retry="loadInvite"
        />
        <template v-else>
          <p v-if="!inviteItem" class="ph-text-sub">
            还没有考核记录：拉新成绩随月度考核一起算（每月 1 日算上一个自然月）。
          </p>
          <!-- 用一层 template 把「有拉新项」的两段收进同一个 else 分支：v-if 与 v-else 必须相邻，
               否则 inviteItem 为 null 时下面的 `inviteItem.participated` 会在渲染期直接抛错 -->
          <template v-else>
            <dl class="ph-kv">
              <dt>账期</dt>
              <dd>{{ period }}</dd>
              <dt>考核项</dt>
              <dd>{{ assessmentItemLabel(inviteItem.item_code, inviteItem.item_name) }}（权重 {{ inviteItem.weight ?? "—" }}%）</dd>
              <dt>本期成绩</dt>
              <dd>
                {{ inviteItem.participated === false ? "未参与计分" : (inviteItem.raw_value ?? "—") }}
                <span class="ph-text-weak">得分 {{ inviteItem.score ? scoreText(inviteItem.score) : "未参与" }}</span>
              </dd>
              <dt>达标线</dt>
              <dd>{{ inviteItem.target_value ?? "—" }}</dd>
              <dt>数据来源</dt>
              <dd>{{ inviteItem.data_source ?? "—" }}</dd>
              <dt>说明</dt>
              <dd>{{ inviteItem.note ?? "—" }}</dd>
            </dl>
            <p v-if="inviteItem.participated === false" class="ph-alert ph-alert--warn ph-marketing__alert">
              这一项未参与计分：平台侧还没有给本店拉新的入口（店内二维码 / 推广码的归属口径未定），
              所以权重按参与项重算，而不是把拉新记成 0 分（ADR-0050 第四节）。
            </p>
            <p v-else class="ph-text-weak">
              有效邀请的口径：被邀请人完成建档且在 24 小时内有行为（ADR-0039 第一节）——不是注册数。
            </p>
          </template>
        </template>
      </section>

      <section class="ph-card ph-marketing__card">
        <h3 class="ph-card__title">推广码与店内物料（缺接口）</h3>
        <p class="ph-text-sub">
          这一格暂时是空的，而且是故意空的：`contract/provider.yaml` 里没有服务者侧的邀请接口
          （邀请码 / 拉新明细 / 物料下载都没有），也没有可供本端调用的素材地址。摆一个点不动的
          「生成推广码」按钮或一张二维码占位图，只会让人以为链路已经通了——真正的阻塞不在界面：
        </p>
        <ul class="ph-marketing__list">
          <li>
            邀请关系现在是用户对用户（`invite_relation.inviter_user_id` 是用户），而拉新考核要的是
            「这个人是由哪家门店带来的」——门店维度的绑定与归因口径未裁决（ADR-0052 的「需要协调」第 1 条）；
          </li>
          <li>
            口径定了之后，服务者侧才会有「我的推广码 / 我的拉新明细」这组接口（`ProviderGrowthFactsApi`
            是考核侧读这些事实的入口，目前未接线）；
          </li>
          <li>
            在那之前，本店能做的拉新动作是：把平台给的用户侧邀请机制介绍给到店客户（用户之间互相邀请，
            ADR-0039 第一节），但它不计入门店的拉新考核——考核里那一项目前按「无该维度要求」不参与。
          </li>
        </ul>
      </section>

      <section class="ph-card ph-marketing__card">
        <h3 class="ph-card__title">可用素材：券池里的券</h3>
        <p class="ph-text-sub">
          平台给用户的券（邀请 / 打卡任务 / 积分兑换的来源）来自券池；服务者成本的那部分由服务者
          承诺额度，也就是「我店愿意接多少张」。想用券做店内活动时，入口在券管理——这一页不重复一遍
          券的额度账，也不做券的发放（发放是平台的事，ADR-0037 第三节）。
        </p>
        <p class="ph-marketing__link"><RouterLink :to="{ name: 'coupons' }">去券管理看券池与我的贡献 →</RouterLink></p>
      </section>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-marketing__card {
  margin-top: var(--ph-space-5);
}

.ph-marketing__alert {
  margin-top: var(--ph-space-4);
}

.ph-marketing__list {
  margin: 0;
  padding-left: var(--ph-space-5);
  color: var(--ph-color-text-sub);
  font-size: 13px;
  line-height: 1.8;
}

.ph-marketing__link {
  margin: var(--ph-space-3) 0 0;
}
</style>
