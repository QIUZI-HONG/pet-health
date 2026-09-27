package com.pethealth.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 敏感字段加密的密钥（ADR-0013）。
 *
 * <p>三把密钥都从环境变量来，只进本地 {@code .env}——不进库、不进 git、不贴进对话。
 * {@code encKeyPrevious} 只在轮换期间配置：解密先试当前密钥，失败再试它，老数据在下次写入时自然迁移。
 *
 * <p>{@code hmacKey} 参与唯一索引（手机号查重），**不能与密文密钥混用同一把**——
 * 否则将来轮换密文密钥会连带把查找列一起换掉，唯一索引就得全量重建。
 */
@ConfigurationProperties(prefix = "app.crypto")
public record FieldCryptoProperties(
        String encKey,
        String hmacKey,
        String encKeyPrevious) {

    public static final int REQUIRED_KEY_BYTES = 32;

    public FieldCryptoProperties {
        encKeyPrevious = (encKeyPrevious == null || encKeyPrevious.isBlank()) ? null : encKeyPrevious;
    }
}
