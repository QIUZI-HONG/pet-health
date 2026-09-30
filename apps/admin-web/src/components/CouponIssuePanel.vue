<script setup lang="ts">
/**
 * 平台补贴券的发放（表单 + 发放记录）。
 *
 * 这一个面板被两页共用（券池管理的「补贴券发放」分段、补贴券窗口页），因为它是**同一件事**：
 * 契约里的发放只有一个入口 `POST /api/v1/admin/coupons`，两页各写一遍表单就会各有一份校验，
 * 而「发放」这种动作上两份校验迟早会分叉（一处挡住了服务者成本券、另一处没挡）。
 *
 * 契约口径决定这里的形态：
 * - **只发平台补贴券**（`cost_bearer = 2`）：服务者成本的券走服务者自己的贡献额度，平台不能替
 *   服务者放券（那样等于平台替服务者承诺），传了是 40900——所以模板下拉里**只列**平台补贴券，
 *   不摆一个「选了才知道不行」的选项；
 * - **定向发放、不做抢券**（ADR-0037 第三节）：没有「领券」按钮，也没有人群定向，只有一个用户 id；
 * - **合同里没有批量接口**：`IssueCouponRequest` 只有单个 `user_id`。所以「批量」在这里是
 *   **逐个提交**（一个用户一次请求），不是一次调用——一次太多会把运营动作变成压测，
 *   发起前也把这句话写在表单上；
 * - **本期不限领**（ADR-0044 的待澄清）：同一用户对同一模板重复发放会真的再发一张，
 *   不做隐式去重——所以结果里逐条列出「发给了谁」，重复的发放是运营看得见的。
 */
import { computed, ref } from "vue";
import { formatAmount, formatDateTime, toApiFailure,
  couponSourceLabel as sourceLabel, couponStatusLabel } from "@pet-health/shared";
import { ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { adminApp, type CouponRow, type CouponTemplateRow } from "../api/adminApi";
import { useSection } from "../composables/useSection";

/** 模板下拉的来源：只取启用中的平台补贴券（服务者成本券不能从这里发） */
const templates = useSection<{ list: CouponTemplateRow[] }>();

const templateId = ref("");
const userIds = ref("");
const remark = ref("");
const localError = ref("");

const submit = useSubmitAction("发放失败，请稍后重试");

/** 逐条结果：批量发放是 N 次请求，必须能看出「哪几个成了、哪一个卡在哪」 */
interface IssueResult {
  userId: string;
  ok: boolean;
  message: string;
}
const results = ref<IssueResult[]>([]);

/** 一次最多发多少个：每个用户一次请求，没有上限的粘贴框会让人粘 500 行进来 */
const BATCH_LIMIT = 100;

const records = usePagedList<CouponRow>(
  ({ page, pageSize }, signal) =>
    // source=4（平台补贴）是本页面的产出路径；别的来源由各自的动作触发，不在这里
    adminApp.listCoupons({ source: 4, page, pageSize }, signal),
  { failureText: "发放记录加载失败，请稍后重试" },
);

const templateOptions = computed(() => templates.data.value?.list ?? []);

async function loadTemplates(): Promise<void> {
  await templates.load(
    () => adminApp.listCouponTemplates({ costBearer: 2, status: 1, pageSize: 50 }),
    "平台补贴券模板加载失败，请稍后重试",
  );
}

function reload(): void {
  void records.reload();
}

/** 用户 id 解析：逗号 / 空格 / 换行都算分隔符（从表格里粘出来的往往带空格） */
function parseUserIds(): string[] {
  return userIds.value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item !== "");
}

function validate(): boolean {
  if (templateId.value === "") {
    localError.value = "先选一个平台补贴券模板";
    return false;
  }
  const ids = parseUserIds();
  if (ids.length === 0) {
    localError.value = "至少填一个用户 id";
    return false;
  }
  if (!ids.every((id) => /^\d+$/.test(id) && Number(id) > 0)) {
    localError.value = "用户 id 要都是正整数（契约里的发放是定向到具体用户的）";
    return false;
  }
  if (ids.length > BATCH_LIMIT) {
    localError.value = `一次最多 ${BATCH_LIMIT} 个用户：每个用户是一次独立请求，再多请分批发。`;
    return false;
  }
  localError.value = "";
  return true;
}

async function issue(): Promise<void> {
  if (!validate()) return;
  const ids = parseUserIds();
  const template = Number(templateId.value);
  const text = remark.value.trim() || null;
  results.value = [];

  const outcome = await submit.run(async () => {
    // 逐个发：一次一个用户是对契约的忠实实现；串行而不是并发，是为了让「第几个开始失败」在
    // 上限耗尽这类错误上仍然可读（并发时哪一条失败取决于返回顺序）
    for (const id of ids) {
      try {
        await adminApp.issueCoupon({ user_id: Number(id), template_id: template, remark: text });
        results.value.push({ userId: id, ok: true, message: "已发放" });
      } catch (error) {
        const failure = toApiFailure(error, "发放失败");
        results.value.push({
          userId: id,
          ok: false,
          message: failure.requestId ? `${failure.message}（请求 ID：${failure.requestId}）` : failure.message,
        });
      }
    }
    return results.value;
  });

  if (!outcome.ok) return;
  const okCount = results.value.filter((item) => item.ok).length;
  const failCount = results.value.length - okCount;
  // 成功文案按结果现算：全成功与被拒掉一半，是两件不同的事，不能共用一句「已发放」
  submit.doneMessage.value =
    failCount === 0 ? `已发放 ${okCount} 张` : `已发放 ${okCount} 张，${failCount} 个未发出（见下表）`;
  reload();
}

void loadTemplates();
// 发放记录也先拉一次：默认视角就是「最近发出去的补贴券」
void records.load();
</script>

