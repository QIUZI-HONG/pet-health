<script setup lang="ts">
/**
 * 积分与邀请配置（路由 `/admin/points-invite`）：ADR-0010 第二层里「增长侧」的写入口。
 *
 * 在此之前这些配置**在本 app 里一页都没有**（系统配置页的自述原文：「`/invites/ladder-tiers/{threshold}`
 * 不在本 app 任何一页」）——券模板有自己的页（「券池管理」），而积分上限、行为分值、兑换档位、
 * 月度阶梯、邀请阶梯这五组只能直连 API。它们都是 ADR-0010 说的「业务可调项」：入库 + 运营后台，
 * 改完即时生效，所以这一页是配置面而不是只读看板。
 *
 * 五段在一页，因为它们共用同一批参照数据（券模板、权益码）：兑换档位与两处阶梯都要选券模板，
 * 邀请阶梯还要选权益码。在最外层加载一次传下去，与券池页加载分类的理由相同——面板各拉一次
 * 不仅多几次往返，还会出现「券模板刚改完、表单里还是旧的」。
 *
 * **两处刻意不做**：
 * - **不预置任何档位、分值、奖励**（ADR-0046）：门槛值与奖励没有规则依据，不编。种子里本来就只有
 *   行为分值表与固定五档的门槛，「发什么」「多少分换什么」都留空，由运营填；
 * - **积分任务（`/points/tasks`）不在这里**：它不是这一波要补的三类配置，且任务要引用已有的行为码、
 *   与批算挂钩，接它需要先想清楚「任务与行为分值谁说话」——契约里留了接口，页面留到需要时再加。
 */
import { onMounted, ref } from "vue";
import { ConsoleGate, ConsoleState } from "@pet-health/ui";
import { isIdentityError, toApiFailure } from "@pet-health/shared";
import { adminApp, type CouponTemplateRow, type RightsCodeRow } from "../api/adminApi";
import { useAdminSession } from "../session";
import PointsSettingsPanel from "../components/PointsSettingsPanel.vue";
import PointBehaviorPanel from "../components/PointBehaviorPanel.vue";
import PointExchangeOptionPanel from "../components/PointExchangeOptionPanel.vue";
import PointLadderTierPanel from "../components/PointLadderTierPanel.vue";
import InviteLadderTierPanel from "../components/InviteLadderTierPanel.vue";

const { status } = useAdminSession();

type Tab = "settings" | "behaviors" | "exchange" | "ladder" | "invite-ladder";
const tab = ref<Tab>("settings");

/**
 * 参照数据：券模板与权益码。
 *
 * 券模板一次拉全量（启用中，最多 100 条 = 契约的 page_size 上限）：积分侧只从中挑平台补贴券
 * （`cost_bearer = 2`），邀请侧要所有启用中的模板——两处的口径不同，所以在这里筛出两份，
 * 而不是让两个面板各拉一次列表。
 */
const templates = ref<CouponTemplateRow[]>([]);
const rightsCodes = ref<RightsCodeRow[]>([]);
// 初值就是「加载中」：请求在 onMounted 才发，先摆 false 会让面板先挂一次、再被卸载、再挂回来
// （两次请求，界面上还闪一下），而「还没读回来」本来就等于加载中
const refLoading = ref(true);
const refForbidden = ref(false);
const refError = ref("");
const refRequestId = ref("");

function subsidyTemplates(): CouponTemplateRow[] {
  return templates.value.filter((item) => item.cost_bearer === 2);
}

async function loadRefs(): Promise<void> {
  refLoading.value = true;
  refForbidden.value = false;
  refError.value = "";
  refRequestId.value = "";
  try {
    const [page, codes] = await Promise.all([
      adminApp.listCouponTemplates({ status: 1, pageSize: 100 }),
      adminApp.listRightsCodes(),
    ]);
    templates.value = page.list;
    rightsCodes.value = codes;
  } catch (error) {
    // 没权限与失败分开：重试对前者没有意义（口径与 useSection 一致）
    if (isIdentityError(error)) {
      refForbidden.value = true;
      return;
    }
    const failure = toApiFailure(error, "券模板与权益码加载失败，请稍后重试");
    refError.value = failure.message;
    refRequestId.value = failure.requestId;
  } finally {
    refLoading.value = false;
  }
}

onMounted(() => {
  // 有本域令牌才发请求：未登录时先让闸门说话，别用一串 40100 盖住它
  if (status.value === "authenticated") {
    void loadRefs();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">积分与邀请配置</h2>
    <p class="ph-page-desc">
      增长侧的业务可调项：积分规则（每日上限）、行为分值、兑换档位、月度阶梯档位、邀请阶梯奖励。这些都在库里（ADR-0010 第二层），改完即时生效——下一笔发放、下一次批算就按新配置走（历史流水与已达成的档位不重算）。档位与奖励**不预置任何数值**：门槛与奖励没有规则依据，不编（ADR-0046）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <p class="ph-alert ph-alert--info ph-growth__notice">
        <strong>这些配置会立刻影响线上的发放行为</strong>：积分上限决定用户攒分速度，行为分值一改下一笔发放就按新值，
        阶梯与兑换档位决定「达成什么、拿到什么」。所以每个写操作都有成功 / 失败反馈，
        并且**不预置数值**——你填的每一个数都会真的发出去。
      </p>

      <nav class="ph-tabs" aria-label="积分与邀请配置分段">
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'settings' }" @click="tab = 'settings'">
          积分规则
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'behaviors' }" @click="tab = 'behaviors'">
          行为分值
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'exchange' }" @click="tab = 'exchange'">
          兑换档位
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'ladder' }" @click="tab = 'ladder'">
          月度阶梯
        </button>
        <button type="button" class="ph-tabs__item" :class="{ 'ph-tabs__item--active': tab === 'invite-ladder' }" @click="tab = 'invite-ladder'">
          邀请阶梯
        </button>
      </nav>

      <!-- 参照数据（券模板 / 权益码）：三段要用它挑券与挑权益码，所以四态在面板之上 -->
      <ConsoleState
        v-if="refLoading"
        variant="loading"
        title="正在加载券模板与权益码"
      />
      <ConsoleState
        v-else-if="refForbidden"
        variant="forbidden"
        title="暂无权限"
        description="这个运营账号的令牌不能读取券模板或权益码——兑换档位与两个阶梯都要用它们做参照。"
      />
      <ConsoleState
        v-else-if="refError"
        variant="error"
        :title="refError"
        :request-id="refRequestId"
        @retry="loadRefs"
      />
      <template v-else>
        <PointsSettingsPanel v-if="tab === 'settings'" />
        <PointBehaviorPanel v-else-if="tab === 'behaviors'" />
        <PointExchangeOptionPanel v-else-if="tab === 'exchange'" :templates="subsidyTemplates()" />
        <PointLadderTierPanel v-else-if="tab === 'ladder'" :templates="subsidyTemplates()" />
        <InviteLadderTierPanel v-else :templates="templates" :rights-codes="rightsCodes" />
      </template>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-growth__notice {
  margin-bottom: var(--ph-space-4);
  max-width: 880px;
}
</style>
