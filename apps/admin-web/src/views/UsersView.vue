<script setup lang="ts">
/**
 * 用户管理（交付文档 3.1 模块树「用户管理 / 全数据」，仅平台运营与超级管理员）。
 *
 * **先说这一页最大的缺口：契约里没有「用户」这个资源。** `contract/admin.yaml` 里只有
 * `/rights/users/{user_id}` 这一条带 user_id 的路径，没有 `/users` 列表、没有用户详情、
 * 也没有手机号字段——所以这一页做不到「按手机号 / 昵称搜一个用户」，只能**按用户 id 反查**
 * 他名下的三块数据（积分流水、权益、邀请关系）。不摆一个搜不出东西的搜索框：那种界面会让运营
 * 以为「搜不到 = 这个用户不存在」，而真相是「这个接口不存在」。
 *
 * 因此「手机号脱敏」在这一页的落点是**不显示**：这些接口一个都不下发手机号，页面上也就没有
 * 需要脱敏的号码。契约里带手机号的只有两处（入驻申请的 `contact_phone`、门店的 `phone`），
 * 两处都标着「脱敏」，我们照原样展示、不拼完整号码（ADR-0013 的字段级加密下，前端也拼不出来）。
 *
 * 三块只读视图都是现有接口，不是为这一页新造的：
 *   - 积分：`GET /points/records?user_id=`（每条带变动后余额，所以最新一条的 `balance_after`
 *     就是当前余额——契约里没有「单用户积分账户」接口，`/points/overview` 是全平台的）；
 *   - 权益：`GET /rights/users/{user_id}`（判定出口，`effective=false` 的也列出来）+
 *     `GET /rights/grants?user_id=`（每条来源各一条，可以解释「这个码是哪来的」）；
 *   - 邀请：`GET /invites/relations`（按 inviter / invitee 两个方向各查一次）。
 *
 * 处置动作（禁用 / 注销）**这一页没有**：契约里没有用户状态接口。注销还是唯一会物理删除数据的
 * 场景（docs/conventions.md），没有一个「谁操作的、为什么」都留得下来的接口之前，不该给这个按钮。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleGate, ConsoleListState, ConsoleState, usePagedList } from "@pet-health/ui";
import { adminApp, type GrantRow, type InviteRelationRow, type PointRecordRow, type RightsEvaluationView } from "../api/adminApi";
import { useSection } from "../composables/useSection";
import { useAdminSession } from "../session";

const { status } = useAdminSession();

type Tab = "points" | "rights" | "invites";
const tab = ref<Tab>("points");

/** 输入框里的 id（可能是错的）与**已经查过的** id（加载器只读后者）分开，否则边输边改会让列表错位 */
const userIdInput = ref("");
const queriedUserId = ref<number | null>(null);
const localError = ref("");

const behaviorFilter = ref("");
/** 邀请关系的方向：他是邀请人，还是被邀请人——这是两个不同的问题 */
const relationRole = ref<"inviter" | "invitee">("inviter");

const points = usePagedList<PointRecordRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listPointRecords(
      {
        userId: queriedUserId.value ?? undefined,
        behaviorCode: behaviorFilter.value || undefined,
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "积分流水加载失败，请稍后重试" },
);

const rights = useSection<RightsEvaluationView>();
const grants = usePagedList<GrantRow>(
  ({ page, pageSize }, signal) => adminApp.listRightsGrants({ userId: queriedUserId.value ?? undefined, page, pageSize }, signal),
  { failureText: "权益授予记录加载失败，请稍后重试" },
);

const relations = usePagedList<InviteRelationRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listInviteRelations(
      relationRole.value === "inviter"
        ? { inviterUserId: queriedUserId.value ?? undefined, page, pageSize }
        : { inviteeUserId: queriedUserId.value ?? undefined, page, pageSize },
      signal,
    ),
  { failureText: "邀请关系加载失败，请稍后重试" },
);

/** 权益判定单独一个加载口：它没有分页，重试只该重读这一块，不必把三块一起重来 */
async function loadRights(): Promise<void> {
  await rights.load(() => adminApp.getUserRights(queriedUserId.value!), "权益判定加载失败，请稍后重试");
}

/** 查询：三块一起取回来（都是同一个用户的，切分段不再发请求；每块各自四态，互不掩盖） */
async function query(): Promise<void> {
  const text = userIdInput.value.trim();
  if (!/^\d+$/.test(text) || Number(text) <= 0) {
    localError.value = "请填一个正整数用户 id：契约里没有按手机号 / 昵称搜索的接口，只能按 id 查。";
    return;
  }
  localError.value = "";
  queriedUserId.value = Number(text);
  behaviorFilter.value = "";
  relationRole.value = "inviter";
  void points.load();
  void grants.load();
  void relations.load();
  await loadRights();
}

