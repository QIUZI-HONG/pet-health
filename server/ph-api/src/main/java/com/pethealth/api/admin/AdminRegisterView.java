package com.pethealth.api.admin;

import com.pethealth.api.app.UserProfile;

/**
 * 运营后台注册的结果（契约 `admin.yaml` 的同名 schema）。
 *
 * <p><b>刻意不含令牌</b>：运营后台是名单制（`CONSOLE_ADMIN_USER_IDS`，fail-closed），
 * 注册只负责把账号建出来，能不能进由名单决定。给一个「注册即登录」的令牌是做不到的
 * （登录那条路径会当场以 40300 拒绝不在名单里的账号），所以这里如实返回「账号已建 + 还差什么」。
 *
 * @param user   刚建出来的账号（手机号已脱敏）
 * @param notice 给用户看的那句话（下一步要做什么）
 */
public record AdminRegisterView(UserProfile user, String notice) {
}
