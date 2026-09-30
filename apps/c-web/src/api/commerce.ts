/**
 * C 端交易与增长链路的接口调用：订单与号源（#109）、券的 C 端（#110）、权益（#112）、
 * 积分中心（#113）、邀请（#111）。路径与字段**全部来自 `contract/app.yaml` 的生成物**
 * （`AppSchemas`），一个字都不手写（AGENTS.md / ADR-0005）。
 *
 * 为什么单独一份而不是加进 `packages/shared/src/api/cApp.ts`：那份是**三个端共享的契约映射层**，
 * 本轮这条线的调用点全在 `apps/c-web`；而共享层里那一份是生成物的搬运工，不该被按页面进度反复改。
 * 鉴权、刷新、重试、信封解包仍然全部走共享的请求层（`http`）——这里只做拼路径与给类型。
 *
 * 钱的语义提醒（ADR-0036 / ADR-0038 第二节）：订单上只有 `total_amount / coupon_discount /
 * estimated_pay_amount` 三个**展示值**，`estimated_pay_amount` 是「总额 − 券面额」的预估，
 * **不是收款事实**——本项目钱在门店直接付给服务者，所以这里没有任何支付方法，也不该有。
 */
import { http, type AppSchemas, type Paged } from "@pet-health/shared";

// 领域类型：契约 schema 的名字与后端 DTO 同名（docs/conventions.md 的命名表）。
export type AppointmentSlotView = AppSchemas["AppointmentSlotView"];
export type OrderCreateRequest = AppSchemas["OrderCreateRequest"];
export type OrderCancelRequest = AppSchemas["OrderCancelRequest"];
export type OrderSummaryView = AppSchemas["OrderSummaryView"];
export type OrderView = AppSchemas["OrderView"];
export type OrderReviewRequest = AppSchemas["OrderReviewRequest"];
export type OrderReviewView = AppSchemas["OrderReviewView"];
export type OrderPhotoWallView = AppSchemas["OrderPhotoWallView"];
export type OrderPhotoSlotView = AppSchemas["OrderPhotoSlotView"];
export type CouponView = AppSchemas["CouponView"];
export type PointsCenterView = AppSchemas["PointsCenterView"];
export type PointTaskProgressView = AppSchemas["PointTaskProgressView"];
export type PointBehaviorView = AppSchemas["PointBehaviorView"];
export type PointExchangeOptionView = AppSchemas["PointExchangeOptionView"];
export type PointExchangeView = AppSchemas["PointExchangeView"];
export type PointSignInView = AppSchemas["PointSignInView"];
export type RightsEvaluationView = AppSchemas["RightsEvaluationView"];
export type RightsItemView = AppSchemas["RightsItemView"];
export type InviteCodeRequest = AppSchemas["InviteCodeRequest"];
export type InviteCodeView = AppSchemas["InviteCodeView"];
export type InviteCenterView = AppSchemas["InviteCenterView"];
export type InviteLadderProgressView = AppSchemas["InviteLadderProgressView"];
export type InviteAttributionRequest = AppSchemas["InviteAttributionRequest"];
export type InviteAttributionView = AppSchemas["InviteAttributionView"];

/** 分页结构：信封用契约的 `PageResult`，只把 `list` 的元素类型收窄（与 cApp.ts 同一手法）。 */
export type OrderPage = Paged<OrderSummaryView>;
export type CouponPage = Paged<CouponView>;
export type OrderReviewPage = Paged<OrderReviewView>;

const BASE = "/api/v1/app";

