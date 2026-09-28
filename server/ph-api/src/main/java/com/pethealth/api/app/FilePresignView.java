package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 一次上传的凭证，对应 contract/app.yaml 的 {@code FilePresignView}。
 *
 * <p>{@code uploadUrl} 是**相对地址**：前端与后端同源（开发期走 vite 代理），换域名不用重新下发。
 *
 * @param fileId   文件 id；上传完成后用它读回或关联业务
 * @param role     回显用途，前端据此区分「原图 / 局部特写」两栏
 * @param maxBytes 本次允许的最大字节数，前端可在选择阶段就拦住超大文件
 */
public record FilePresignView(
        long fileId,
        String uploadUrl,
        LocalDateTime expiresAt,
        long maxBytes,
        String role) {
}
