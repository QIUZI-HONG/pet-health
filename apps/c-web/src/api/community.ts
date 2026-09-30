/**
 * C 端社区的接口调用：经验卡片（同款宠友圈）与问答互助
 * （contract/app.yaml 的 `/community/**`，切片 #84 / F020；决策见 ADR-0041 第一节 / ADR-0051）。
 *
 * <p>类型全部来自契约生成物（`AppSchemas`），不手写——与 `commerce.ts` / `catalog.ts` 同一手法、
 * 同一理由各立一份：调用点都只在 `apps/c-web`，而 `packages/shared` 的 `cApp` 是三端共享的契约
 * 映射层，不该被按 C 端的页面进度反复改。
 *
 * <p><b>这一组全部要求登录</b>：`/community/**` 七条路径**没有一条**标 `security: []`
 * （app.yaml 顶部的 `security: bearerAuth` 是全局的），而未登录时后端回 **40100**。
 * 对照着看更清楚：`/providers` 与 `/catalog` 那几条只读浏览都**逐条**标了 `security: []`，
 * 后端的 `PublicReadRoute` 白名单里也只有它们——社区不在里面。所以
 * 「浏览不需要登录」这条 C 端口径**不适用于社区**，页面据此套 `SessionGate`（见 CommunityView）。
 *
 * <p><b>40300 与 40100 要分开</b>（契约在发卡片那一条里点名）：40100 是「没登录」，该引导去登录；
 * 40300 是「登录了但没有 `community.post` 这项权益」，该引导去做任务 / 邀请。
 * 两个码混用会让引导文案指向错误的方向（ADR-0051 第四节）。点赞**不过这道门**——
 * 契约原文：「点赞不需要 `community.post`：它不是发文，是互动」。
 */
import { http, type AppSchemas, type Paged } from "@pet-health/shared";

// 领域类型：契约 schema 的名字与后端 DTO 同名（docs/conventions.md 的命名表）。
export type CommunityCardView = AppSchemas["CommunityCardView"];
export type CommunityCardCreateRequest = AppSchemas["CommunityCardCreateRequest"];
export type CommunityQuestionView = AppSchemas["CommunityQuestionView"];
export type CommunityQuestionCreateRequest = AppSchemas["CommunityQuestionCreateRequest"];
export type CommunityAnswerView = AppSchemas["CommunityAnswerView"];
export type CommunityAnswerCreateRequest = AppSchemas["CommunityAnswerCreateRequest"];

/** 分页结构：信封用契约的 `PageResult`，只把 `list` 的元素类型收窄（与 cApp.ts 同一手法）。 */
export type CommunityCardPage = Paged<CommunityCardView>;
export type CommunityQuestionPage = Paged<CommunityQuestionView>;

const BASE = "/api/v1/app";