export const commerce = {
  // ---- 号源与订单（切片 #109；决策见 ADR-0038 第一节 / ADR-0048）----

  /**
   * 某服务项在某天的可预约时段（号源）。
   *
   * **约满的时段照样返回**（`full=true`）：契约要求前端把它显示成「已满」而不是让它消失——
   * 让它消失，用户会以为门店那天根本不营业（ADR-0038 第一节）。
   */
  listAppointmentSlots(
    providerId: number,
    serviceId: number,
    date: string,
    signal?: AbortSignal,
  ): Promise<AppointmentSlotView[]> {
    return http.get<AppointmentSlotView[]>(
      `${BASE}/providers/${providerId}/appointment-slots`,
      { service_id: serviceId, date },
      { signal },
    );
  },

  /** 我的订单（分页 + 状态筛选）。**核销码不在列表里**——到店凭证只出现在详情，且到预约时间才可见。 */
  listOrders(
    params: { status?: number; page?: number; pageSize?: number } = {},
    signal?: AbortSignal,
  ): Promise<OrderPage> {
    return http.get<OrderPage>(
      `${BASE}/orders`,
      { status: params.status, page: params.page, page_size: params.pageSize },
      { signal },
    );
  },

  /**
   * 下单：占用号源 + 锁定券（`coupon_id` 非空时）。
   *
   * 幂等键由调用方生成：这是一次**会占掉一个号源**的写操作，用户连点时靠它保证只成一次。
   * 三种业务拒绝都要按后端 `message` 原样展示：80001 券不可用 / 80002 券已核销 /
   * 90001 价格须在区间内（HTTP 200 的业务码，见契约）。
   */
  createOrder(body: OrderCreateRequest, idempotencyKey: string): Promise<OrderView> {
    return http.post<OrderView>(`${BASE}/orders`, body, { idempotencyKey });
  },

  /** 订单详情（含 6 位核销码与三道照片墙进度）。别人的订单一律按不存在处理（40400）。 */
  getOrder(orderId: number, signal?: AbortSignal): Promise<OrderView> {
    return http.get<OrderView>(`${BASE}/orders/${orderId}`, undefined, { signal });
  },

  /**
   * 取消：待接单**直接取消**；已预约只是**发起取消申请**（等门店同意，拒绝必填理由）；
   * 履约中 / 终态 → 40900（见 `utils/order-status.ts` 的状态到动作映射）。
   *
   * **没有退款单**：钱在门店付，取消不产生任何资金动作（ADR-0036）。
   */
  cancelOrder(orderId: number, reason: string, idempotencyKey: string): Promise<OrderView> {
    const body: OrderCancelRequest = { reason: reason.trim() || null };
    return http.post<OrderView>(`${BASE}/orders/${orderId}/cancel`, body, { idempotencyKey });
  },

  // ---- 评价晒单（第一版：评分 + 一句话）----

  /**
   * 评价一单：评分必填（1–5 星）+ 一句话可选（≤500 字）。
   *
   * 三种拒绝按后端 `message` 原样展示：40400（不是我的订单，与不存在同码）、
   * 40900（订单不是「已完成」/ 这一单已经评价过）、40001（评分越界）。
   * **一单只能评一次，且不是幂等**——第二次提交是 40900，所以界面据此把入口换成「已评价」，
   * 而不是让用户反复点一个注定失败的按钮（是否已评价看 `OrderView.review`）。
   *
   * 幂等键仍然带上（一次写操作，用户连点时是它先挡一道），但真正的兜底是服务端的唯一键。
   */
  reviewOrder(orderId: number, body: OrderReviewRequest, idempotencyKey: string): Promise<OrderReviewView> {
    return http.post<OrderReviewView>(`${BASE}/orders/${orderId}/review`, body, { idempotencyKey });
  },

  /**
   * 某门店的评价列表（分页，最近的在前）。**需要登录**——评价是「内容面」，
   * 游客只给「浏览服务」（未登录调它 → 40100）；门店详情页因此只在已登录时发这个请求。
   *
   * 为什么这条 `/providers/...` 的路径在这一份（交易线）里：**路径按调用方语义、
   * 数据按归属**——评价行归订单域，服务端也是 ph-order 在服务它（见后端
   * `OrderReviewService` 的类注释）。放在这里与后端的归属一致，
   * 而不是按路径把它拆到 `providers.ts`（那会让人以为数据在服务者域）。
   *
   * 门店的**平均分**不在这条接口里，它是门店详情的 `rating`；这里的 `total` 是**条数**，
   * 两个数一起看才是「N 分 / M 条评价」。
   */
  listProviderReviews(
    providerId: number,
    params: { page?: number; pageSize?: number } = {},
    signal?: AbortSignal,
  ): Promise<OrderReviewPage> {
    return http.get<OrderReviewPage>(
      `${BASE}/providers/${providerId}/reviews`,
      { page: params.page, page_size: params.pageSize },
      { signal },
    );
  },

  // ---- 我的券（切片 #110；决策见 ADR-0037 第三节 / ADR-0044）----

  /** 我的券（按状态 / 来源筛选，发放时间倒序）。券由平台定向发放，**不做抢券**。 */
  listCoupons(
    params: { status?: number; source?: number; page?: number; pageSize?: number } = {},
    signal?: AbortSignal,
  ): Promise<CouponPage> {
    return http.get<CouponPage>(
      `${BASE}/coupons`,
      { status: params.status, source: params.source, page: params.page, page_size: params.pageSize },
      { signal },
    );
  },

  /**
   * 锁定券（下单占用）。锁定是**占用不是消耗**。
   *
   * 界面本轮没有独立入口：契约把「谁占用、谁释放」定成**由订单持有**——单独锁一张不留订单，
   * 会留下一张没人释放的锁（契约自己标着「无主的锁怎么收场」是待澄清）。下单走
   * {@link createOrder} 的 `coupon_id`，服务端会在同一步锁一次。
   */
  lockCoupon(couponId: number, idempotencyKey: string): Promise<CouponView> {
    return http.post<CouponView>(`${BASE}/coupons/${couponId}/lock`, undefined, { idempotencyKey });
  },

  /**
   * 释放券（把锁定的券放回「待使用」）。
   *
   * 券包里给**已锁定**的券用：正常路径由「取消订单」释放，但锁若因为下单中途失败而留在那里
   * （无主锁），用户需要自己解开。券被某个未结束的订单持有时服务端给 40900——
   * 那时界面要照实说「先取消那一单」，不能自己把它解开。
   */
  releaseCoupon(couponId: number, idempotencyKey: string): Promise<CouponView> {
    return http.post<CouponView>(`${BASE}/coupons/${couponId}/release`, undefined, { idempotencyKey });
  },

  // ---- 积分中心（切片 #113；决策见 ADR-0038 第四节 / ADR-0046）----

  /** 积分中心的四块数据：账户、任务进度、行为分值、兑换档位。**积分不能提现**。 */
  getPoints(signal?: AbortSignal): Promise<PointsCenterView> {
    return http.get<PointsCenterView>(`${BASE}/points`, undefined, { signal });
  },

  /**
   * 每日签到（任务清单第一条）。**不带幂等键**：契约写明「今天已经签过」是 200 + `awarded=false`，
   * 不是错误——所以双击、重试都安全，客户端只需看 `awarded` 决定按钮文案。
   */
  signIn(): Promise<PointSignInView> {
    return http.post<PointSignInView>(`${BASE}/points/sign-in`, undefined);
  },

  /**
   * 用积分兑换券（只兑**平台补贴券**）。扣分与发券在同一个事务里，余额不足给 40900（整笔不发）。
   *
   * **幂等键必须带**（契约点名）：一次兑换没有天然的业务引用，不带键的重复提交会把分扣两次
   * （ADR-0046 第六节 / ADR-0028）。
   */
  exchangePoints(optionId: number, idempotencyKey: string): Promise<PointExchangeView> {
    return http.post<PointExchangeView>(`${BASE}/points/exchange`, { option_id: optionId }, { idempotencyKey });
  },

  // ---- 我的权益（切片 #112；决策见 ADR-0038 第三节 / ADR-0045）----

  /**
   * 我的权益：每个权益码「是否生效 + 来源 + 到期」。判定是**实时的**（服务端不缓存），
   * 界面不自己算——各模块自己拼规则会让客服与用户看到两个答案（ADR-0045）。
   */
  getRights(signal?: AbortSignal): Promise<RightsEvaluationView> {
    return http.get<RightsEvaluationView>(`${BASE}/rights`, undefined, { signal });
  },

  // ---- 邀请（切片 #111；决策见 ADR-0039 第一节 / ADR-0046）----

  /** 我的邀请码与邀请进度。进度按**有效邀请数**算（不是注册数）。 */
  getInviteCenter(signal?: AbortSignal): Promise<InviteCenterView> {
    return http.get<InviteCenterView>(`${BASE}/invites/code`, undefined, { signal });
  },

  /**
   * 生成我的邀请码。**不删旧码**——旧码已经分享出去了，删掉等于让那些链接失效。
   *
   * `channel`：1 分享链接 / 2 注册表单手工填 / 3 其他；不传按 1 处理（契约）。
   */
  createInviteCode(body: InviteCodeRequest, idempotencyKey: string): Promise<InviteCodeView> {
    return http.post<InviteCodeView>(`${BASE}/invites/code`, body, { idempotencyKey });
  },

  /**
   * 注册归因：把用户填的邀请码提交给平台（**注册成功后立即调一次**）。
   *
   * `attributed=false` **不是报错**（HTTP 200 + code 0）：注册照常成功，只是没建立邀请关系。
   * 三种被拦下的情形（自邀 / 同设备 / 同 IP 段）在 `reason` 里，但**界面不得据此暴露判据细节**
   * （ADR-0046 第三节）——见 `utils/invite.ts` 的文案映射。
   */
  attributeInvite(body: InviteAttributionRequest, idempotencyKey?: string): Promise<InviteAttributionView> {
    return http.post<InviteAttributionView>(`${BASE}/invites/attribution`, body, { idempotencyKey });
  },
};
