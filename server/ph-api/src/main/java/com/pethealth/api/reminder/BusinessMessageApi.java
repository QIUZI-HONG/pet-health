package com.pethealth.api.reminder;

/**
 * 消息中心对外暴露的**写侧**接口：别的模块发一条**业务通知**（ADR-0006：不碰 {@code message} 表）。
 *
 * <p>CONTEXT.md 把消息分两类：**健康提醒**（系统主动生成，ph-reminder 自己算）与
 * **业务通知**（订单、券、邀请等事件驱动）。健康提醒的生成逻辑在 ph-reminder 内部；
 * 业务通知由**事件发生方**调用——订单取消、券到期、邀请达标、预约临近都发生在别的模块，
 * 那些模块不该为了发一条站内消息去写 {@code message} 表。
 *
 * <p><b>为什么它在 ph-api 而不是 ph-reminder 的 api 包</b>：这是 2026-09-30 接线时挪过来的，
 * 判据与 {@link com.pethealth.api.provider.ProviderServiceQueryApi} 那批端口**完全一样**
 * ——「只有一个模块用」的接口可以留在拥有方自己的 api 包里（如 {@code MessageQueryApi}，
 * 目前只有 ph-account 用），而**两个以上模块要用**的必须放 ph-api：否则每个消费方都要依赖
 * ph-reminder，而 Maven 的模块图**不允许成环**——`ph-reminder → ph-record → ph-privilege`
 * 已经是一条链，于是「邀请生效给邀请人发消息」这一件事会把 ph-privilege → ph-reminder
 * 变成一个环（实测：构建直接报 {@code ProjectCycleException}）。
 * 接口放共享模块、实现仍由拥有者写（{@code MessageService}），是这条约束下唯一不绕路的解法。
 */
public interface BusinessMessageApi {

    /** 业务通知的类型码（与 {@code message.type} 的取值一致：7 订单 / 8 券 / 9 邀请）。 */
    int TYPE_ORDER = 7;
    int TYPE_COUPON = 8;
    int TYPE_INVITE = 9;

    /**
     * 发一条业务通知。**幂等**：同一个 {@code dedupKey} 只会有一条消息，
     * 重复调用是 no-op（不报错、也不把消息中心刷成重复项）。
     *
     * <p>与健康提醒共用同一套去重键（`message.uk_dedup`）：订单取消的通知用
     * `order-cancelled-{orderId}`，所以「取消 → 重试取消」不会给用户发两遍。
     *
     * @param notification 收件人、类型、去重键、标题与正文
     */
    void notify(BusinessNotification notification);

    /**
     * 一条业务通知的内容（跨模块用，不是契约 DTO）。
     *
     * @param type         类型码，用本接口的 {@code TYPE_*} 常量
     * @param dedupKey     幂等键，**必须全局唯一地表达「这是哪一件事」**（如 {@code order-cancelled-42}）
     * @param title        短标题（列表直接展示）
     * @param content      正文（详情文案）
     * @param actionHint   操作按钮文案；不需要跳转时传 null
     * @param actionTarget 操作跳转的相对地址；不需要跳转时传 null
     */
    record BusinessNotification(
            long userId,
            int type,
            String dedupKey,
            String title,
            String content,
            String actionHint,
            String actionTarget) {
    }
}
