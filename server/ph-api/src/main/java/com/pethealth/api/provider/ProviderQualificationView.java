package com.pethealth.api.provider;

import java.time.LocalDate;

/**
 * 资质材料（脱敏），对应 contract 的 {@code ProviderQualificationView}。
 *
 * <p>{@code certNo} 是脱敏值（如 {@code 9133**********1234}），**明文只在服务端内存里出现**
 * （ADR-0013）。列表接口不解密，只有服务者查看自己材料时才回显脱敏值。
 *
 * <p>{@code validUntil} 是资质到期日的支撑字段：交付文档要求「资质到期前 30 天触发续期提醒」，
 * 提醒任务在 #99 的提醒体系里落地，这里先把字段与到期状态给出来。
 *
 * <p><b>{@code fileId} 与 {@code fileUrl} 是两个不同的东西</b>（ADR-0053）：
 * {@code fileId} 是库里真正存的引用，表单「整体替换」时要原样带回来才能保住这张图；
 * {@code fileUrl} 是**每次请求当场签发的短时读地址**（ADR-0020），有有效期、不要缓存，
 * 审核员靠它打开材料看。两者都可能为 null——改动前提交的老材料就没有图。
 */
public record ProviderQualificationView(
        Long id,
        Integer type,
        String name,
        String certNo,
        Long fileId,
        String fileUrl,
        LocalDate validFrom,
        LocalDate validUntil,
        Integer status,
        String reviewRemark) {
}
