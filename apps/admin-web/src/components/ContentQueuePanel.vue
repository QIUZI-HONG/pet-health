<script setup lang="ts">
/**
 * 内容审核队列（`GET /api/v1/admin/community/contents` + approve / reject）。三种内容共用这一个面板，
 * 页面按类型挂四次——因为契约要求**按类型分别查**（`content_type` 必填，三种内容各住一张表）。
 *
 * 三条口径决定了这里的形态：
 * - **正文给全文**（不是摘要）：截断的审核等于没审，所以这一列不裁字；
 * - **通过没有请求体**：通过时没有要对作者说的话（内容只是出现了），所以通过按钮旁不摆备注框
 *   ——摆了也没有地方存，只会让运营以为它被记录；
 * - **驳回理由必填**且会**原样出现在作者自己的那份内容里**（C 端 `mine=true` 的 `reject_reason`）：
 *   只说「违规」而不说违什么，作者只会换个说法再发一遍。所以驳回是一个带输入框的展开区，
 *   不是一个二次确认弹窗。
 *
 * 机审命中的内容会**直接落在已驳回**（`machine_hits` 记着命中了哪些词），运营可以改判为通过：
 * 词表误伤是常态，而「误伤了一个真实用户的内容且没人能改」比漏放一条更伤。改判不清空
 * `machine_hits`——它是这次改判的依据。
 *
 * 队列带 `author_id`：审核是**运营**的动作，要能追溯到人（ADR-0037 第一节）。这一条只在这一端成立：
 * C 端的卡片视图恒匿名，审核后台不是「匿名」的例外，而是另一个域（运营有处置权，也就必须看得见对象）。
 *
 * **没有「编辑内容」的入口**：运营的处置是放行 / 拦住 / 下架，不是替用户改内容（那等于平台替用户表态）。
 */
import { ref } from "vue";
import { formatDateTime } from "@pet-health/shared";
import { ConsoleListState, usePagedList, useSubmitAction } from "@pet-health/ui";
import { adminApp, type ContentRow } from "../api/adminApi";

const props = defineProps<{ contentType: number }>();

const statusFilter = ref("0");
const authorId = ref("");

const contents = usePagedList<ContentRow>(
  ({ page, pageSize }, signal) =>
    adminApp.listContents(
      {
        contentType: props.contentType,
        status: statusFilter.value === "" ? undefined : Number(statusFilter.value),
        authorId: authorId.value.trim() === "" ? undefined : Number(authorId.value.trim()),
        page,
        pageSize,
      },
      signal,
    ),
  { failureText: "内容审核队列加载失败，请稍后重试" },
);

const submit = useSubmitAction("审核失败，请稍后重试");
/** 正在填写驳回理由的那一条（内容 id 在类型内唯一，所以 key 用 content_id） */
const rejecting = ref<ContentRow | null>(null);
const rejectReason = ref("");

function reload(): void {
  void contents.reload();
}

async function approve(row: ContentRow): Promise<void> {
  const outcome = await submit.run(
    () => adminApp.approveContent(row.content_type, row.content_id),
    "已通过：内容立刻对其他用户可见",
  );
  if (outcome.ok) reload();
}

function openReject(row: ContentRow): void {
  submit.clear();
  rejecting.value = row;
  rejectReason.value = "";
}

async function confirmReject(): Promise<void> {
  const row = rejecting.value;
  if (!row) return;
  if (rejectReason.value.trim() === "") {
    // 本地先说一遍：理由必填的理由不是「校验」，是它会原样给作者看（ADR-0037 第一节）
    submit.errorMessage.value = "驳回理由必填：它会原样出现在作者自己的那份内容里";
    return;
  }
  const outcome = await submit.run(
    () => adminApp.rejectContent(row.content_type, row.content_id, rejectReason.value.trim()),
    "已驳回（已发布的内容则是下架）：作者能看到这条理由",
  );
  if (!outcome.ok) return;
  rejecting.value = null;
  reload();
}

function statusLabel(value: number): string {
  switch (value) {
    case 0:
      return "待审";
    case 1:
      return "已发布";
    case 2:
      return "已驳回 / 已下架";
    default:
      return "—";
  }
}

function statusTone(value: number): string {
  if (value === 1) return "ph-tag--success";
  if (value === 2) return "ph-tag--danger";
  return "ph-tag--warning";
}

function typeLabel(value: number): string {
  switch (value) {
    case 1:
      return "经验卡片";
    case 2:
      return "提问";
    case 3:
      return "回答";
    default:
      return "—";
  }
}

void contents.load();
</script>

