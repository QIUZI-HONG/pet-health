/**
 * C 端「找店 / 看店」的接口调用：服务者列表与详情（contract/app.yaml 的 `/providers`）。
 * 路径与字段**全部来自契约生成物**（`AppSchemas`），一个字都不手写（AGENTS.md / ADR-0005）。
 *
 * <p>与 `commerce.ts` 同一手法、同一理由分开放：那份是交易与增长线，这份是服务供给的浏览线，
 * 各自的调用点都只在 `apps/c-web`。也**不放 `packages/shared` 的 `cApp`**——那是三个端共享的
 * 契约映射层，不该被按 C 端的页面进度反复改。鉴权、刷新、重试与信封解包仍全部走共享请求层。
 *
 * <p><b>这两条接口不需要登录</b>（ADR-0037 第一节：游客不是角色，只读接口不需要身份）：
 * 未登录能浏览，带令牌结果一样。
 *
 * <p>价格在这两条接口里是**展示值**：钱在门店直接付给服务者，平台不经手资金（ADR-0036），
 * 所以这里没有任何与支付有关的方法，也不该有。
 */
import { http, type AppSchemas, type Paged } from "@pet-health/shared";

// 领域类型：契约 schema 的名字与后端 DTO 同名（docs/conventions.md 的命名表）。
export type ProviderSummaryView = AppSchemas["ProviderSummaryView"];
export type ProviderDetailView = AppSchemas["ProviderDetailView"];
export type ProviderQualificationSummaryView = AppSchemas["ProviderQualificationSummaryView"];
export type ProviderServiceOfferView = AppSchemas["ProviderServiceOfferView"];

/** 分页结构：信封用契约的 `PageResult`，只把 `list` 的元素类型收窄（与 cApp.ts 同一手法）。 */
export type ProviderPage = Paged<ProviderSummaryView>;

const BASE = "/api/v1/app";

export const providers = {
  /**
   * 找店：分类 / 关键词 / 分页。
   *
   * <p>返回的**只有可下单的店**（状态正常且至少有一份未过期资质）——服务端已经按这条口径
   * 过滤过，所以前端不需要再判一次「这家能不能约」。关键词只匹配门店名称（不匹配服务项名）。
   */
  list(
    params?: { type?: number; keyword?: string; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ): Promise<ProviderPage> {
    return http.get<ProviderPage>(
      `${BASE}/providers`,
      {
        type: params?.type,
        keyword: params?.keyword,
        page: params?.page,
        page_size: params?.pageSize,
      },
      { signal },
    );
  },

  /**
   * 看店：资质摘要 + 营业时间 + **在架服务项与价格**。
   *
   * <p>不可浏览的店（未过审 / 已驳回 / 已冻结 / 资质全部过期）回 **40400**，
   * 与「这个 id 不存在」同一个码——页面按「这家店看不了」处理，不要提示「重试」。
   */
  detail(providerId: number, signal?: AbortSignal): Promise<ProviderDetailView> {
    return http.get<ProviderDetailView>(`${BASE}/providers/${providerId}`, undefined, { signal });
  },
};
