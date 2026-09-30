package com.pethealth.api.order;

import com.pethealth.api.privilege.CouponDtos.CouponView;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单详情（C 端），对应 contract/app.yaml 的 {@code OrderView}。
 *
 * <p>比列表多出四块：**6 位核销码**、**三道照片墙的进度**、本单锁定的券、以及取消申请。
 *
 * <p>两条可见性规则由服务端算，前端不要自己判：
 *
 * <ul>
 *   <li>{@code redeemCode} **到预约时间才可见**（ADR-0038 第二节）：未到预约时间、
 *       订单还没进入「已预约 / 履约中」、或已取消 / 已完成时为空；
 *   <li>{@code photoWall.reportable} 与 {@code missingSlots} 是「三个槽位各至少一张照片」
 *       这条硬约束的**服务端结论**（ADR-0040 第四节）——前端禁用按钮不算数。
 * </ul>
 *
 * <p>{@code estimatedPayAmount} 只是展示用的预估：界面必须写明**费用在门店直接付给服务者**
 * （ADR-0036 / ADR-0038 第二节）。
 *
 * <p>{@code review} 是这一单的评价（第一版一单一评，至多一条，所以是对象不是数组），
 * 还没评价时为空。它放在订单详情里是为了让界面能如实显示「已评价」：没有它，用户刷新页面
 * 会再看到一次评价入口，点下去只会拿到 40900。
 */
public record OrderView(
        Long id,
        String orderNo,
        Integer status,
        Long providerId,
        String providerName,
        Long serviceId,
        String serviceName,
        Long petId,
        String petName,
        LocalDate appointmentDate,
        String startTime,
        String endTime,
        String totalAmount,
        String couponDiscount,
        String estimatedPayAmount,
        CouponView coupon,
        String remark,
        String redeemCode,
        LocalDateTime acceptedAt,
        LocalDateTime redeemedAt,
        LocalDateTime reportedAt,
        String reportRemark,
        OrderPhotoWallView photoWall,
        Integer cancelRequestStatus,
        LocalDateTime cancelRequestedAt,
        String cancelReason,
        String cancelRejectedReason,
        LocalDateTime cancelledAt,
        Integer cancelledBy,
        OrderReviewView review,
        LocalDateTime createdAt) {
}
