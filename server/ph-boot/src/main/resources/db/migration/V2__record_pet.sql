-- 档案域：宠物表（切片 #94 的建档、多宠、软删除）
--
-- 与交付文档 7.2 的差异：
--   1. 文档用 `status`（1正常2软删除）表示软删除；本项目统一用 `is_deleted`（docs/conventions.md
--      与 ADR-0011 的全局约定），另外加 `deleted_at` 支撑「30 天内可恢复」这个窗口。
--   2. 补审计列 `created_by` / `updated_by` / `trace_id`（ADR-0011）。
--
-- 索引说明：列表查询永远是「按主人 + 未删除」，所以索引带上 `is_deleted`；
-- 回收站查询是「按主人 + 已删除 + 删除时间」，共用同一个索引即可。

CREATE TABLE `pet` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '主人 id（逻辑引用 user.id，无物理外键）',
  `name`          VARCHAR(64)     NOT NULL COMMENT '宠物昵称',
  `species`       TINYINT         NOT NULL COMMENT '1犬2猫',
  `breed`         VARCHAR(64)              DEFAULT NULL COMMENT '品种',
  `gender`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0未知1公2母',
  `birthday`      DATE                     DEFAULT NULL,
  `weight`        DECIMAL(6, 2)            DEFAULT NULL COMMENT '体重 kg',
  `avatar`        VARCHAR(512)             DEFAULT NULL,
  `is_sterilized` TINYINT         NOT NULL DEFAULT 0 COMMENT '是否绝育',
  `is_chronic`    TINYINT         NOT NULL DEFAULT 0 COMMENT '是否有慢病',
  `chronic_desc`  VARCHAR(512)             DEFAULT NULL COMMENT '慢病描述',
  `deleted_at`    DATETIME                 DEFAULT NULL COMMENT '软删除时间，用于 30 天恢复期',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id，0 表示系统写入',
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_deleted` (`user_id`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '宠物表';
