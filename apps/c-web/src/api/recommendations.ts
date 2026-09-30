/**
 * F011「AI 帮我找服务」的接口调用（contract/app.yaml 的 `/service-recommendations`）。
 * 类型全部来自契约生成物（`AppSchemas`），不手写。
 *
 * <p>与 `providers.ts` / `catalog.ts` 同一手法各立一份：调用点只在 `apps/c-web`。
 *
 * <p>**需要登录**（与其它 AI 功能一致）：所以这条不挂在 `cApp` 那种「契约搬运工」上，
 * 也不进免登录的只读浏览组。它是 POST，但**不是写操作**——不落 AI 留痕、不占 AI 免费额度
 * （那是「咨询」的账，见后端的 `ServiceRecommendationService`）。
 */
import { http, type AppSchemas } from "@pet-health/shared";

export type ServiceRecommendationRequest = AppSchemas["ServiceRecommendationRequest"];
export type ServiceRecommendationView = AppSchemas["ServiceRecommendationView"];
export type ServiceRecommendationMatch = AppSchemas["ServiceRecommendationMatch"];
export type ServiceRecommendationItem = AppSchemas["ServiceRecommendationItem"];

const BASE = "/api/v1/app";

export const recommendations = {
  /**
   * 描述症状 → 推荐项目。
   *
   * 三种「没有推荐」的成因在契约里是不同的字段，界面上要说不同的话：
   * `matched` 为空 + `degraded=false` = 没认出症状（换个说法试试）；
   * `degraded=true` = AI 侧这次没读出来（稍后再试）；`recommendations` 为空 = 认出了症状但
   * 运营还没给它配推荐项目（可以直接浏览目录）。**不要把它们合并成一句「暂无推荐」。**
   */
  recommend(text: string, signal?: AbortSignal): Promise<ServiceRecommendationView> {
    return http.post<ServiceRecommendationView>(`${BASE}/service-recommendations`, { text }, { signal });
  },
};
