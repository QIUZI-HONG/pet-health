/**
 * 「一块只读数据」的加载与四态——不是列表，是**一个对象**或一组指标。
 *
 * 为什么需要它：`usePagedList`（packages/ui）管的是列表（页码、总数、翻页），而这一波的后台页面里
 * 越来越多的是**一次读一个对象**的数据块——券池总览、邀请漏斗、积分总览、考核规则、用户的权益判定、
 * 系统配置里的开关与提示词版本。它们同样要「加载中 / 无权限 / 失败（带请求 ID）/ 有内容」，
 * 但不需要页码。三处以上各写一遍这套 v-if 与 catch，最先分叉的同样是**判断顺序**：
 * 把「失败」排在「无权限」前面，40300 会显示成「加载失败 + 重试」，点几次都一样失败。
 *
 * 判据与实现与 `usePagedList` 逐条对齐（同一套 identity 错误码、同一份 `toApiFailure`、
 * 同一个并发守卫），差别只有一条：成功时把**整个返回值**放进 `data`，不留 total/has_more。
 *
 * `loaded` 单独一位，因为「还没查」与「查了但没有」在界面上是两句话：前者是「先查一个用户 id」，
 * 后者是「这个用户没有积分记录」。只看 `data === null` 分不出这两件事。
 */
import { ref, type Ref } from "vue";
import { createLatestGuard, isIdentityError, toApiFailure } from "@pet-health/shared";

export interface SectionResult<T> {
  /** 加载成功后的数据；还没加载过、或加载失败时为 null */
  data: Ref<T | null>;
  loading: Ref<boolean>;
  /** 服务端按「没权限」处理了（40100 / 40101 / 40300）——重试没有意义 */
  forbidden: Ref<boolean>;
  errorMessage: Ref<string>;
  requestId: Ref<string>;
  /** 成功加载过一次（用来把「还没查」与「查了但空」分开） */
  loaded: Ref<boolean>;
  load(loader: () => Promise<T>, failureText: string): Promise<void>;
}

export function useSection<T>(): SectionResult<T> {
  const data = ref(null) as Ref<T | null>;
  const loading = ref(false);
  const forbidden = ref(false);
  const errorMessage = ref("");
  const requestId = ref("");
  const loaded = ref(false);

  const latest = createLatestGuard();

  async function load(loader: () => Promise<T>, failureText: string): Promise<void> {
    const { token } = latest.claim();
    loading.value = true;
    forbidden.value = false;
    errorMessage.value = "";
    requestId.value = "";
    try {
      const value = await loader();
      if (!latest.isCurrent(token)) return;
      data.value = value;
      loaded.value = true;
    } catch (error) {
      if (!latest.isCurrent(token)) return;
      // 没权限不进「失败 + 重试」态：重试还是同样的拒绝（码的取值见 shared 的 IDENTITY_ERROR_CODES）
      if (isIdentityError(error)) {
        forbidden.value = true;
        data.value = null;
        return;
      }
      const failure = toApiFailure(error, failureText);
      errorMessage.value = failure.message;
      requestId.value = failure.requestId;
    } finally {
      if (latest.isCurrent(token)) {
        loading.value = false;
      }
    }
  }

  return { data, loading, forbidden, errorMessage, requestId, loaded, load };
}
