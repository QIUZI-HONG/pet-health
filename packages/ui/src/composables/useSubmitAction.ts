/**
 * 写操作的状态机：提交中 / 失败文案 + 请求 ID / 无权限 / 双击与连点节流。
 *
 * 三个约定收在一处（docs/conventions.md）：
 *   - **重复提交**：按钮点击后禁用 2 秒（不是只防双击——网络慢的时候用户会连点五次）；
 *   - **错误分类**：40300（无权限）与 40001 这类业务错误的处理不同，页面要能分别展示；
 *   - **请求 ID 要露出来**：写操作失败时用户要能把 ID 报出来（ADR-0029）。
 *
 * `run` 返回 `{ ok: true, value }` 而不是「失败时返回 null」：JSON 里的 `data` 本来就可以是 null，
 * 用 null 当哨兵会把「成功且数据为空」误判成失败。
 *
 * **为什么三端共用一份**：这段原先有三个拷贝（两个后台逐字相同，C 端少了 `forbidden`/`result`
 * 两个分支）——同一个 2 秒闸门有三个实现，改一处必漏两处。C 端不读 `forbidden` 只是「用不到」，
 * 不是「语义不同」：它自己的身份失效由请求层广播会话失效统一处理（ADR-0012）。
 * 幂等键在 shared 的 `newIdempotencyKey`，由调用点随请求带出（ADR-0028）。
 */
import { ref, type Ref } from "vue";
import { isIdentityError, toApiFailure } from "@pet-health/shared";

/** 提交后多久内不接受下一次提交（docs/conventions.md：按钮点击后禁用 2 秒）。 */
const COOLDOWN_MS = 2000;

export type SubmitOutcome<T> = { ok: true; value: T } | { ok: false };

export interface SubmitAction {
  submitting: Ref<boolean>;
  errorMessage: Ref<string>;
  requestId: Ref<string>;
  /** 服务端按「没权限」处理了（40100 / 40101 / 40300）：页面该提示身份问题而不是「重试」 */
  forbidden: Ref<boolean>;
  /** 成功后的提示文案（页面自己决定显示在哪） */
  doneMessage: Ref<string>;
  /** 上一次操作返回的数据（例如新建后的那条记录） */
  result: Ref<unknown>;
  run<T>(action: () => Promise<T>, successText?: string): Promise<SubmitOutcome<T>>;
  /** 清掉上一次的提示（打开表单或切换目标对象时调） */
  clear(): void;
}

export function useSubmitAction(failureText = "操作失败，请稍后重试"): SubmitAction {
  const submitting = ref(false);
  const errorMessage = ref("");
  const requestId = ref("");
  const forbidden = ref(false);
  const doneMessage = ref("");
  const result = ref<unknown>(null);
  let lastSubmittedAt = 0;

  function clear(): void {
    errorMessage.value = "";
    requestId.value = "";
    forbidden.value = false;
    doneMessage.value = "";
  }

  async function run<T>(action: () => Promise<T>, successText = ""): Promise<SubmitOutcome<T>> {
    // 节流放在最前面：连点时后到的几次直接返回「没执行」，页面不必怕重复写
    const now = Date.now();
    if (submitting.value || now - lastSubmittedAt < COOLDOWN_MS) {
      return { ok: false };
    }
    lastSubmittedAt = now;
    submitting.value = true;
    clear();
    try {
      const value = await action();
      result.value = value;
      doneMessage.value = successText;
      return { ok: true, value };
    } catch (error) {
      forbidden.value = isIdentityError(error);
      const failure = toApiFailure(error, failureText);
      // 契约保证 message 是给用户看的一句话（例如「价格须在¥100.00-¥200.00之间」），原样展示
      errorMessage.value = failure.message;
      requestId.value = failure.requestId;
      return { ok: false };
    } finally {
      submitting.value = false;
    }
  }

  return { submitting, errorMessage, requestId, forbidden, doneMessage, result, run, clear };
}
