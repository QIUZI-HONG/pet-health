package com.pethealth.file.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 文件存储的技术参数（ADR-0020）。
 *
 * <p>这些是**技术参数**，改要重启——与 AI 配置同理（ADR-0010）：业务可调项（谁能传、传几张、
 * 什么类型）放在 {@code file_rule} 这类表里才入库，但目前的限制来自交付文档 13.2，是硬规定，
 * 所以暂时留在这里；等运营真要去调它时再迁进库。
 *
 * @param driver     存储驱动：{@code local}（本期）/ {@code cos}（生产，待 #66）
 * @param root       本地盘的根目录；只对 local 驱动有意义
 * @param maxBytes   单文件上限，交付文档 13.2：单图 ≤ 10MB
 * @param maxCount   一次 presign 的文件数上限，交付文档 13.2：最多 9 张
 * @param presignTtl 上传凭证有效期——够慢网络传完 9 张图即可，不宜长
 * @param readTtl    读地址（签名 URL）有效期，与「详情缓存 1 分钟」同量级
 */
@ConfigurationProperties(prefix = "app.file")
public record FileStorageProperties(
        String driver,
        String root,
        long maxBytes,
        int maxCount,
        Duration presignTtl,
        Duration readTtl) {

    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_MAX_COUNT = 9;

    public FileStorageProperties {
        driver = (driver == null || driver.isBlank()) ? LocalFileStorage.DRIVER : driver;
        root = (root == null || root.isBlank()) ? "data/files" : root;
        maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
        maxCount = maxCount <= 0 ? DEFAULT_MAX_COUNT : maxCount;
        presignTtl = (presignTtl == null || presignTtl.isZero()) ? Duration.ofMinutes(30) : presignTtl;
        readTtl = (readTtl == null || readTtl.isZero()) ? Duration.ofMinutes(10) : readTtl;
    }
}
