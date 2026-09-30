package com.pethealth.api.order;

import com.pethealth.api.privilege.CouponDtos.CouponView;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 服务者侧的订单详情，对应 contract/provider.yaml 的 {@code ProviderOrderView}。
 *
 * <p>比列表多出券实例、照片墙、取消申请与状态迁移的时间点。**核销码不在里面**：
 * 门店不需要知道码本身（知道了只会增加它被记下来复用的机会）。
 *
 * <p>金额是**到店收款的口径**：{@code estimatedPayAmount}（预估实付）就是用户到店时该收的金额，
 * 但它**不是平台的收款事实**——界面必须写明「费用在门店直接付给服务者」（ADR-0036 / ADR-0038 第二节）。
 *
 * <p>{@code reportRemark} 是报工说明（会展现在用户侧），{@code cancelRejectedReason}
 * 是门店拒绝取消的理由（拒绝必填，ADR-0038 第一节）。
 */
public record ProviderOrderView(
        Long id,
        String orderNo,
        Integer status,
        Long userId,
        String userNickname,
        String userPhone,
        Long petId,
        String petName,
        Integer petSpecies,
        Long serviceId,
        String serviceName,
        LocalDate appointmentDate,
        String startTime,
        String endTime,
        String totalAmount,
        String couponDiscount,
        String estimatedPayAmount,
        CouponView coupon,
        String remark,
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
        LocalDateTime createdAt) {
}
