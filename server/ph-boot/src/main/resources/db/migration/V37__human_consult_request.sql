-- 转人工咨询的工单（F006 的「转人工」出口）
--
-- 口径（本轮裁决，见验收报告第十二节）：**转人工 = 登记一条人工咨询请求，由平台运营跟进**。
-- 不接支付：交付文档写「39 元/次」，而本项目钱在门店付、平台不经手资金（ADR-0036），
-- 没有线上收费路径——所以这条链路只做「把请求交到人手上」，收费如果要做，得先说清收款主体。
-- 也不直接派给服务者：派给谁需要一套匹配规则（就近？接诊能力？排班？），那是一条独立的决策；
-- 运营先接住，人工判断后再转给合适的门店（线下动作）。
--
-- 三条刻意的设计：
--
--   1. **`consult_id` 唯一**：一次 AI 咨询只能转人工一次——幂等键就是唯一键（与 ADR-0052 第三节
--      同一条纪律），重复提交返回同一条工单，而不是给运营堆两张一样的单子；
--   2. **不存用户自由文本**：工单只引用咨询（咨询的问题原文已在 `ai_consult.question_enc`，
--      字段级加密，ADR-0013）。再开一个自由文本字段等于多一处加密面，而它并不带来新信息——
--      要补充说明，用户可以再问一次 AI；
--   3. **`reply_note` 不落库**：运营的回复正文直接写进站内消息（`message.content`），
--      工单只记状态与处置人。同一条回复有两个副本迟早会不一致。

CREATE TABLE `human_consult_request` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '发起人（逻辑引用 user.id）',
  `pet_id`      BIGINT UNSIGNED NOT NULL COMMENT '咨询的宠物（逻辑引用 pet.id）',
  `consult_id`  BIGINT UNSIGNED NOT NULL COMMENT '关联的 AI 咨询（逻辑引用 ai_consult.id）；唯一，即幂等键',
  `risk_level`  TINYINT         NOT NULL DEFAULT 0 COMMENT '转人工时那次咨询的风险等级：0 未知 / 1 绿 / 2 黄 / 3 红',
  `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 已回复 / 2 已关闭',
  `operator_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处置人（运营），0 表示还没人处理',
  `handled_at`  DATETIME                 DEFAULT NULL COMMENT '处置时间',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consult` (`consult_id`),
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '转人工咨询工单（F006；不接支付，见文件头部的口径）';