<template>
  <div>
    <p v-if="templates.loading.value" class="ph-alert ph-alert--info ph-issue__alert">正在加载平台补贴券模板…</p>
    <p v-else-if="templates.forbidden.value" class="ph-alert ph-alert--error ph-issue__alert">
      这个运营账号的令牌不能读取券模板，发放用不了。
    </p>
    <p v-else-if="templates.errorMessage.value" class="ph-alert ph-alert--error ph-issue__alert">
      {{ templates.errorMessage.value }}
      <span v-if="templates.requestId.value" class="ph-text-weak">（请求 ID：{{ templates.requestId.value }}）</span>
      <button type="button" class="ph-table__action ph-issue__retry" @click="loadTemplates">重新加载模板</button>
    </p>

    <form class="ph-card ph-issue__form" @submit.prevent="issue">
      <h4 class="ph-card__title">发放平台补贴券</h4>
      <div class="ph-form-grid">
        <label class="ph-field">
          <span class="ph-field__label">券模板（只列启用中的平台补贴券）</span>
          <select v-model="templateId" class="ph-select">
            <option value="">请选择</option>
            <option v-for="item in templateOptions" :key="item.id" :value="String(item.id)">
              {{ item.code }} · {{ item.name }}（面额 {{ formatAmount(item.face_value) }}）
            </option>
          </select>
          <span class="ph-field__hint">
            服务者成本的券不在这个下拉里：它走服务者自己的贡献额度，平台不能替服务者放券（传了是 40900）。
          </span>
        </label>
        <label class="ph-field">
          <span class="ph-field__label">发放说明（可选）</span>
          <input v-model="remark" class="ph-input" maxlength="255" placeholder="如：客诉补偿" />
        </label>
        <label class="ph-field ph-form-grid--full">
          <span class="ph-field__label">用户 id（多个用逗号分隔）</span>
          <input v-model="userIds" class="ph-input" placeholder="如：1001, 1002" />
          <span class="ph-field__hint">
            发放全部定向（ADR-0037），没有批量接口：**每个用户一次请求**，逐个提交、逐个回报结果。
            同一用户重复发放会真的再发一张——本期不限领，不做隐式去重。
          </span>
        </label>
      </div>

      <p v-if="localError" class="ph-alert ph-alert--error ph-issue__alert">{{ localError }}</p>
      <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-issue__alert">
        {{ submit.errorMessage.value }}
        <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
      </p>
      <p v-if="submit.forbidden.value" class="ph-alert ph-alert--error ph-issue__alert">
        服务端按「没权限」拒绝了这次发放（40300）：当前身份不能发券。
      </p>

      <div class="ph-issue__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "发放中…" : "发放" }}
        </button>
        <span v-if="templateOptions.length === 0" class="ph-field__hint">
          没有可用的平台补贴券模板：先到「券池管理」建一个成本归属为「平台补贴」的模板并启用。
        </span>
      </div>
    </form>

    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-issue__alert">{{ submit.doneMessage.value }}</p>

    <div v-if="results.length > 0" class="ph-table-wrap ph-issue__results">
      <table class="ph-table">
        <thead>
          <tr>
            <th>用户 id</th>
            <th>结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in results" :key="row.userId">
            <td class="ph-table__num">{{ row.userId }}</td>
            <td :class="row.ok ? '' : 'ph-issue__failed'">{{ row.message }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <h4 class="ph-card__title ph-issue__sub">发放记录（来源 = 平台补贴）</h4>
    <ConsoleListState
      :loading="records.loading.value"
      :forbidden="records.forbidden.value"
      :error-message="records.errorMessage.value"
      :request-id="records.requestId.value"
      :is-empty="records.isEmpty.value"
      loading-title="正在加载发放记录"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取券实例。"
      empty-title="还没有平台补贴券发出过"
      empty-description="上面发放之后，这里会按发放时间倒序列出每一张。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>券码</th>
              <th>模板</th>
              <th>领券用户</th>
              <th>面额 / 门槛</th>
              <th>来源</th>
              <th>状态</th>
              <th>有效期至</th>
              <th>发放时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in records.items.value" :key="row.id">
              <td class="ph-table__num">{{ row.code }}</td>
              <td>{{ row.template_code }} · {{ row.template_name }}</td>
              <td class="ph-table__num">{{ row.user_id }}</td>
              <td class="ph-table__num">{{ formatAmount(row.face_value) }} / 满 {{ formatAmount(row.min_amount) }}</td>
              <td>{{ sourceLabel(row.source) }}</td>
              <td>
                <span class="ph-tag" :class="row.status === 3 ? 'ph-tag--success' : row.status === 4 ? 'ph-tag--warning' : ''">
                  {{ couponStatusLabel(row.status) }}
                </span>
              </td>
              <td class="ph-table__num">{{ formatDateTime(row.valid_until) }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.issued_at) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ records.total.value }} 张</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="records.page.value <= 1 || records.loading.value"
          @click="records.prevPage"
        >
          上一页
        </button>
        <span>第 {{ records.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!records.hasMore.value || records.loading.value"
          @click="records.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>
  </div>
</template>

<style scoped>
.ph-issue__alert {
  margin: var(--ph-space-3) 0;
}

.ph-issue__retry {
  margin-left: var(--ph-space-3);
}

.ph-issue__actions {
  display: flex;
  align-items: center;
  gap: var(--ph-space-4);
  margin-top: var(--ph-space-4);
}

.ph-issue__results {
  margin-top: var(--ph-space-4);
}

.ph-issue__failed {
  color: var(--ph-color-danger);
}

.ph-issue__sub {
  margin: var(--ph-space-6) 0 var(--ph-space-3);
  font-size: 14px;
  font-weight: 600;
}
</style>
