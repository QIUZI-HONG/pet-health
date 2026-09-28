package com.pethealth.file.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * 一个文件对象（表 {@code file_object}，迁移 V5）。
 *
 * <p>原图与派生物（缩略图 / 局部特写）共用这一张表：派生物用自己的 {@code role} 与
 * {@code originalId} 指向原图，生命周期跟着原图走。
 *
 * <p>状态机只有三步，取值见 {@link #STATUS_PENDING} 等常量。**待传行是必需的**：
 * 对象存储的回调验签要对上一个预先存在的意图，否则拿到合法签名就能凭空造记录（ADR-0020）。
 */
@TableName("file_object")
public class FileObject extends BaseEntity {

    /** 已签发上传凭证，等浏览器送字节。 */
    public static final int STATUS_PENDING = 0;
    /** 字节已落存储，可以读。 */
    public static final int STATUS_STORED = 1;
    /** 用户取消或凭证过期。 */
    public static final int STATUS_DISCARDED = 2;

    public static final String ROLE_ORIGINAL = "original";
    public static final String ROLE_CLOSEUP = "closeup";
    public static final String ROLE_THUMB = "thumb";

    private Long ownerUserId;
    private Long petId;
    private String bizType;
    private String role;
    private Long originalId;
    private String mime;
    private Long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    private String storageDriver;
    private String storageKey;
    private Integer status;
    private LocalDateTime expiresAt;

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getOriginalId() {
        return originalId;
    }

    public void setOriginalId(Long originalId) {
        this.originalId = originalId;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getStorageDriver() {
        return storageDriver;
    }

    public void setStorageDriver(String storageDriver) {
        this.storageDriver = storageDriver;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
