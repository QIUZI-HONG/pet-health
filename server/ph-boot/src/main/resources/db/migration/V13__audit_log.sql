-- 操作审计（ADR-0028）
--
-- 记两类动作：**账号安全事件**（注册、登录成功、登录失败）与**敏感数据访问**（数据导出、账号注销）。
-- 交付文档 6.3 要求「管理员操作、敏感数据访问全量记录」，管理端接口还不存在（#118），
-- 动作码是 VARCHAR 而不是枚举，届时加码不用改表。
--
-- 三条设计取舍（理由见 ADR-0028）：
--   1. **不存 PII 明文**：`subject_ref` 存手机号的 HMAC（不可还原，与 `user.phone_hash` 同一算法）。
--      审计表的价值是「不可否认」，不是「再存一份个人信息」；原始手机号、昵称、症状文本一律不落这里。
--   2. **append-only**：只有 created_* 有意义，`updated_at` 由框架列约定带着但不使用；
--      审计行能删就等于没有，所以 `is_deleted` 恒为 0，不做软删除。
--   3. **写入用独立事务**（REQUIRES_NEW，见 AuditRecorder）：登录失败的业务事务注定回滚，
--      审计若跟着它走，最该留记录的那一次反而留不下来。
--
-- 保留期暂不设自动清理：删除策略要从合规文本（#74 / #120）倒推，不由工程随手定。
-- 审计行不随账号注销删除——`subject_ref` 是 HMAC，不构成残留的个人信息。

CREATE TABLE `audit_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `action`       VARCHAR(48)     NOT NULL COMMENT '动作码：register / login_success / login_failed / account_export / account_deactivate',
  `target_type`  VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '对象类型：user …（后续可扩展 file / order）',
  `target_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '对象 id',
  `subject_ref`  VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '主体引用：手机号 HMAC，用于串同一账号的多次尝试；禁止放明文',
  `ip`           VARCHAR(45)     NOT NULL DEFAULT '' COMMENT '来源 IP；代理落地前取 remoteAddr（不信任 X-Forwarded-For，ADR-0028）',
  `detail`       VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '补充说明；禁止放 PII 明文',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id，0 表示未登录（如登录失败）或系统写入',
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '与业务日志串起来——审计行必须能追回那一次请求',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_action_time` (`action`, `created_at`),
  KEY `idx_subject_time` (`subject_ref`, `created_at`),
  KEY `idx_operator_time` (`created_by`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作审计日志';
