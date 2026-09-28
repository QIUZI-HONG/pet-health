-- AI 咨询留痕（切片 #98 的验收标准「每次调用留痕 model_version / prompt_version / 延迟」）
--
-- 一张表装一次咨询的全部结果：分级、原因、行动建议、命中情况、降级情况、模型与提示词版本、延迟。
-- **为什么留这么细**：ADR-0017 记录过模型输出会波动，出现「分级漂移」时要能回答
-- 「当时用的是哪个模型、哪版提示词、有没有图片、延迟多少、是不是降级答复」——
-- 少任何一列，那次归因就做不成。
--
-- `question_enc`：问题原文按病历口径做**字段级加密**（ADR-0013）。健康描述属于敏感数据，
-- 明文入库等于把用户的病史摊在数据库备份里；加密后不可检索是有意接受的代价。
--
-- 与交付文档 7.2 的偏离：文档里是 `ai_session` + `ai_message` 两张表（会话与消息）。
-- 本期不做多轮会话（#101 之后再说），一次咨询就是一条记录；真要做多轮时再加会话表，
-- 而不是先建一张大部分字段为空的表。

CREATE TABLE `ai_consult` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT UNSIGNED NOT NULL,
  `pet_id`            BIGINT UNSIGNED NOT NULL,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '与 Java/AI 两侧日志串起来',
  `question_enc`      VARCHAR(2048)   NOT NULL COMMENT '问题原文，字段级加密（ADR-0013）',
  `image_count`       INT             NOT NULL DEFAULT 0 COMMENT '实际送去判断的图片张数',
  `risk_level`        TINYINT         NOT NULL DEFAULT 2 COMMENT '1绿2黄3红；降级时保守给 2 或 3',
  `possible_causes`   JSON                     DEFAULT NULL,
  `action_suggestion` VARCHAR(1024)            DEFAULT NULL,
  `need_hospital`     TINYINT         NOT NULL DEFAULT 0,
  `care_tips`         JSON                     DEFAULT NULL,
  `red_flag_hits`     JSON                     DEFAULT NULL COMMENT '命中的红线编号；非空=未经模型（ADR-0021）',
  `guard_hits`        JSON                     DEFAULT NULL COMMENT '输出层护栏命中（剂量/越界表述）',
  `red_flag_check`    VARCHAR(16)     NOT NULL DEFAULT 'ok' COMMENT 'ok/unavailable：红线层是否生效',
  `degraded`          TINYINT         NOT NULL DEFAULT 0,
  `degrade_reason`    VARCHAR(256)             DEFAULT NULL,
  `model_name`        VARCHAR(64)     NOT NULL DEFAULT '',
  `model_version`     VARCHAR(64)     NOT NULL DEFAULT '',
  `prompt_version`    VARCHAR(64)     NOT NULL DEFAULT '',
  `latency_ms`        INT             NOT NULL DEFAULT 0,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_pet_time` (`pet_id`, `created_at`),
  KEY `idx_risk` (`risk_level`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 咨询留痕';
