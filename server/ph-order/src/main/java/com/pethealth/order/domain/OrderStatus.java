package com.pethealth.order.domain;

/**
 * 订单状态机（ADR-0038 第一节）。**取值从 0 重排**——交付文档 F014 的六态里
 * {@code 待支付 / 已支付 / 已退款} 在到店付下失去语义，本项目**不定义**它们
 * （ADR-0036：平台不经手资金），所以这里没有「已支付」，也没有任何与收款有关的成员。
 *
 * <pre>
 *  0 待接单 ──接单──▶ 1 已预约 ──核销──▶ 2 履约中 ──报工──▶ 3 已完成
 *      │                  │                  └─（不可取消：只能报工或运营干预）
 *      │                  └─ 用户申请取消 ──▶ 门店同意 / 拒绝（拒绝必填理由）
 *      └─ 用户 / 门店可直接取消 ──▶ 4 已取消
 * </pre>
 *
 * <p><b>非法迁移一律 40900</b>（不是 40001）：那是状态冲突，不是参数写错。而且这条判定
 * **不靠「先查状态再判断」**——实现上是把源状态写进 {@code UPDATE ... WHERE status = ?} 的
 * 条件里，受影响行数为 0 就是冲突。先查后判在并发下会两个请求都读到「可推进」而双双通过
 * （ADR-0044 记的正是这一类）。
 *
 * <p>这个类只放**状态常量与判定**，迁移动作本身在 {@code ServiceOrderMapper} 的条件更新里：
 * 状态机不是一组 {@code if}，而是几条带条件的 SQL。把它们放一起读，才不会出现
 * 「service 以为某个迁移合法、mapper 的条件却写窄了」这种缝。
 */
public final class OrderStatus {

    /** 下单成功（同时占用号源、锁定券）。 */
    public static final int PENDING_ACCEPT = 0;
    /** 服务者接单。接单即承诺：号源被这一单占住，此后用户取消需门店同意。 */
    public static final int BOOKED = 1;
    /** 到店核销（履约确认，**与收款无关**）。 */
    public static final int IN_SERVICE = 2;
    /** 报工提交完成（三道照片墙齐了才允许）。 */
    public static final int COMPLETED = 3;
    /** 终态：用户 / 服务者 / 运营推进，见 ADR-0038 第一节。 */
    public static final int CANCELLED = 4;

    private OrderStatus() {
    }

    /** 未结束的状态（**仍占用号源与券**）。取消时释放，完成时不释放号源（ADR-0038）。 */
    public static boolean isActive(int status) {
        return status == PENDING_ACCEPT || status == BOOKED || status == IN_SERVICE;
    }

    /** 终态：已完成 / 已取消，两个都不能再推进（重复推进一律 40900）。 */
    public static boolean isTerminal(int status) {
        return status == COMPLETED || status == CANCELLED;
    }

    /**
     * 核销码对用户可见的两个状态。
     *
     * <p>契约写死：`到预约时间才可见`，且未进入「已预约 / 履约中」、已取消、已完成时为空。
     * 换言之可见性由**状态 + 时间**两个条件共同决定，这里只负责状态那半边。
     */
    public static boolean redeemCodeVisible(int status) {
        return status == BOOKED || status == IN_SERVICE;
    }

    /** 中文状态名：40900 的 message 要能直接读给用户听（「当前是『待接单』，不能核销」）。 */
    public static String labelOf(int status) {
        return switch (status) {
            case PENDING_ACCEPT -> "待接单";
            case BOOKED -> "已预约";
            case IN_SERVICE -> "履约中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            default -> "未知状态(" + status + ")";
        };
    }

    /** 取消申请的处理状态（{@code cancel_request_status}）。 */
    public static final class CancelRequest {
        /** 待门店处理——**这一列是门店要行动的入口**（服务者侧列表里它会飘红）。 */
        public static final int PENDING = 1;
        public static final int APPROVED = 2;
        public static final int REJECTED = 3;

        private CancelRequest() {
        }
    }

    /** 推进「已取消」的三方（{@code cancelled_by}）。 */
    public static final class CancelledBy {
        public static final int USER = 1;
        public static final int PROVIDER = 2;
        /** 运营干预：清退服务者时未完成订单转已取消（ADR-0036）。本切片没有运营侧入口，先留常量。 */
        public static final int ADMIN = 3;

        private CancelledBy() {
        }
    }
}