/** 积分流水里最新一条的余额就是当前余额（流水带 `balance_after` 正是为了能这样重建） */
function currentBalance(): string {
  const first = points.items.value[0];
  if (!first) return "无记录";
  return String(first.balance_after);
}

function behaviorLabel(code: string): string {
  switch (code) {
    case "SIGN_IN":
      return "签到";
    case "CHECK_IN":
      return "打卡";
    case "INVITE":
      return "邀请";
    case "REVIEW":
      return "评价晒单";
    case "PROFILE_COMPLETE":
      return "完善档案";
    case "AI_ADVICE":
      return "查看 AI 建议";
    case "SHARE":
      return "分享";
    default:
      return code;
  }
}

function grantSourceLabel(source: number): string {
  switch (source) {
    case 1:
      return "订阅";
    case 2:
      return "邀请";
    case 3:
      return "打卡";
    case 4:
      return "运营补偿";
    default:
      return "—";
  }
}

function relationStatusLabel(value: number): string {
  switch (value) {
    case 1:
      return "待生效";
    case 2:
      return "有效";
    case 3:
      return "无效";
    default:
      return "—";
  }
}

function relationStatusTone(value: number): string {
  if (value === 2) return "ph-tag--success";
  if (value === 3) return "ph-tag--danger";
  return "ph-tag--info";
}

function channelLabel(value?: number | null): string {
  if (value === 1) return "分享链接（预填）";
  if (value === 2) return "注册表单手填";
  return "—";
}
</script>

