/**
 * 分页列表的加载与四态。
 *
 * 每个接列表的页面都要这几件事：发请求、记住页码、区分「加载中 / 无权限 / 失败（带请求 ID）/
 * 空 / 有内容」、以及**并发守卫**（切筛选或翻页时先发的请求可能后回来，把新数据盖成旧数据——
 * 统一实现见 shared 的 `createLatestGuard`）。
 *
 * 之所以收成一个 composable：这套 v-if 链与守卫漏一处，表现是「偶发显示错数据」，
 * 比直接报错难查得多。
 *
 * **为什么在 packages/ui**：服务者后台与运营后台的列表页是同一套交互（筛选 + 分页器 + 四态），
 * 原先两个应用各有一份 116 行逐字相同的实现。放这里不影响 ADR-0015 的分家：
 * 共享的是交互状态机，不是业务组件（C 端的列表不长这样，也没有搬进来）。
 */
import { computed, ref, type Ref } from "vue";
import { ApiError, createLatestGuard, isIdentityError, toApiFailure } from "@pet-health/shared";

export interface PagedListResult<T> {
  items: Ref<T[]>;
  page: Ref<number>;
  total: Ref<number>;
  hasMore: Ref<boolean>;
  loading: Ref<boolean>;
  forbidden: Ref<boolean>;
  errorMessage: Ref<string>;
  requestId: Ref<string>;
  /** 加载成功但一条都没有（加载中与失败时都是 false，页面不必再判一次） */
  isEmpty: Ref<boolean>;
  /** 加载第 N 页（默认第一页） */
  load(page?: number): Promise<void>;
  /** 回到第一页重来（筛选变了、或用户点了重试） */
  reload(): Promise<void>;
  nextPage(): Promise<void>;
  prevPage(): Promise<void>;
}

export interface PagedListOptions {
  pageSize?: number;
  /** 加载失败的兜底文案（后端有 message 时用它，没有才用这句） */
  failureText?: string;
}

export function usePagedList<T>(
  loader: (params: { page: number; pageSize: number }, signal: AbortSignal) => Promise<{ list: T[]; total: number; has_more: boolean }>,
  options: PagedListOptions = {},
): PagedListResult<T> {
  const pageSize = options.pageSize ?? 20;
  const failureText = options.failureText ?? "加载失败，请稍后重试";

  const items = ref([]) as Ref<T[]>;
  const page = ref(1);
  const total = ref(0);
  const hasMore = ref(false);
  const loading = ref(false);
  const forbidden = ref(false);
  const errorMessage = ref("");
  const requestId = ref("");

  const latest = createLatestGuard();

  /** 有内容以外的四种状态互斥，页面只需读这里的标记 */
  const isEmpty = computed(
    () => !loading.value && !forbidden.value && errorMessage.value === "" && items.value.length === 0,
  );

  async function load(target = 1): Promise<void> {
    const { token, signal } = latest.claim();
    loading.value = true;
    errorMessage.value = "";
    requestId.value = "";
    forbidden.value = false;
    try {
      const result = await loader({ page: target, pageSize }, signal);
      if (!latest.isCurrent(token)) return;
      items.value = result.list;
      page.value = target;
      total.value = result.total;
      hasMore.value = result.has_more;
    } catch (error) {
      if (!latest.isCurrent(token)) return;
      // 没权限不进「失败 + 重试」态：重试还是同样的拒绝（码的取值见 shared 的 IDENTITY_ERROR_CODES）
      if (error instanceof ApiError && isIdentityError(error)) {
        forbidden.value = true;
        items.value = [];
        total.value = 0;
        hasMore.value = false;
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

  return {
    items,
    page,
    total,
    hasMore,
    loading,
    forbidden,
    errorMessage,
    requestId,
    isEmpty,
    load,
    reload: () => load(1),
    nextPage: () => (hasMore.value ? load(page.value + 1) : Promise.resolve()),
    prevPage: () => (page.value > 1 ? load(page.value - 1) : Promise.resolve()),
  };
}