<template>
  <div>
    <div class="ph-toolbar">
      <label class="ph-field ph-cq__filter">
        <span class="ph-field__label">状态</span>
        <select v-model="statusFilter" class="ph-select" @change="reload">
          <option value="0">待审</option>
          <option value="1">已发布</option>
          <option value="2">已驳回 / 已下架</option>
          <option value="">全部</option>
        </select>
      </label>
      <label class="ph-field ph-cq__filter">
        <span class="ph-field__label">作者 id</span>
        <input v-model="authorId" class="ph-input ph-cq__id" inputmode="numeric" placeholder="可选：处置刷屏账号时用" @keyup.enter="reload" />
      </label>
      <button type="button" class="ph-button ph-button--secondary" :disabled="contents.loading.value" @click="reload">刷新</button>
    </div>

    <p class="ph-field__hint ph-cq__hint">
      待审的可以「通过」或「驳回」；已发布的改判为「下架」（同一个状态迁移，理由同样必填）；
      已经是驳回态的不再重复驳回——后端是 40900（非法迁移不是静默成功），所以按钮直接禁掉，
      省一次没有意义的往返。「通过」没有备注框：通过时没有要对作者说的话。
    </p>

    <p v-if="submit.errorMessage.value" class="ph-alert ph-alert--error ph-cq__alert">
      {{ submit.errorMessage.value }}
      <span v-if="submit.requestId.value" class="ph-text-weak">（请求 ID：{{ submit.requestId.value }}）</span>
    </p>
    <p v-if="submit.doneMessage.value" class="ph-alert ph-alert--info ph-cq__alert">{{ submit.doneMessage.value }}</p>

    <ConsoleListState
      :loading="contents.loading.value"
      :forbidden="contents.forbidden.value"
      :error-message="contents.errorMessage.value"
      :request-id="contents.requestId.value"
      :is-empty="contents.isEmpty.value"
      loading-title="正在加载内容审核队列"
      forbidden-title="暂无权限"
      forbidden-description="这个运营账号的令牌不能读取内容审核队列。"
      empty-title="这个状态下没有内容"
      empty-description="换个状态看看（被机审拦住的内容会直接落在「已驳回 / 已下架」，那里可以改判为通过）。"
      @retry="reload"
    >
      <div class="ph-table-wrap">
        <table class="ph-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>作者 id</th>
              <th>标题</th>
              <th>正文</th>
              <th>状态</th>
              <th>机审命中</th>
              <th>提交时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in contents.items.value" :key="`${row.content_type}-${row.content_id}`">
              <td>{{ row.content_type_name ?? typeLabel(row.content_type) }}</td>
              <td class="ph-table__num">{{ row.author_id ?? "—" }}</td>
              <td>{{ row.title ?? "—" }}</td>
              <!-- 全文：截断的审核等于没审 -->
              <td class="ph-cq__body">{{ row.content }}</td>
              <td>
                <span class="ph-tag" :class="statusTone(row.status)">{{ row.status_name ?? statusLabel(row.status) }}</span>
                <span v-if="row.reject_reason" class="ph-field__hint ph-cq__reason">驳回理由：{{ row.reject_reason }}</span>
              </td>
              <td>{{ row.machine_hits ?? "—" }}</td>
              <td class="ph-table__num">{{ formatDateTime(row.created_at) }}</td>
              <td>
                <div class="ph-table__actions">
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="submit.submitting.value || row.status === 1"
                    @click="approve(row)"
                  >
                    通过
                  </button>
                  <button
                    type="button"
                    class="ph-table__action"
                    :disabled="submit.submitting.value || row.status === 2"
                    @click="openReject(row)"
                  >
                    {{ row.status === 1 ? "下架" : "驳回" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ph-pager">
        <span>共 {{ contents.total.value }} 条</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="contents.page.value <= 1 || contents.loading.value"
          @click="contents.prevPage"
        >
          上一页
        </button>
        <span>第 {{ contents.page.value }} 页</span>
        <button
          type="button"
          class="ph-button ph-button--secondary"
          :disabled="!contents.hasMore.value || contents.loading.value"
          @click="contents.nextPage"
        >
          下一页
        </button>
      </div>
    </ConsoleListState>

    <form v-if="rejecting" class="ph-card ph-cq__reject" @submit.prevent="confirmReject">
      <h4 class="ph-card__title">
        驳回这{{ typeLabel(rejecting.content_type) }} #{{ rejecting.content_id }}
      </h4>
      <label class="ph-field">
        <span class="ph-field__label">驳回理由（必填，会原样展示给作者）</span>
        <textarea
          v-model="rejectReason"
          class="ph-textarea"
          maxlength="255"
          placeholder="如：正文含联系方式，社区不允许在内容里引流"
        />
      </label>
      <div class="ph-cq__actions">
        <button type="submit" class="ph-button ph-button--primary" :disabled="submit.submitting.value">
          {{ submit.submitting.value ? "提交中…" : "确认驳回" }}
        </button>
        <button type="button" class="ph-button ph-button--secondary" @click="rejecting = null">取消</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.ph-cq__filter {
  flex-direction: row;
  align-items: center;
  gap: var(--ph-space-2);
}

.ph-cq__filter .ph-select {
  width: auto;
  min-width: 150px;
}

.ph-cq__id {
  width: 200px;
}

.ph-cq__alert {
  margin-bottom: var(--ph-space-4);
}

.ph-cq__hint {
  margin: 0 0 var(--ph-space-4);
  max-width: 880px;
}

.ph-cq__body {
  min-width: 320px;
  max-width: 520px;
  white-space: normal;
  word-break: break-word;
}

.ph-cq__reason {
  display: block;
  margin-top: var(--ph-space-1);
  max-width: 220px;
  white-space: normal;
}

.ph-cq__reject {
  margin-top: var(--ph-space-5);
  max-width: 720px;
}

.ph-cq__actions {
  display: flex;
  gap: var(--ph-space-3);
  margin-top: var(--ph-space-4);
}
</style>
