package com.pethealth.file.storage;

import com.pethealth.common.crypto.FieldCipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 直传与读图的签名凭证（ADR-0020）。
 *
 * <p>形态：{@code {purpose}.{expiresEpochSecond}.{hmac}}，HMAC 覆盖「用途 + 文件 id + 归属用户 + 过期时间」。
 *
 * <p>三点刻意选择：
 *
 * <ul>
 *   <li>**复用 {@link FieldCipher} 的 HMAC 密钥**，加 {@code upload-token:v1|} 命名空间前缀——
 *       不再新增一个「部署时可能忘了配」的密钥；前缀保证它与字段查找摘要之间不可互换。
 *   <li>**用途写进签名**（{@code up} 上传 / {@code rd} 读图）：否则一个读地址的凭证能被拿去覆盖文件内容。
 *   <li>**过期时间在签名内**：改过期时间即失效，不需要额外的状态存储，也就不需要清理任务。
 * </ul>
 */
public final class UploadTokens {

    public static final String PURPOSE_UPLOAD = "up";
    public static final String PURPOSE_READ = "rd";

    private static final String NAMESPACE = "upload-token:v1|";
    private static final String SEPARATOR = ".";

    private final FieldCipher cipher;

    public UploadTokens(FieldCipher cipher) {
        this.cipher = cipher;
    }

    /** 签发一个凭证。 */
    public String issue(String purpose, long fileId, long ownerUserId, Instant expiresAt) {
        String payload = payload(purpose, fileId, ownerUserId, expiresAt);
        return purpose + SEPARATOR + expiresAt.getEpochSecond() + SEPARATOR + sign(payload);
    }

    /**
     * 校验凭证。
     *
     * <p>返回是否通过，**不区分「签名不对」与「过期」**——对调用方而言两者都只是「这个地址不能用了」，
     * 区分开只会给探测者多一条信息（他能据此判断签名是否猜对）。
     */
    public boolean verify(String token, String purpose, long fileId, long ownerUserId, Instant now) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\" + SEPARATOR);
        if (parts.length != 3 || !parts[0].equals(purpose)) {
            return false;
        }
        long expiresEpoch;
        try {
            expiresEpoch = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        Instant expiresAt = Instant.ofEpochSecond(expiresEpoch);
        String expected = sign(payload(purpose, fileId, ownerUserId, expiresAt));
        boolean signatureOk = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8));
        // 先比签名再比时间：时间不敏感，签名才是关键
        return signatureOk && expiresAt.isAfter(now);
    }

    private String payload(String purpose, long fileId, long ownerUserId, Instant expiresAt) {
        return NAMESPACE + purpose + "|" + fileId + "|" + ownerUserId + "|" + expiresAt.getEpochSecond();
    }

    private String sign(String payload) {
        // 复用字段级 HMAC：同一把密钥、不同命名空间，互不通用
        return cipher.lookupHash(payload);
    }
}
