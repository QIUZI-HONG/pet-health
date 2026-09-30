package com.pethealth.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单，表 {@code order}（迁移 V29）。
 *
 * <p>五条读代码前要先知道的口径：
 *
 * <ol>
 *   <li><b>四态不含支付</b>（ADR-0038 第一节 / ADR-0036）：没有 {@code pay_amount}、
 *       没有收款状态、没有退款单。{@code estimatedPayAmount} 是「总额 − 券面额」的
 *       **展示用计算值**——钱在门店直接付给服务者，平台不经手资金；
 *   <li><b>快照与现取的分界</b>：{@code serviceCode / serviceName / totalAmount /
 *       petName / petSpecies} 是**下单时的快照**（订单是历史凭证，目录改名不该改写已发生的交易）；
 *       门店名称与预约人昵称 / 脱敏手机号**现取**（订单不该留一个会过期的联系人快照，见
 *       {@code OrderViews}）；
 *   <li><b>核销码有两个列</b>：{@code redeemCode} 永久留着可查可追；{@code redeemCodeActive}
 *       只在未结束时等于它，唯一键加在这一列上——6 位数字只有 100 万种，历史码一直占位
 *       迟早会把码位吃光（详见迁移 V29 的注释）；
 *   <li><b>两处可取消的入口共用一套取消申请字段</b>：{@code cancelRequestStatus} 只在
 *       「已预约 + 用户申请过」时有值，{@code 1} 是**门店要行动**的那一档；
 *   <li><b>状态迁移只经由 {@code ServiceOrderMapper} 的条件更新</b>——不要在 service 里
 *       {@code setStatus} 后 {@code updateById}，那会把「原子条件更新」退化成
 *       「先查后写」，并发下两个请求都会以为自己赢了（ADR-0044 的教训）。
 * </ol>
 */
@TableName(value = "`order`")
public class ServiceOrder extends BaseEntity {

    /** 订单号里的前缀：`PH` + 时间戳 + 4 位随机（格式属待澄清，见 ADR-0048）。 */
    public static final String ORDER_NO_PREFIX = "PH";

    private String orderNo;
    private Long userId;
    private Long petId;
    private String petName;
    private Integer petSpecies;
    private Long providerId;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private LocalDate appointmentDate;
    private String startTime;
    private String endTime;
    private BigDecimal totalAmount;
    private Long couponId;
    private BigDecimal couponDiscount;
    private BigDecimal estimatedPayAmount;
    private Integer status;
    private String redeemCode;
    private String redeemCodeActive;
    private String remark;
    private LocalDateTime acceptedAt;
    private LocalDateTime redeemedAt;
    private LocalDateTime reportedAt;
    private String reportRemark;
    private Integer cancelRequestStatus;
    private LocalDateTime cancelRequestedAt;
    private String cancelReason;
    private String cancelRejectedReason;
    private LocalDateTime cancelledAt;
    private Integer cancelledBy;

    // ---------------------------------------------------------------- 判定

    /** 这一单还占着号源与券吗（未结束）；取消才释放，完成不释放号源（ADR-0038）。 */
    public boolean isActive() {
        return status != null && OrderStatus.isActive(status);
    }

    /** 是不是终态（已完成 / 已取消）：终态不能再推进，重复推进一律 40900。 */
    public boolean isTerminal() {
        return status != null && OrderStatus.isTerminal(status);
    }

    /** 有**待门店处理**的取消申请：门店的同意 / 拒绝只在这一档上有效。 */
    public boolean hasPendingCancelRequest() {
        return cancelRequestStatus != null && cancelRequestStatus == OrderStatus.CancelRequest.PENDING;
    }

    /** 本单用了券吗（用了才在取消 / 核销时通知券模块）。 */
    public boolean hasCoupon() {
        return couponId != null;
    }

    /** 中文状态名，给 40900 的 message 用。 */
    public String statusLabel() {
        return OrderStatus.labelOf(status == null ? -1 : status);
    }

    // ---------------------------------------------------------------- getter / setter

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getPetName() {
        return petName;
    }

    public void setPetName(String petName) {
        this.petName = petName;
    }

    public Integer getPetSpecies() {
        return petSpecies;
    }

    public void setPetSpecies(Integer petSpecies) {
        this.petSpecies = petSpecies;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public LocalDate getAppointmentDate() {
        return appointmentDate;
    }

    public void setAppointmentDate(LocalDate appointmentDate) {
        this.appointmentDate = appointmentDate;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public BigDecimal getCouponDiscount() {
        return couponDiscount;
    }

    public void setCouponDiscount(BigDecimal couponDiscount) {
        this.couponDiscount = couponDiscount;
    }

    public BigDecimal getEstimatedPayAmount() {
        return estimatedPayAmount;
    }

    public void setEstimatedPayAmount(BigDecimal estimatedPayAmount) {
        this.estimatedPayAmount = estimatedPayAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRedeemCode() {
        return redeemCode;
    }

    public void setRedeemCode(String redeemCode) {
        this.redeemCode = redeemCode;
    }

    public String getRedeemCodeActive() {
        return redeemCodeActive;
    }

    public void setRedeemCodeActive(String redeemCodeActive) {
        this.redeemCodeActive = redeemCodeActive;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public LocalDateTime getRedeemedAt() {
        return redeemedAt;
    }

    public void setRedeemedAt(LocalDateTime redeemedAt) {
        this.redeemedAt = redeemedAt;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }

    public String getReportRemark() {
        return reportRemark;
    }

    public void setReportRemark(String reportRemark) {
        this.reportRemark = reportRemark;
    }

    public Integer getCancelRequestStatus() {
        return cancelRequestStatus;
    }

    public void setCancelRequestStatus(Integer cancelRequestStatus) {
        this.cancelRequestStatus = cancelRequestStatus;
    }

    public LocalDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(LocalDateTime cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getCancelRejectedReason() {
        return cancelRejectedReason;
    }

    public void setCancelRejectedReason(String cancelRejectedReason) {
        this.cancelRejectedReason = cancelRejectedReason;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Integer getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Integer cancelledBy) {
        this.cancelledBy = cancelledBy;
    }
}
