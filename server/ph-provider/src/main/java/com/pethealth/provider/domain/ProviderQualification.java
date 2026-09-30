package com.pethealth.provider.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDate;

/**
 * 服务者资质材料，表 {@code provider_qualification}。
 *
 * <p>{@code certNoEnc} / {@code certNoHash} 是证件号的密文与 HMAC 查找列（ADR-0013，与手机号同一套办法）。
 * HMAC 列的用途有两条：
 *
 * <ul>
 *   <li><b>唯一性</b>：同一营业执照 / 身份证只允许一个「有效状态」（待审核或正常）的服务者——
 *       被驳回或已清退的记录不占名额（2026-09-29 口径，否则一次误拒就再也开不了店）；
 *   <li><b>查重提示</b>：运营审核时能看到「这份执照还挂在另一个服务者名下」。
 * </ul>
 *
 * <p>{@code validUntil} 是资质到期策略的支撑字段：到期前 30 天提醒、过期后自动下架
 * 该服务者的全部服务项（但不注销服务者）。
 */
@TableName("provider_qualification")
public class ProviderQualification extends BaseEntity {

    /** 营业执照。唯一性校验认它。 */
    public static final int TYPE_BUSINESS_LICENSE = 1;
    /** 执业许可证（医院 / 诊所）。 */
    public static final int TYPE_PRACTICE_LICENSE = 2;
    /** 法人身份证。唯一性校验认它。 */
    public static final int TYPE_ID_CARD = 3;
    /** 训犬师认证。 */
    public static final int TYPE_TRAINER_CERT = 4;
    /** 健康证。 */
    public static final int TYPE_HEALTH_CERT = 5;
    /** 其它。 */
    public static final int TYPE_OTHER = 6;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    /**
     * 材料类型码 → 中文名。**这一类名字的唯一定义处**。
     *
     * <p>两处用它：C 端详情页的 {@code type_name}（contract/app.yaml），以及服务者提交材料时
     * 没填材料名时的兜底名（{@code OnboardingService}）。收在一处是因为「一处叫身份证、
     * 一处叫法人身份证」这种分叉不会有任何机制提醒你——两个名字都能读通，只有对着看才发现。
     *
     * <p>认不出的码返回 {@code null}，由调用方决定怎么兜（C 端显示空、服务者侧兜「资质材料」）：
     * 在这里偷偷给一个默认值，会让「码不认识」这件事永远没人发现。
     */
    public static String typeName(Integer type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case TYPE_BUSINESS_LICENSE -> "营业执照";
            case TYPE_PRACTICE_LICENSE -> "执业许可证";
            case TYPE_ID_CARD -> "法人身份证";
            case TYPE_TRAINER_CERT -> "训犬师认证";
            case TYPE_HEALTH_CERT -> "健康证";
            case TYPE_OTHER -> "其他";
            default -> null;
        };
    }

    private Long providerId;
    private Integer type;
    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String certNoEnc;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String certNoHash;
    /**
     * 材料图片的**文件 id**（ph-file，`biz_type=qualification`），不是 URL。
     *
     * <p>读用的签名地址由后端当场签发、**不落库**（ADR-0053）——存 URL 会存成死链，
     * 因为签名地址默认 10 分钟就过期（ADR-0020）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long fileId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate validFrom;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate validUntil;
    private Integer status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reviewRemark;

    /** 这份材料是否「不占用唯一性名额」：驳回的不占（见类注释）。 */
    public boolean blocksUniqueness() {
        return status != null && (status == STATUS_PENDING || status == STATUS_APPROVED);
    }

    /** 是否已过期：有到期日且早于给定日期。没有到期日表示长期有效。 */
    public boolean isExpiredAt(LocalDate date) {
        return validUntil != null && validUntil.isBefore(date);
    }

    /** 是否被运营驳回（驳回的材料不算资质，未过期也不顶用）。 */
    public boolean isRejected() {
        return status != null && status == STATUS_REJECTED;
    }

    /**
     * 这份材料算不算**有效资质**：未被驳回**且**未过期。
     *
     * <p>两处口径必须一致，所以判定只写在这里：上架门禁（{@code ProviderAccess.hasValidQualification}）
     * 与 C 端可见性（{@code GET /api/v1/app/providers} 只列有有效资质的店，ADR-0035 决定 6/7）。
     * 两边各写一遍的话，会出现「能上架但搜不到」或反过来的店。
     */
    public boolean countsAsValid(LocalDate today) {
        return !isRejected() && !isExpiredAt(today);
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCertNoEnc() {
        return certNoEnc;
    }

    public void setCertNoEnc(String certNoEnc) {
        this.certNoEnc = certNoEnc;
    }

    public String getCertNoHash() {
        return certNoHash;
    }

    public void setCertNoHash(String certNoHash) {
        this.certNoHash = certNoHash;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getReviewRemark() {
        return reviewRemark;
    }

    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
    }
}
