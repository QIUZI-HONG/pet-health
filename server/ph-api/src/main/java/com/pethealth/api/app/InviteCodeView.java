package com.pethealth.api.app;

import java.time.LocalDateTime;

/**
 * 一条邀请码，对应 contract/app.yaml 的 {@code InviteCodeView}。
 *
 * <p>{@code invitePath} 是**相对路径**（形如 {@code /register?invite=ABC123}），
 * 前端拼上域名后用；「落地页记住 7 天」是前端行为（ADR-0039 第一节）。
 *
 * <p>码是给用户念、给用户抄的**可读串**，不是安全凭证：归因的安全性靠「归因只有一次机会 +
 * 反作弊判据」兜住，不靠码本身难猜。
 */
public record InviteCodeView(
        Long id,
        String code,
        Integer channel,
        String invitePath,
        Integer status,
        LocalDateTime createdAt) {
}
