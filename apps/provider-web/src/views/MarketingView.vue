<script setup lang="ts">
/**
 * 营销中心（交付文档 3.2 P027，路由 /b/marketing）：物料、推广码与活动。
 *
 * 拉新是考核的 40%，链路是「店内铺推广二维码 → 用户扫码注册后归因到门店」。
 * **这条链路 2026-09-30 起是通的**：门店推广码（`provider_invite_code`，V44）落到服务者侧的
 * `GET|POST /invite-code` 上，考核的拉新项也从这批关系里取数（ADR-0052「需要协调」第 1 条已清偿）。
 *
 * 这一页因此有两段：
 *   - **推广码**：没有就生成（幂等）、有就显示码与三个计数（有效 / 观察中 / 无效）。
 *     码是给人抄的（PV + 8 位，去掉了 0/O/1/I/L 这些会看错的字符），所以用等宽字体大号展示；
 *   - **本店拉新在考核里的那一项**（`INVITE` 的原始值、得分、达标线、说明）：
 *     它同时解释了「码扫来的用户什么时候算数」（完成建档 + 24 小时内有行为，不是注册数）。
 */
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { ConsoleGate, ConsoleState, useSubmitAction } from "@pet-health/ui";
import { isIdentityError, toApiFailure, formatDateTime } from "@pet-health/shared";
import { providerApp, type AssessmentItemView, type ProviderInviteCodeView } from "../api/providerApi";
import { useProviderSession } from "../session";
import { assessmentItemLabel, scoreText } from "../utils/labels";

const { status } = useProviderSession();

/** 门店推广码与拉新战况（`code` 为 null = 还没生成过） */
const inviteCode = ref<ProviderInviteCodeView | null>(null);
const codeLoading = ref(false);
const codeError = ref("");
const submit = useSubmitAction("推广码生成失败，请稍后重试");

/** 拉新项来自最近一期的考核明细（列表按账期倒序，第一条就是最近一期） */
const period = ref("");
const inviteItem = ref<AssessmentItemView | null>(null);
const loading = ref(false);
const errorMessage = ref("");
const requestId = ref("");
const forbidden = ref(false);

/** 在飞的那一次：点重试时先作废它（旧响应后回来会把新结果盖掉，与 usePagedList 同一手法） */
let inFlight: AbortController | null = null;

async function loadInviteCode(): Promise<void> {
  codeLoading.value = true;
  codeError.value = "";
  try {
    inviteCode.value = await providerApp.getInviteCode();
  } catch (error) {
    if (isIdentityError(error)) {
      return; // 未登录由闸门说话，这里不额外报错
    }
    codeError.value = toApiFailure(error, "推广码加载失败，请稍后重试").message;
  } finally {
    codeLoading.value = false;
  }
}

/** 生成（或取回）推广码。幂等：已有码时后端返回同一个，不会换码。 */
async function generateCode(): Promise<void> {
  const outcome = await submit.run(() => providerApp.ensureInviteCode(), "推广码已生成");
  if (!outcome.ok) return;
  inviteCode.value = outcome.value;
  // 生成之后考核的拉新项**口径没变**（还是 0 条），但它的说明会从「还没有拉新入口」
  // 变成「有入口但一条没有」——那是两种不同的结论，所以重读一次明细
  void loadInvite();
}

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
    void loadInviteCode();
    void loadInvite();
  }
});
</script>

