-- 提醒与消息中心（切片 #99，决策见 ADR-0019）
--
-- 三张表各管一件事：
--   message           消息中心的内容（健康提醒 + 业务通知两类共用一张表，未读/已读是跨类行为）
--   reminder_setting  用户按类型的开关（红色等级的提醒不可关闭，见 ADR-0019）
--   reminder_rule     提醒的阈值与启用状态（**业务可调项必须入库**：ADR-0010 + #64 点名的缺口）
--
-- 与交付文档 7.2 的偏离（ADR-0019 已记）：文档只有一张 reminder 表；这里改名为 message、
-- 加 kind（提醒 / 通知）、dedup_key（幂等去重）、read_at（多端已读要能区分时间）、
-- channel_state（二期接浏览器通知/邮件时扩这里，本期恒为 in_site）。

-- 档案记录表补两列：提醒规则要查询它们，**不能让查询去解析 content 里的 JSON**
--   due_on       疫苗/驱虫的下次应接种日（疫苗提醒的唯一依据）
--   numeric_value 体重等数值型分项的取值（趋势提醒要算变化幅度）
-- 这是 ADR-0019 所需的字段，与 ADR-0018 记的 record_date/abnormal 是同一类偏离：实现所必需。
ALTER TABLE `archive_record`
  ADD COLUMN `due_on` DATE DEFAULT NULL COMMENT '下次应接种日（防疫分项用；空=不提醒）' AFTER `record_date`,
  ADD COLUMN `numeric_value` DECIMAL(10, 2) DEFAULT NULL COMMENT '数值型分项的取值（体重等）' AFTER `content`,
  ADD KEY `idx_due_on` (`due_on`);

-- 幂等约束要**只作用于打卡分项**：V3 的唯一键是 (pet_id, record_date, category)，
-- 那是为「一天一项一条」的打卡设计的；但防疫记录（category=7）是**列表**——
-- 同一天录两种疫苗、或一次录入多条，都会撞这个键。用生成列把约束收窄：
-- 打卡分项（1–6）生成槽值、防疫等分项为 NULL，而唯一索引允许多个 NULL。
ALTER TABLE `archive_record`
  DROP INDEX `uk_pet_date_category`,
  ADD COLUMN `checkin_slot` VARCHAR(64)
      GENERATED ALWAYS AS (
        CASE WHEN `category` BETWEEN 1 AND 6
             THEN CONCAT(`pet_id`, ':', `record_date`, ':', `category`)
             ELSE NULL END
      ) STORED COMMENT '打卡幂等槽：同宠物+日期+分项唯一；防疫等多条记录为 NULL 不受限',
  ADD UNIQUE KEY `uk_checkin_slot` (`checkin_slot`);

CREATE TABLE `message` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '收件人（逻辑引用 user.id）',
  `pet_id`        BIGINT UNSIGNED          DEFAULT NULL COMMENT '相关宠物；业务通知可能不针对某只宠物',
  `kind`          TINYINT         NOT NULL DEFAULT 1 COMMENT '1健康提醒(系统主动生成) 2业务通知(事件驱动)',
  `type`          TINYINT         NOT NULL COMMENT '1疫苗2驱虫3日常4异常5趋势6慢病老年7订单8券9邀请10系统',
  `title`         VARCHAR(128)    NOT NULL COMMENT '短标题，前端列表直接展示',
  `content`       VARCHAR(1024)            DEFAULT NULL COMMENT '详情文案',
  `risk_level`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0无 1绿 2黄 3红；**红色提醒不可关闭**',
  `remind_at`     DATETIME        NOT NULL COMMENT '该提醒指向的时间：到期日 / 应打卡日 / 事件时间',
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '1已发 2已读 3已取消',
  `read_at`       DATETIME                 DEFAULT NULL COMMENT '已读时间（多端同步与「什么时候看的」都靠它）',
  `dedup_key`     VARCHAR(96)     NOT NULL COMMENT '幂等键：宠物+类型+窗口；同键更新内容而不新增（ADR-0019）',
  `action_hint`   VARCHAR(32)              DEFAULT NULL COMMENT '按钮文案，如「去打卡」',
  `action_target` VARCHAR(128)             DEFAULT NULL COMMENT '按钮跳转的前端路由',
  `channel_state` VARCHAR(64)     NOT NULL DEFAULT 'in_site' COMMENT '已送达的通道；二期扩 browser / email',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedup` (`dedup_key`),
  KEY `idx_user_status` (`user_id`, `status`, `created_at`),
  KEY `idx_user_kind_status` (`user_id`, `kind`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息中心（提醒 + 业务通知）';

CREATE TABLE `reminder_setting` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `type`       TINYINT         NOT NULL COMMENT '提醒类型，取值同 message.type',
  `enabled`    TINYINT         NOT NULL DEFAULT 1 COMMENT '1开 0关；红色等级的提醒不允许置 0',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`   VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted` TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_type` (`user_id`, `type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户提醒开关';

CREATE TABLE `reminder_rule` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type`       TINYINT         NOT NULL COMMENT '提醒类型，取值同 message.type',
  `enabled`    TINYINT         NOT NULL DEFAULT 1 COMMENT '平台级总开关（运营可关掉某一类，用户开关之上再一层）',
  `config`     JSON            NOT NULL COMMENT '阈值等参数，如 {"advanceDays":7,"maxPerPetPerDay":3}',
  `remark`     VARCHAR(256)             DEFAULT NULL COMMENT '给运营看的说明：这条规则在做什么',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`   VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted` TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type` (`type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '提醒规则与阈值（业务可调项，ADR-0010）';

-- 默认规则：阈值写在数据里而不是代码里（ADR-0010 / #64 点名的缺口）
-- type=0 是**全局策略行**（不是提醒类型）：目前放「每宠物每日上限」这类跨类型参数
INSERT INTO `reminder_rule` (`type`, `enabled`, `config`, `remark`) VALUES
  (0, 1, '{"maxPerPetPerDay": 3}', '全局策略：每宠物每天最多 3 条提醒，超出按紧迫度保留前几条'),
  (1, 1, '{"advanceDays": 7, "overdueGraceDays": 30}', '疫苗到期前 7 天开始提醒；过期后 30 天内继续提醒'),
  (2, 1, '{"advanceDays": 7, "overdueGraceDays": 30}', '驱虫到期前 7 天开始提醒；过期后 30 天内继续提醒'),
  (3, 1, '{}', '当天未打卡时提醒（每天最多一条）'),
  (4, 1, '{}', '打卡里标注异常时即时提醒'),
  (5, 1, '{"weightChangePercent": 5, "windowDays": 7}', '近 7 天体重变化 ≥5% 触发'),
  (6, 1, '{"intervalMonths": 3}', '老年/慢病照护提醒，每 3 个月一条');
