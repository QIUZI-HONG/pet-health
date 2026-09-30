/**
 * C 端标准目录浏览的接口调用：分类导航、目录项、**按项目找店**
 * （contract/app.yaml 的 `/catalog/**`）。类型全部来自契约生成物（`AppSchemas`），不手写。
 *
 * <p>与 `providers.ts` / `commerce.ts` 同一手法、同一理由各立一份：调用点都只在 `apps/c-web`，
 * 而 `packages/shared` 的 `cApp` 是三端共享的契约映射层，不该被按 C 端的页面进度反复改。
 *
 * <p><b>不需要登录</b>（ADR-0037 第一节）：三条都是只读浏览。
 */
import { http, type AppSchemas, type Paged } from "@pet-health/shared";

// 领域类型：契约 schema 的名字与后端 DTO 同名（docs/conventions.md 的命名表）。
export type CatalogCategoryView = AppSchemas["CatalogCategoryView"];
export type CatalogItemView = AppSchemas["CatalogItemView"];
export type CatalogItemProviderView = AppSchemas["CatalogItemProviderView"];

/** 分页结构：信封用契约的 `PageResult`，只把 `list` 的元素类型收窄（与 cApp.ts 同一手法）。 */
export type CatalogItemPage = Paged<CatalogItemView>;
export type CatalogItemProviderPage = Paged<CatalogItemProviderView>;

const BASE = "/api/v1/app";

export const catalog = {
  /** 分类导航：只给启用中的分类，顺序由运营维护（前端不抄常量，改名字立刻生效）。 */
  categories(signal?: AbortSignal): Promise<CatalogCategoryView[]> {
    return http.get<CatalogCategoryView[]>(`${BASE}/catalog/categories`, undefined, { signal });
  },

  /**
   * 目录项列表（项目 + **平台区间价**）。
   *
   * 区间价不是门店报价：某家店做这个项目多少钱要看 `providersOfItem` 或门店详情——
   * 界面上必须把这句话说出来，否则区间会被读成「起价」。
   */
  items(
    params?: { categoryCode?: string; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<CatalogItemPage> {
    return http.get<CatalogItemPage>(
      `${BASE}/catalog/items`,
      {
        category_code: params?.categoryCode,
        keyword: params?.keyword,
        page: params?.page,
        page_size: params?.pageSize,
      },
      { signal },
    );
  },

  /**
   * 做这个项目的门店（按项目找店）：门店字段 + **这家店的定价** + 下单要用的 `service_id`。
   *
   * 项目不存在或已停用回 **40400**；没有门店做这个项目是 200 + 空列表（两者要分开处理）。
   */
  providersOfItem(
    itemCode: string,
    params?: { page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<CatalogItemProviderPage> {
    return http.get<CatalogItemProviderPage>(
      `${BASE}/catalog/items/${encodeURIComponent(itemCode)}/providers`,
      { page: params?.page, page_size: params?.pageSize },
      { signal },
    );
  },
};