<template>
  <section>
    <h2 class="ph-page-title">营销中心</h2>
    <p class="ph-page-desc">
      店内推广码与本店拉新成绩。用户扫这个码注册并完成建档、24 小时内有过记录，就算本店一条有效拉新（考核占 40%）。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录服务者账号"
      forbidden-description="服务者后台是独立登录域（ADR-0012），C 端的登录状态在这里不通用——用本端账号在登录页登一次（右上角「登录」）。"
    >
      <section class="ph-card">
        <h3 class="ph-card__title">店内推广码</h3>

        <ConsoleState v-if="codeLoading" variant="loading" title="正在加载推广码" />
        <ConsoleState
          v-else-if="codeError"
          variant="error"
          :title="codeError"
          @retry="loadInviteCode"
        />
        <template v-else-if="inviteCode?.code">
          <p class="ph-marketing__code">{{ inviteCode.code }}</p>
          <dl class="ph-kv">
            <dt>有效邀请</dt>
            <dd>{{ inviteCode.effective_invites ?? 0 }} 人（考核只算这个数）</dd>
            <dt>观察中</dt>
            <dd>{{ inviteCode.pending_invites ?? 0 }} 人（注册后 24 小时内还没看到行为）</dd>
            <dt>已判无效</dt>
            <dd>{{ inviteCode.invalid_invites ?? 0 }} 人（观察期内没有完成建档或没有记录）</dd>
            <dt>生成时间</dt>
            <dd>{{ inviteCode.created_at ? formatDateTime(inviteCode.created_at) : "—" }}</dd>
          </dl>
          <p class="ph-text-weak">
            把码印成桌贴或收银台立牌即可（交付文档 F022 的店内二维码）。码是固定的，重复点「生成」不会换码。
          </p>
        </template>
        <template v-else>
          <p class="ph-text-sub">
            还没有推广码。生成之后印到店里——用户扫码注册，就归因到本店。
          </p>
          <p class="ph-marketing__action">
            <button
              type="button"
              class="ph-button ph-button--primary"
              :disabled="submit.submitting.value"
              @click="generateCode"
            >
              {{ submit.submitting.value ? "生成中…" : "生成推广码" }}
            </button>
          </p>
        </template>

        <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-marketing__alert">
          {{ submit.errorMessage.value }}
        </p>
        <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-marketing__alert">
          {{ submit.doneMessage.value }}
        </p>
      </section>

      <section class="ph-card ph-marketing__card">
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
              这一项未参与计分。上面那段「说明」写明了这次是哪一种原因：**还没有推广码**（平台侧
              无该维度要求）还是**规则里没配达标线**。两种情况都不记 0 分，权重按参与项重算（ADR-0050 第四节）。
            </p>
            <p v-else class="ph-text-weak">
              有效邀请的口径：被邀请人完成建档且在 24 小时内有行为（ADR-0039 第一节）——不是注册数。
            </p>
          </template>
        </template>
      </section>

      <section class="ph-card ph-marketing__card">
        <h3 class="ph-card__title">物料与活动的边界</h3>
        <p class="ph-text-sub">
          推广码已经在上面了（<strong>2026-09-30 起链路已通</strong>：V44 的门店归因 + 服务者侧
          `GET|POST /invite-code`，考核的拉新项也从这批关系取数）。这一格说明**还没有**的部分：
        </p>
        <ul class="ph-marketing__list">
          <li>
            <strong>没有二维码图片下载</strong>：契约只给码本身，没有生成二维码图的接口。要图得自己
            拿码去生成（码是给人抄的，PV + 8 位）。
          </li>
          <li>
            <strong>没有物料模板与活动管理</strong>：交付文档 P027 的「物料 / 活动」需要素材库与活动
            表，两样都还没有——本切片只闭合了推广码与拉新数据这一半。
          </li>
          <li>
            <strong>反作弊是薄的一层</strong>：用户邀请码有「自邀自 / 同设备 / 同 IP 同号段」三条判据，
            而门店码背后没有主人账号，这三条用不上。门店码现在只靠 24 小时观察窗与「完成建档 + 有行为」
            这条有效判据拦刷号（已知宽松，口径写在该服务类的注释里）。
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

/* 推广码是给人抄到纸上、再印成桌贴的：等宽 + 大号 + 字距拉开，避免抄错 */
.ph-marketing__code {
  margin: var(--ph-space-2) 0 var(--ph-space-4);
  font-family: var(--ph-font-numeric);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: var(--ph-color-primary);
}

.ph-marketing__action {
  margin: var(--ph-space-4) 0 0;
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
