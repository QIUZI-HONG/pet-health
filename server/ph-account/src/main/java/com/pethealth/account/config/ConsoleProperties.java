package com.pethealth.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 两个后台（控制台）登录的准入配置。
 *
 * <p><b>为什么要有这个类</b>：服务者后台与运营后台是**独立登录域**（ADR-0012），而账号是
 * **同一批**（ADR-0035 决定 4：「绑定现有账号 id」，不另建一套后台账号体系）。所以「这个人
 * 能不能进控制台」不能靠「他有没有账号」来判断——每个 C 端用户都有账号。两个域各有自己的判据：
 *
 * <ul>
 *   <li><b>provider 域</b>：**不设准入**——任何启用中的账号都能登进来。这不是放宽而是流程要求：
 *       BPM-4 的第一句是「商家申请入驻」（交付文档原文，文档用词），而申请要先能进控制台，
 *       把「已有服务者绑定」当作登录前置就永远没人能提交第一份申请（鸡生蛋）。
 *       进来以后**看得到什么**由各接口已有的绑定校验兜着（未绑定 / 审核中 / 已驳回
 *       读门店类接口 40400、写 40300），细节见 {@code ConsoleAuthService#requireAdmitted}；
 *   <li><b>admin 域</b>：必须在 {@link #adminUserIds()} 名单里。**fail-closed**：名单为空时
 *       谁都进不去，而不是「谁都能进」。理由与考核超管名单（ADR-0052）完全相同——
 *       运营后台看到的是全平台数据，让它默认对所有人开放是把钥匙挂在门上。
 * </ul>
 *
 * <p><b>没有角色体系是当前的真实状态</b>：JWT 里只有登录域，不带角色，所以进得来以后
 * 「运营 vs 超级管理员」仍然由各自的名单（如 {@code app.assessment.super-admin-user-ids}）
 * 二次判定。这条欠账写在 ADR-0035 的「需要协调」第 3 条，落地真实 RBAC 后这个类会删掉。
 *
 * @param adminUserIds 允许进入运营后台的账号 id，逗号分隔；空 = 都不允许（fail-closed）
 */
@ConfigurationProperties(prefix = "app.console")
public record ConsoleProperties(String adminUserIds) {

    /** 解析成 id 集合；空白项与非法数字一律忽略（配置写错不该让应用起不来，但也绝不放宽准入）。 */
    public Set<Long> adminIds() {
        if (adminUserIds == null || adminUserIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> {
                    try {
                        return Long.valueOf(token);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
