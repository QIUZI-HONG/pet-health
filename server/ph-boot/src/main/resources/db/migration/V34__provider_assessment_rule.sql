-- 服务者考核（F022）的**规则**：三项权重、三项达标线、三档等级阈值（切片 #58 / #114）
--
-- 决策：ADR-0039 第三节（总分 = 拉新 40% + 券 40% + 过程 20%，每月 1 日算上月；等级影响 AI 推荐
-- 优先级、区域保护规则留白）、ADR-0050 第四节（缺项两种口径）、ADR-0049 第七节（过程分含服务者
-- 取消率）、ADR-0037 第一节（规则配置只归超级管理员）。本表的实现取舍写在 ADR-0052。
--
-- 为什么规则入库而不是写成代码常量（ADR-0010 的三层配置）：
--   权重与阈值是**运营会调的业务可调项**（交付文档 F022 的 40/40/20 是产品定的），
--   而算法与判据是代码常量。混在一起会让「改个权重」变成一次发版。
--
-- 三条刻意的设计：
--
--   1. **单行表**（`id = 1`）：规则是「当前生效的那一份」，不是可以并存的多个方案。
--      多行会立刻引出「哪一行生效」这个必须回答的问题，而这一期没有 A/B 的需求。
--      历史账期不受改规则影响——每次算分把当时的权重与达标线**快照进分表**（V35）。
--   2. **达标线为 0 = 该项还没定要求**：按 ADR-0050 第四节，该维度**不参与计分并重算权重**，
--      而不是记 0 分。所以 0 不是一个「很松的目标」，它是「平台侧无该维度要求」的编码。
--   3. **种子里给的是占位值**：40/40/20 来自交付文档（不是编的）；三档阈值（80 / 90）与
--      接单响应达标线（30 分钟）**没有规则依据**，取值依据列在 ADR-0052 的待澄清里，
--      运营随时可改。拉新与券的达标线**种子里留 0（未配置）**：编一个「月拉新 5 人」就是
--      替甲方做产品决策（与 ADR-0046 第五节「阶梯奖励物留空是有效状态」同一条纪律）。
--
-- 等级与 `recommend_priority` 的关系（ADR-0039 第三节「等级影响 AI 推荐优先级」）：
--   本表存的是**映射关系**（哪个等级对应哪个优先级），分表存的是**算出来的结果**。
--   推荐逻辑本身（谁排在前面）不在本切片——本切片只保证「等级与优先级存得下来、查得到」。

CREATE TABLE `assessment_rule` (
  `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invite_weight`           INT             NOT NULL DEFAULT 40 COMMENT '拉新项权重（百分比，ADR-0039 第三节）',
  `coupon_weight`           INT             NOT NULL DEFAULT 40 COMMENT '券项权重（百分比）',
  `process_weight`          INT             NOT NULL DEFAULT 20 COMMENT '过程项权重（百分比）；三项之和恒为 100（写接口校验）',
  `invite_target`           INT             NOT NULL DEFAULT 0 COMMENT '拉新达标线（有效邀请数）；**0 = 未配置 → 该维度不参与并重算权重**（ADR-0050 第四节）',
  `coupon_target`           DECIMAL(10,2)   NOT NULL DEFAULT 0.00 COMMENT '券达标线（完成率 × 核销数，ADR-0044 的口径）；0.00 = 未配置 → 该维度不参与',
  `response_minutes_target` INT             NOT NULL DEFAULT 30 COMMENT '接单响应时长的达标线（分钟）：不超过它记满分，超过按 目标/实际 比例扣',
  `created_at`              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`              BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`              BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`                VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`              TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考核规则（单行：权重 / 达标线）';

-- 单行种子：权重取交付文档 F022 的 40/40/20；达标线留 0（未配置），响应时长占位 30 分钟。
INSERT INTO `assessment_rule`
  (`id`, `invite_weight`, `coupon_weight`, `process_weight`,
   `invite_target`, `coupon_target`, `response_minutes_target`)
VALUES (1, 40, 40, 20, 0, 0.00, 30);

-- 等级档位：**等级本身固定三档**（CONTEXT.md 与 `provider.level` 的取值：基础 / 优选 / 战略合作），
-- 可改的是「进入这一档的最低总分」与「对应的 AI 推荐优先级」。
--
-- `recommend_priority` 越小越优先（1 最高）。它只是个**存储的映射**：本切片不实现推荐排序，
-- 只保证考核记录与 `provider.level` 上留有这个值（ADR-0039 第三节的「影响 AI 推荐优先级」，
-- 怎么用见 ADR-0052 的待澄清）。
CREATE TABLE `assessment_level_rule` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `level`              TINYINT         NOT NULL COMMENT '1基础2优选3战略合作',
  `level_name`         VARCHAR(16)     NOT NULL COMMENT '等级中文名（由服务端给出，结算时快照进分表）',
  `min_score`          DECIMAL(5,2)    NOT NULL COMMENT '进入这一档的最低总分（闭区间，两位小数，不用浮点）',
  `recommend_priority` TINYINT         NOT NULL DEFAULT 3 COMMENT 'AI 推荐优先级：1最高2较高3普通',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level` (`level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考核等级档位（阈值与推荐优先级可配）';

-- 三档种子。阈值 80 / 90 是**占位值**（没有规则依据，ADR-0052 待澄清里点名）；
-- 基础档必须是 0.00——否则会出现「谁都不匹配」的分数段（写接口也校验这一条）。
INSERT INTO `assessment_level_rule` (`level`, `level_name`, `min_score`, `recommend_priority`) VALUES
  (1, '基础', 0.00, 3),
  (2, '优选', 80.00, 2),
  (3, '战略合作', 90.00, 1);
