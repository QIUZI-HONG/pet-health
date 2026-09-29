package com.pethealth.account.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 一条操作审计记录（ADR-0028）。**只增不改**，见 {@code V13__audit_log.sql} 的说明。
 *
 * <p>哪些列不该出现什么，比该出现什么更重要：{@code subjectRef} 只放手机号的 HMAC，
 * {@code detail} 不放 PII 明文——审计要的是「能证明发生过」，不是「再存一份个人信息」。
 * 用例 {@code AuditLogTest.noPlaintextPii} 会逐列扫一遍手机号明文，改这里时别把它绕过。
 */
@TableName("audit_log")
public class AuditLog extends BaseEntity {

    /** 账号注册成功。 */
    public static final String ACTION_REGISTER = "register";
    /** 登录成功。 */
    public static final String ACTION_LOGIN_SUCCESS = "login_success";
    /** 登录失败（手机号不存在或口令不对，两者不区分——与接口对外的口径一致）。 */
    public static final String ACTION_LOGIN_FAILED = "login_failed";
    /** 导出账号数据（敏感数据访问）。 */
    public static final String ACTION_ACCOUNT_EXPORT = "account_export";
    /** 账号注销（敏感数据访问 + 不可逆动作）。 */
    public static final String ACTION_ACCOUNT_DEACTIVATE = "account_deactivate";

    /** 目前只有用户一种对象；管理端接口（#118）落地后可扩展 file / order 等。 */
    public static final String TARGET_USER = "user";

    private String action;
    private String targetType;
    private Long targetId;
    private String subjectRef;
    private String ip;
    private String detail;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public void setSubjectRef(String subjectRef) {
        this.subjectRef = subjectRef;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
