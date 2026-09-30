-- 健康报告（切片 #115，决策见 ADR-0031）
--
-- 报告是**派生视图**：内容由规则模板从 `health_score` 按天的行与 `archive_record` 拼出来，
-- 不经过模型（ADR-0031 决定一）。落库的理由只有一个：**历史报告要能按当时的计算复现**
-- ——「历史报告可回看」这条验收标准，实时重算做不到（以后改了模板或评分口径，
-- 那段历史会给出与当时不同的结论，而用户会拿它做对比）。
--
-- 幂等键是 (pet_id, type, period_start)：读取时惰性生成当期（与消息中心的惰性补算同一模式），
-- 同一天再读是**更新当期那一行**（当天又打了卡，当期报告应跟着变）；跨周期是新的行。
--   ⚠️ 「历史周期不回改」不靠数据库约束，靠代码：写入前先判断该周期的 period_end 是否已过。

CREATE TABLE `health_report` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pet_id`       BIGINT UNSIGNED NOT NULL COMMENT '宠物 id（逻辑引用 pet.id，无物理外键）',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '归属用户（越权校验用）',
  `type`         TINYINT         NOT NULL COMMENT '1周报2月报3行为改善报告4卫生评分',
  `period_start` DATE            NOT NULL COMMENT '周期起点（周报=周一，月报=1 号，其余=近 30 天窗口的起点）',
  `period_end`   DATE            NOT NULL COMMENT '周期终点；当期报告是今天（周期还没走完）',
  `grade`        VARCHAR(16)              DEFAULT NULL COMMENT '同期档位文案（良好/尚可/需关注）；无数据为 NULL',
  `total_score`  TINYINT                  DEFAULT NULL COMMENT '周期内总分均值；无数据为 NULL（不是 0 分）',
  `tier`         TINYINT         NOT NULL DEFAULT 1 COMMENT '1基础版2完整版；**本期只标注不拦截**（ADR-0031 决定五）',
  `payload`      JSON            NOT NULL COMMENT '报告正文：{"headline":..,"lines":[..],"stats":{..},"notice":..}',
  `generated_at` DATETIME        NOT NULL COMMENT '生成时间（历史报告记的是「出报告那一天」的口径）',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id，0 表示系统写入',
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pet_type_period` (`pet_id`, `type`, `period_start`),
  KEY `idx_pet_type_period_desc` (`pet_id`, `type`, `period_start` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '健康报告（周报/月报/行为改善/卫生评分，按周期一行）';
