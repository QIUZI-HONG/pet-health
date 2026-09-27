package com.pethealth.common.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 敏感字段的加解密与等值查找（ADR-0013）。
 *
 * <p>两件事分开做：
 *
 * <ul>
 *   <li>{@link #encrypt}/{@link #decrypt} 用 AES-256-GCM，每条记录随机 IV。密文不可比较、不可排序，
 *       但自带完整性校验——被篡改会解密失败而不是解出垃圾。
 *   <li>{@link #lookupHash} 用 HMAC-SHA256 产出确定值，供「按手机号查用户」与唯一索引使用。
 * </ul>
 *
 * <p>密文格式：{@code v1:<base64(iv[12] || ciphertext || tag[16])>}。版本前缀是为轮换留的口子——
 * 换密钥时不必回头改老数据。
 */
@Component
public class FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec encKey;
    private final SecretKeySpec previousEncKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(FieldCryptoProperties properties) {
        this.encKey = aesKey(properties.encKey(), "app.crypto.enc-key");
        this.previousEncKey = properties.encKeyPrevious() == null
                ? null
                : aesKey(properties.encKeyPrevious(), "app.crypto.enc-key-previous");
        this.hmacKey = hmacKey(properties.hmacKey());
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密内容不能为 null");
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    /**
     * 解密。当前密钥解不开时按轮换流程尝试上一把密钥；都不行则抛异常——
     * 这种情况说明密钥配置错了或密文被改过，**绝不能悄悄返回原文或空值**。
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("密文格式不合法，缺少 v1: 前缀");
        }
        byte[] payload = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        if (payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("密文长度不合法");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[payload.length - IV_LENGTH];
        System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
        System.arraycopy(payload, IV_LENGTH, ciphertext, 0, ciphertext.length);

        if (previousEncKey != null && !canDecrypt(ciphertext, iv, encKey)) {
            return doDecrypt(ciphertext, iv, previousEncKey);
        }
        return doDecrypt(ciphertext, iv, encKey);
    }

    /** 等值查找值：同一明文恒定产出同一结果，供 WHERE 与唯一索引使用。 */
    public String lookupHash(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待摘要内容不能为 null");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("字段摘要失败", e);
        }
    }

    private boolean canDecrypt(byte[] ciphertext, byte[] iv, SecretKeySpec key) {
        try {
            doDecrypt(ciphertext, iv, key);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private String doDecrypt(byte[] ciphertext, byte[] iv, SecretKeySpec key) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("字段解密失败：密钥不匹配或密文被篡改", e);
        }
    }

    private SecretKeySpec aesKey(String base64Key, String propertyName) {
        byte[] keyBytes = decode(base64Key, propertyName);
        if (keyBytes.length != FieldCryptoProperties.REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(propertyName + " 必须是 base64 编码的 32 字节（AES-256），当前 "
                    + keyBytes.length + " 字节");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private SecretKeySpec hmacKey(String base64Key) {
        byte[] keyBytes = decode(base64Key, "app.crypto.hmac-key");
        if (keyBytes.length < FieldCryptoProperties.REQUIRED_KEY_BYTES) {
            throw new IllegalStateException("app.crypto.hmac-key 至少 32 字节，当前 " + keyBytes.length + " 字节");
        }
        return new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
    }

    private byte[] decode(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(propertyName + " 未配置：请在 server/.env 或环境变量里给出"
                    + "（base64 的 32 字节，生成方式见 server/.env.example）");
        }
        try {
            return Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " 不是合法的 base64", e);
        }
    }
}
