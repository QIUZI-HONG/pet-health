-- 档案域：健康记录表（打卡的载体）与健康评分表
--
-- 依据：交付文档 7.2 的 archive_record / health_score，加 ADR-0018 定的两处偏离：
--   1. `record_date DATE` —— 文档靠 created_at 判定「属于哪一天」，但**补录必须有独立的业务日期**
--      （否则补录的记录会算到今天头上，连续天数与评分窗口都错）。
--   2. `abnormal TINYINT` —— 评分要按近 7 天窗口聚合异常项数，用列比每次解析 content JSON 干净。
-- 另按 ADR-0011 补齐审计列（created_by / updated_by / trace_id / is_deleted）。
--
-- 幂等靠唯一键：同「宠物 + 业务日期 + 分项」只允许一条，重复提交走更新（打卡可改主意）。

CREATE TABLE `archive_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pet_id`      BIGINT UNSIGNED NOT NULL COMMENT '宠物 id（逻辑引用 pet.id，无物理外键）',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '录入时的主人 id（越权校验与审计用）',
  `record_date` DATE            NOT NULL COMMENT '业务日期：当场录入=当天，补录=补的那一天',
  `category`    TINYINT         NOT NULL COMMENT '1体重2饮食3排泄4行为5情绪6卫生7防疫8就医9其他',
  `content`     VARCHAR(1024)            DEFAULT NULL COMMENT '结构化内容 JSON，如 {"status":"normal","note":""}',
  `score`       TINYINT                  DEFAULT NULL COMMENT '该次记录的主观评分（可选）',
  `images`      JSON                     DEFAULT NULL COMMENT '图片 URL 数组',
  `abnormal`    TINYINT         NOT NULL DEFAULT 0 COMMENT '1=用户标注为异常（评分按窗口聚合它）',
  `backfilled`  TINYINT         NOT NULL DEFAULT 0 COMMENT '1=补录（记录时有标注，不冒充当场录入）',
  `source`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1用户2AI3服务者报工',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id，0 表示系统写入',
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pet_date_category` (`pet_id`, `record_date`, `category`),
  KEY `idx_pet_date` (`pet_id`, `record_date`),
  KEY `idx_pet_category_date` (`pet_id`, `category`, `record_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '健康档案记录表（打卡载体）';

CREATE TABLE `health_score` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pet_id`      BIGINT UNSIGNED NOT NULL,
  `total_score` TINYINT         NOT NULL COMMENT '总分 = 已计入维度的等权平均',
  `physiology`  TINYINT                  DEFAULT NULL COMMENT '生理：体重/饮食/排泄',
  `behavior`    TINYINT                  DEFAULT NULL COMMENT '行为：行为/情绪',
  `hygiene`     TINYINT                  DEFAULT NULL COMMENT '卫生',
  `epidemic`    TINYINT                  DEFAULT NULL COMMENT '防疫：无疫苗/驱虫记录时为 NULL（不计入）',
  `elderly`     TINYINT                  DEFAULT NULL COMMENT '老年专项：未开启时为 NULL（不计入）',
  `included_dimensions` TINYINT NOT NULL DEFAULT 0 COMMENT '实际计入总分的维度数（便于回看算法口径）',
  `calc_date`   DATE            NOT NULL COMMENT '计算日期（按业务日期，不是写入时间）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pet_calc_date` (`pet_id`, `calc_date`),
  KEY `idx_calc_date` (`calc_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '健康评分表（每日一行，写入时实时重算）';