export const community = {
  // ---- 经验卡片（同款宠友圈）----

  /**
   * 卡片列表：按**卡片上的快照标签**聚合（物种 / 品种 / 慢病标签），按发布时间倒序。
   *
   * `mine=true` 时改成「我发的卡片」，此时连待审与被拒的一起给，每行带 `status` 与
   * `reject_reason`——作者要能看到自己的内容停在哪一步，否则「发布」在他眼里就是石沉大海。
   * 默认（`mine=false`）只有已发布的：**待审与被拒的内容不出现在公开列表里**。
   *
   * 响应里没有作者 id、昵称或宠物昵称（**恒匿名**，ADR-0051 第二节），界面不许自己"猜"作者。
   */
  listCards(
    params: {
      species?: number;
      breed?: string;
      diseaseTag?: string;
      mine?: boolean;
      page?: number;
      pageSize?: number;
    } = {},
    signal?: AbortSignal,
  ): Promise<CommunityCardPage> {
    return http.get<CommunityCardPage>(
      `${BASE}/community/cards`,
      {
        species: params.species,
        breed: params.breed,
        disease_tag: params.diseaseTag,
        mine: params.mine,
        page: params.page,
        page_size: params.pageSize,
      },
      { signal },
    );
  },

  /**
   * 一键生成经验卡片（从打卡 / 就医记录）。
   *
   * 本轮**界面暂无调用点**：卡片正文要由前端**从那条来源记录预填、用户改完再提交**
   * （契约：服务端不重读档案正文，社区是公开场域、档案是私域），所以这个入口属于记录侧
   * （打卡 / 就医记录页的「生成经验卡片」），不在社区列表页里凭空捏一个 `source_ref`。
   * 接口先落在这里，免得下一个切片的调用点又跑去 `cApp` 里加一份。
   *
   * 提交后一律进待审；**没有 `community.post` → 40300**（与未登录的 40100 分开）。
   */
  createCard(body: CommunityCardCreateRequest, idempotencyKey: string): Promise<CommunityCardView> {
    return http.post<CommunityCardView>(`${BASE}/community/cards`, body, { idempotencyKey });
  },

  /**
   * 点赞（**幂等**）：反复点同一张卡片返回同样的结果，不报 40900——点赞按钮重试是常态。
   *
   * 应答给的是**点赞后的那张卡片**（`liked=true` + 最新 `like_count`）：计数以服务端为准，
   * 前端不自己 +1（自己加会与别人的赞、与重复点击算出三个数）。
   *
   * 只有已发布的卡片点得到（待审 / 被拒的别人看不见 → 40400）。
   */
  likeCard(cardId: number, idempotencyKey: string): Promise<CommunityCardView> {
    return http.post<CommunityCardView>(`${BASE}/community/cards/${cardId}/likes`, undefined, {
      idempotencyKey,
    });
  },

  /**
   * 取消点赞（**幂等**，按「宠物名下的子资源」口径）：本来就没点过也返回成功，不是错误。
   *
   * 契约没给这条挂 `Idempotency-Key`（服务端自己就是幂等的），所以这里不传键。
   */
  unlikeCard(cardId: number): Promise<CommunityCardView> {
    return http.delete<CommunityCardView>(`${BASE}/community/cards/${cardId}/likes`);
  },

  // ---- 问答互助 ----

  /**
   * 提问列表（分页）。**列表不带回答正文**（`answer_count` 只数已过审的回答，
   * 被采纳的那条由 `adopted_answer_id` 指出），回答正文在详情里给：一屏十个问题带上
   * 全部回答，既没人看，也让分页失去意义（契约原文）。
   *
   * `mine=true` 时改成「我提的问题」（含待审与被拒），用来看出自己的问题卡在哪一步。
   */
  listQuestions(
    params: { diseaseTag?: string; mine?: boolean; page?: number; pageSize?: number } = {},
    signal?: AbortSignal,
  ): Promise<CommunityQuestionPage> {
    return http.get<CommunityQuestionPage>(
      `${BASE}/community/questions`,
      {
        disease_tag: params.diseaseTag,
        mine: params.mine,
        page: params.page,
        page_size: params.pageSize,
      },
      { signal },
    );
  },

  /**
   * 提问详情（含回答）。
   *
   * 回答的可见范围是「**已发布的 + 我自己写的**」：别人待审的回答对提问者也不可见，
   * 而自己写的那条自己要能看到状态（否则「我明明答了」与「详情里没有」会同时成立）。
   *
   * `mine` 是「这条问题是不是我提的」——**采纳的唯一入口据此显示**（契约原文）。
   */
  getQuestion(questionId: number, signal?: AbortSignal): Promise<CommunityQuestionView> {
    return http.get<CommunityQuestionView>(`${BASE}/community/questions/${questionId}`, undefined, { signal });
  },

  /** 提问：**需要 `community.post`**（没有 → 40300），提交后进审核。 */
  createQuestion(body: CommunityQuestionCreateRequest, idempotencyKey: string): Promise<CommunityQuestionView> {
    return http.post<CommunityQuestionView>(`${BASE}/community/questions`, body, { idempotencyKey });
  },

  /**
   * 回答：**与提问、发卡片同一道权益门**（回答也是发文，ADR-0041 第一节）。
   *
   * 给一个别人看不见的提问（待审 / 被拒 / 已删）回答一律 40400。
   */
  createAnswer(
    questionId: number,
    body: CommunityAnswerCreateRequest,
    idempotencyKey: string,
  ): Promise<CommunityAnswerView> {
    return http.post<CommunityAnswerView>(`${BASE}/community/questions/${questionId}/answers`, body, {
      idempotencyKey,
    });
  },

  /**
   * 采纳回答：**只有提问者**（别人来调是 40400，不是 40300——采纳按钮只会出现在提问者界面上，
   * 别人来调只能是拿 id 试探，见 docs/conventions.md 的越权口径）。
   *
   * 一条问题只能采纳一个：重复采纳（含改采纳另一个）→ **40900**，靠提问行上的条件更新落地。
   * `answer_id` 必须属于这条问题且已过审（没审过的回答提问者也看不见，看不见的不能被采纳）。
   * **采纳不回退**：没有「取消采纳」这个动作。
   */
  adoptAnswer(questionId: number, answerId: number, idempotencyKey: string): Promise<CommunityQuestionView> {
    return http.post<CommunityQuestionView>(
      `${BASE}/community/questions/${questionId}/answers/${answerId}/adopt`,
      undefined,
      { idempotencyKey },
    );
  },
};