<template>
  <section>
    <h2 class="ph-page-title">用户管理</h2>
    <p class="ph-page-desc">
      按用户 id 查看他名下的积分、权益与邀请关系（只读）。这一页不含处置动作：契约里没有用户状态接口，注销这种会物理删除数据的动作更是要先把「谁操作的、为什么」留得下来的接口定下来。
    </p>

    <ConsoleGate
      :status="status"
      forbidden-title="尚未登录运营账号"
      forbidden-description="运营后台是独立登录域（ADR-0012），其它端的登录状态在这里不通用——用运营账号在登录页登一次（右上角「登录」）。"
    >
      <div class="ph-toolbar">
        <label class="ph-field ph-users__filter">
          <span class="ph-field__label">用户 id</span>
          <input
            v-model="userIdInput"
            class="ph-input ph-users__id"
            inputmode="numeric"
            placeholder="如：1001"
            @keyup.enter="query"
          />
        </label>
        <button type="button" class="ph-button ph-button--primary" @click="query">查询</button>
        <span class="ph-toolbar__spacer" />
        <span class="ph-text-weak">只读视图</span>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-users__alert">{{ localError }}</p>

      <p class="ph-field__hint ph-users__gap">
        没有「按手机号 / 昵称搜索」：`contract/admin.yaml` 里没有用户资源（没有 `/users` 列表与详情），
        只有 `/rights/users/{user_id}` 这一条带 user_id 的路径，所以只能按 id 查。
        这一页也**不显示手机号**——这几个接口都不下发手机号，页面上没有可脱敏的号码；
        契约里带手机号的两处（入驻申请的 `contact_phone`、门店的 `phone`）标着「脱敏」，照原样显示、不拼完整号码。
      </p>

      <ConsoleState
        v-if="queriedUserId === null"
        variant="empty"
        title="先填一个用户 id 查询"
        description="查出来的是那个用户名下的积分流水、权益判定与邀请关系三块只读数据。"
      />

      <template v-else>
        <nav class="ph-tabs" aria-label="用户数据分段">
          <button
            type="button"
            class="ph-tabs__item"
            :class="{ 'ph-tabs__item--active': tab === 'points' }"
            @click="tab = 'points'"
          >
            积分
          </button>
          <button
            type="button"
            class="ph-tabs__item"
            :class="{ 'ph-tabs__item--active': tab === 'rights' }"
            @click="tab = 'rights'"
          >
            权益
          </button>
          <button
            type="button"
            class="ph-tabs__item"
            :class="{ 'ph-tabs__item--active': tab === 'invites' }"
            @click="tab = 'invites'"
          >
            邀请关系
          </button>
        </nav>

        <div v-if="tab === 'points'">
          <div class="ph-toolbar">
            <label class="ph-field ph-users__filter">
              <span class="ph-field__label">行为</span>
              <select v-model="behaviorFilter" class="ph-select" @change="points.reload">
                <option value="">全部</option>
                <option value="SIGN_IN">签到</option>
                <option value="CHECK_IN">打卡</option>
                <option value="INVITE">邀请</option>
                <option value="REVIEW">评价晒单</option>
                <option value="PROFILE_COMPLETE">完善档案</option>
                <option value="AI_ADVICE">查看 AI 建议</option>
                <option value="SHARE">分享</option>
              </select>
            </label>
            <span class="ph-text-weak">
              当前余额 {{ currentBalance() }}（取自最新一条流水的变动后余额：契约里没有单用户积分账户接口）
            </span>
          </div>

          <ConsoleListState
            :loading="points.loading.value"
            :forbidden="points.forbidden.value"
            :error-message="points.errorMessage.value"
            :request-id="points.requestId.value"
            :is-empty="points.isEmpty.value"
            loading-title="正在加载积分流水"
            forbidden-title="暂无权限"
            forbidden-description="这个运营账号的令牌不能读取积分流水。"
            empty-title="这个用户还没有积分流水"
            empty-description="换个人查，或把行为筛选放回「全部」。"
            @retry="points.reload"
          >
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>行为</th>
                    <th>变动</th>
                    <th>变动后余额</th>
                    <th>占每日上限</th>
                    <th>来源引用</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in points.items.value" :key="row.id">
                    <td class="ph-table__num">{{ formatDateTime(row.created_at) }}</td>
                    <td>{{ row.behavior_name ?? behaviorLabel(row.behavior_code) }}</td>
                    <td class="ph-table__num">{{ row.change > 0 ? `+${row.change}` : row.change }}</td>
                    <td class="ph-table__num">{{ row.balance_after }}</td>
                    <td>{{ row.counts_toward_daily_cap === 1 ? "占" : "不占" }}</td>
                    <td>{{ row.source_ref ?? "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="ph-pager">
              <span>共 {{ points.total.value }} 条</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="points.page.value <= 1 || points.loading.value"
                @click="points.prevPage"
              >
                上一页
              </button>
              <span>第 {{ points.page.value }} 页</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="!points.hasMore.value || points.loading.value"
                @click="points.nextPage"
              >
                下一页
              </button>
            </div>
          </ConsoleListState>
        </div>

        <div v-else-if="tab === 'rights'">
          <h4 class="ph-card__title ph-users__sub">权益判定（实时）</h4>
          <p class="ph-field__hint ph-users__gap">
            这是权益引擎**唯一的口径出口**（ADR-0038 第三节）：来源优先级 订阅 &gt; 邀请永久 &gt; 打卡当月，
            取生效的授予里优先级最高的那一条，不按到期时间比较。不生效的码也列出来——否则运营看到的是
            「这个用户什么都没有」，而真相是「有码但当前不生效」。
          </p>
          <ConsoleListState
            :loading="rights.loading.value"
            :forbidden="rights.forbidden.value"
            :error-message="rights.errorMessage.value"
            :request-id="rights.requestId.value"
            :is-empty="rights.loaded.value && (rights.data.value?.rights ?? []).length === 0"
            loading-title="正在加载权益判定"
            forbidden-title="暂无权限"
            forbidden-description="这个运营账号的令牌不能读取这个用户的权益判定。"
            empty-title="权益码表里没有码"
            empty-description="判定结果为空说明权益码表本身是空的，不是这个用户没问题。"
            @retry="loadRights"
          >
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>权益码</th>
                    <th>名称</th>
                    <th>是否生效</th>
                    <th>生效来源</th>
                    <th>到期</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in rights.data.value?.rights ?? []" :key="item.code ?? ''">
                    <td class="ph-table__num">{{ item.code }}</td>
                    <td>{{ item.name }}</td>
                    <td>
                      <span class="ph-tag" :class="item.effective ? 'ph-tag--success' : 'ph-tag--warning'">
                        {{ item.effective ? "生效" : "不生效" }}
                      </span>
                    </td>
                    <td>{{ item.source_name ?? "—" }}</td>
                    <td class="ph-table__num">{{ item.expire_at ? formatDateTime(item.expire_at) : "永久" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </ConsoleListState>

          <h4 class="ph-card__title ph-users__sub">权益授予记录</h4>
          <p class="ph-field__hint ph-users__gap">
            每条来源各写一条授予（ADR-0038 第三节）：同时有「邀请得的永久」与「订阅得的月度」时是两行，
            不合并、不覆盖——合并之后「订阅到期要不要回收」就分不清是哪一条了。
          </p>
          <ConsoleListState
            :loading="grants.loading.value"
            :forbidden="grants.forbidden.value"
            :error-message="grants.errorMessage.value"
            :request-id="grants.requestId.value"
            :is-empty="grants.isEmpty.value"
            loading-title="正在加载授予记录"
            forbidden-title="暂无权限"
            forbidden-description="这个运营账号的令牌不能读取权益授予记录。"
            empty-title="没有授予记录"
            empty-description="这个用户没有过任何权益授予（订阅 / 邀请 / 打卡 / 运营补偿）。"
            @retry="grants.reload"
          >
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>权益码</th>
                    <th>来源</th>
                    <th>到期</th>
                    <th>状态</th>
                    <th>来源引用</th>
                    <th>授予时间</th>
                    <th>回收时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in grants.items.value" :key="row.id">
                    <td class="ph-table__num">{{ row.code }}</td>
                    <td>{{ grantSourceLabel(row.source) }}</td>
                    <td class="ph-table__num">{{ row.expire_at ? formatDateTime(row.expire_at) : "永久" }}</td>
                    <td>
                      <span class="ph-tag" :class="row.status === 1 ? 'ph-tag--success' : 'ph-tag--warning'">
                        {{ row.status === 1 ? "生效" : "已回收" }}
                      </span>
                    </td>
                    <td>{{ row.source_ref ?? "—" }}</td>
                    <td class="ph-table__num">{{ formatDateTime(row.created_at) }}</td>
                    <td class="ph-table__num">{{ row.revoked_at ? formatDateTime(row.revoked_at) : "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="ph-pager">
              <span>共 {{ grants.total.value }} 条</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="grants.page.value <= 1 || grants.loading.value"
                @click="grants.prevPage"
              >
                上一页
              </button>
              <span>第 {{ grants.page.value }} 页</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="!grants.hasMore.value || grants.loading.value"
                @click="grants.nextPage"
              >
                下一页
              </button>
            </div>
          </ConsoleListState>
        </div>

        <div v-else>
          <div class="ph-toolbar">
            <label class="ph-field ph-users__filter">
              <span class="ph-field__label">看哪个方向</span>
              <select v-model="relationRole" class="ph-select" @change="relations.reload">
                <option value="inviter">他邀请了谁</option>
                <option value="invitee">他是谁邀请来的</option>
              </select>
            </label>
            <span class="ph-text-weak">归因时点是注册那一刻，之后不再改（ADR-0039）</span>
          </div>

          <ConsoleListState
            :loading="relations.loading.value"
            :forbidden="relations.forbidden.value"
            :error-message="relations.errorMessage.value"
            :request-id="relations.requestId.value"
            :is-empty="relations.isEmpty.value"
            loading-title="正在加载邀请关系"
            forbidden-title="暂无权限"
            forbidden-description="这个运营账号的令牌不能读取邀请关系。"
            empty-title="这个方向没有邀请关系"
            empty-description="换个方向看看，或换个人查。"
            @retry="relations.reload"
          >
            <div class="ph-table-wrap">
              <table class="ph-table">
                <thead>
                  <tr>
                    <th>邀请人 id</th>
                    <th>被邀请人 id</th>
                    <th>邀请码</th>
                    <th>渠道</th>
                    <th>状态</th>
                    <th>拦截判据</th>
                    <th>归因时间</th>
                    <th>结算时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in relations.items.value" :key="row.id">
                    <td class="ph-table__num">{{ row.inviter_user_id }}</td>
                    <td class="ph-table__num">{{ row.invitee_user_id }}</td>
                    <td class="ph-table__num">{{ row.invite_code }}</td>
                    <td>{{ channelLabel(row.channel) }}</td>
                    <td>
                      <span class="ph-tag" :class="relationStatusTone(row.status)">{{ relationStatusLabel(row.status) }}</span>
                    </td>
                    <!-- 被拦下的关系状态是「无效」且不计数、不发奖，判据（SELF_INVITE / SAME_DEVICE …）留在这里可查 -->
                    <td>{{ row.reject_reason ?? "—" }}</td>
                    <td class="ph-table__num">{{ formatDateTime(row.attributed_at) }}</td>
                    <td class="ph-table__num">{{ row.settled_at ? formatDateTime(row.settled_at) : "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="ph-pager">
              <span>共 {{ relations.total.value }} 条</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="relations.page.value <= 1 || relations.loading.value"
                @click="relations.prevPage"
              >
                上一页
              </button>
              <span>第 {{ relations.page.value }} 页</span>
              <button
                type="button"
                class="ph-button ph-button--secondary"
                :disabled="!relations.hasMore.value || relations.loading.value"
                @click="relations.nextPage"
              >
                下一页
              </button>
            </div>
          </ConsoleListState>
        </div>
      </template>
    </ConsoleGate>
  </section>
</template>

<style scoped>
.ph-users__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-users__filter .ph-select {
  width: auto;
  min-width: 130px;
}

.ph-users__id {
  width: 140px;
}

.ph-users__alert,
.ph-users__gap {
  margin-bottom: var(--ph-space-4);
}

.ph-users__gap {
  max-width: 880px;
}

.ph-users__sub {
  margin: var(--ph-space-5) 0 var(--ph-space-2);
  font-size: 14px;
  font-weight: 600;
}
</style>
