-- 积分与任务（切片 #113）——决策见 ADR-0038 第四节与 ADR-0046
--
-- 八张表：账户、流水、行为分值表、任务清单、兑换档位、月度阶梯档位 / 发放记录、规则设置。
--
-- 五条刻意的设计：
--   1. **积分是整数、不是金额**：`INT`，不参与任何金额计算。它与券是**两套账**
--      （CONTEXT.md：积分只能兑换券，不能兑换现金或提现）。
--   2. **流水带 `balance_after`**：只记变动数的话，事后永远无法从流水重建当时的余额，
--      客诉时对不上账。余额与流水在同一个事务里改，账才不会漂。
--   3. **每日上限按业务日算**（`business_date`，东八区）：`counts_toward_daily_cap` 标记这条
--      是否占上限——**邀请与一次性项不占**（ADR-0038 第四节：否则 20 分的邀请奖励会被日上限吃掉）。
--      `(user_id, behavior_code, source_ref)` 唯一：同一行为的同一引用只记一次，
--      这是「同一行为不得重复发奖励」在积分侧的落点。
--   4. **行为码与任务分开**：行为（签到 / 打卡 / 邀请 / 评价 / 完善档案）是可发分的动作，
--      任务是「每日 / 每周要做什么」的展示清单（ADR-0038 第四节要求清单入库存配置）。
--      任务本身**不额外发分**——同一行为发两次奖励正是那条要被钉死的规则。
--      新增行为 = 改代码（要有地方触发它）；新增任务 = 改配置（引用已有的行为）。
--   5. **月度阶梯只落骨架**：档位表**种子里一行都没有**——门槛值与奖励没有依据，不编。
--      档位为空时批算照跑、不发券（骨架是通的），运营配好即生效。年度大奖留白。
--
-- 兑换**只兑平台补贴券**（`cost_bearer=2`）：消耗的是平台的钱，不消耗服务者的贡献额度，
-- 否则等于把兑换成本转嫁给服务者，直接抵消它的出券意愿（ADR-0038 第四节）。

-- 积分账户：一人一行。余额与累计值都在这里，流水是它的账本。
CREATE TABLE `user_point` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '用户（逻辑引用 user.id）',
  `balance`        INT             NOT NULL DEFAULT 0 COMMENT '当前可用积分（整数）',
  `total_earned`   INT             NOT NULL DEFAULT 0 COMMENT '累计获得',
  `total_spent`    INT             NOT NULL DEFAULT 0 COMMENT '累计消耗（兑换）',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分账户（余额 + 累计，整数）';

-- 积分流水：每一次变动都留痕，并带着**变动后余额**。
--
-- `change_amount` 可以是 0：`AI_ADVICE` / `SHARE` 这类任务只统计行为、不发分，
-- 但也走同一张流水——另建一张「行为表」会让任务进度有第二个真相。`balance_after` 此时等于原余额。
--
-- `business_date` 是东八区业务日：每日上限与每日频次都按它算，按 `created_at` 算会在跨零点时出错。
CREATE TABLE `point_record` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`                BIGINT UNSIGNED NOT NULL,
  `behavior_code`          VARCHAR(32)     NOT NULL COMMENT 'SIGN_IN / CHECK_IN / INVITE / REVIEW / PROFILE_COMPLETE / AI_ADVICE / SHARE',
  `change_amount`          INT             NOT NULL COMMENT '变动值：正为发放、负为消耗、0 为只记行为不发分（列名不叫 change：CHANGE 是 MySQL 保留字，手写 SQL 与框架插入都要反引号，代价大于收益）',
  `balance_after`          INT             NOT NULL COMMENT '变动后余额（据此可重建任一时刻的余额）',
  `counts_toward_daily_cap` TINYINT        NOT NULL DEFAULT 0 COMMENT '1 占每日上限 / 0 不占（邀请与一次性项）',
  `business_date`          DATE            NOT NULL COMMENT '业务日（东八区）',
  `source_ref`             VARCHAR(64)              DEFAULT NULL COMMENT '来源引用（业务日 / 订单号 / 邀请关系 id / 兑换单号），与行为码一起做幂等',
  `remark`                 VARCHAR(255)             DEFAULT NULL COMMENT '给用户看的说明或给运营看的理由',
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者；0 表示系统发分',
  `updated_by`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`               VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`             TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_behavior_ref` (`user_id`, `behavior_code`, `source_ref`),
  KEY `idx_user_business` (`user_id`, `business_date`),
  KEY `idx_behavior_business` (`behavior_code`, `business_date`),
  KEY `idx_user_id` (`user_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分流水（带变动后余额）';

-- 行为分值表：ADR-0038 第四节那张表，进库 + 运营可调（ADR-0010 的分层）。
-- **只能改分值 / 频次 / 启停，不能新增**：行为码要有代码去触发它，加一行数据只产生一个
-- 永远不会发生的动作（ADR-0046 的决定）。
CREATE TABLE `point_behavior` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`                   VARCHAR(32)     NOT NULL COMMENT '行为码；**只能改分值，不能新增**（新增行为是代码变更）',
  `name`                   VARCHAR(64)     NOT NULL,
  `points`                 INT             NOT NULL DEFAULT 0 COMMENT '单次分值；0 表示只记行为不发分',
  `counts_toward_daily_cap` TINYINT        NOT NULL DEFAULT 0 COMMENT '1 占每日上限 / 0 不占',
  `daily_count_limit`      INT                      DEFAULT NULL COMMENT '每日次数上限；NULL 表示不限次',
  `monthly_count_limit`    INT                      DEFAULT NULL COMMENT '每月次数上限；NULL 表示不限次',
  `once_only`              TINYINT         NOT NULL DEFAULT 0 COMMENT '1 一次性（如完善档案）',
  `status`                 TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `sort_order`             INT             NOT NULL DEFAULT 0,
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`               VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`             TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分行为分值表（分值可调，行为不可新增）';

-- 任务清单：每日 / 每周两档（ADR-0038 第四节），入库存配置。
-- 任务引用已有的行为码，进度按行为流水聚合——**任务本身不额外发分**。
CREATE TABLE `point_task` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`          VARCHAR(32)     NOT NULL COMMENT '任务编码（如 DAILY_CHECK_IN）；创建后不可改',
  `name`          VARCHAR(64)     NOT NULL,
  `period`        TINYINT         NOT NULL COMMENT '1 每日 / 2 每周',
  `behavior_code` VARCHAR(32)     NOT NULL COMMENT '该任务统计的行为（逻辑引用 point_behavior.code）',
  `target_count`  INT             NOT NULL DEFAULT 1 COMMENT '达标所需次数',
  `sort_order`    INT             NOT NULL DEFAULT 0,
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_period_behavior` (`period`, `behavior_code`),
  KEY `idx_period_sort` (`period`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分任务清单（每日 + 每周；清单入库存配置）';

-- 兑换档位：**只兑平台补贴券**（`coupon_template_id` 必须指向 cost_bearer=2 的模板，
-- 应用层校验；数据库不写跨表约束——跨模块引用不建物理外键是本项目的通则）。
CREATE TABLE `point_exchange_option` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`               VARCHAR(64)     NOT NULL COMMENT '档位名，如「30 元体检券」',
  `points_cost`        INT             NOT NULL COMMENT '消耗积分',
  `coupon_template_id` BIGINT UNSIGNED NOT NULL COMMENT '兑出的券模板（**必须是平台补贴券**）',
  `sort_order`         INT             NOT NULL DEFAULT 0,
  `status`             TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分兑换档位（只兑平台补贴券）';

-- 月度阶梯档位（F018 骨架）：上月累计积分 ≥ 门槛 → 发券。
-- **种子里一行都没有**：门槛与奖励没有规则依据，不编。
CREATE TABLE `point_ladder_tier` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `threshold_points`   INT             NOT NULL COMMENT '门槛：上月累计获得积分不低于它',
  `coupon_template_id` BIGINT UNSIGNED NOT NULL COMMENT '发什么券（**必须是平台补贴券**：成本归平台）',
  `coupon_count`       INT             NOT NULL DEFAULT 1 COMMENT '发几张',
  `sort_order`         INT             NOT NULL DEFAULT 0,
  `status`             TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_threshold` (`threshold_points`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '月度阶梯档位（F018 骨架：上月累计 → 阶梯发券）';

-- 阶梯发放记录：`(user_id, period)` 唯一 → **一个账期每人只发一次**。
-- 批算重跑、多实例并发都命在这条唯一键上（骨架也要幂等，否则第一次补跑就发重了）。
CREATE TABLE `point_ladder_grant` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT UNSIGNED NOT NULL,
  `period`            CHAR(7)         NOT NULL COMMENT '账期 yyyy-MM（**上月**）',
  `tier_id`           BIGINT UNSIGNED NOT NULL COMMENT '命中的档位（逻辑引用 point_ladder_tier.id）',
  `threshold_points`  INT             NOT NULL COMMENT '该档门槛（存档：档位后来改了不影响这次记录）',
  `cumulative_points` INT             NOT NULL COMMENT '算出的上月累计积分',
  `coupon_id`         BIGINT UNSIGNED NOT NULL COMMENT '发出的券（逻辑引用 coupon.id）',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示批算发出',
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_period` (`user_id`, `period`),
  KEY `idx_period_tier` (`period`, `tier_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '月度阶梯发放记录（一个账期每人一次）';

-- 积分规则设置：单行表（id 恒为 1）。
-- 为什么单独一张而不是写进代码常量：每日上限是**业务可调项**（ADR-0010 的三层配置），
-- 运营要在后台改得动（调活动、压刷量），改它不该发版。
CREATE TABLE `point_config` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `daily_earn_limit` INT             NOT NULL DEFAULT 20 COMMENT '每日获取上限（邀请与一次性项不占此上限）',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`         VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分规则设置（单行：每日获取上限）';

INSERT INTO `point_config` (`id`, `daily_earn_limit`) VALUES (1, 20);

-- 行为分值表：ADR-0038 第四节那张表，逐行对上。
-- AI_ADVICE / SHARE 是任务清单里的展示项（ADR-0038 第四节把它们列为每日任务），
-- 但**不在发分表里**——所以分值为 0、只记行为：任务中心要显示「查看 AI 建议」的进度，
-- 而这条行为不该发分（发了就是替甲方加规则）。
INSERT INTO `point_behavior`
  (`code`, `name`, `points`, `counts_toward_daily_cap`, `daily_count_limit`, `monthly_count_limit`, `once_only`, `sort_order`) VALUES
  ('SIGN_IN',          '每日签到',     1,  1, 1,    NULL, 0, 1),
  ('CHECK_IN',         '打卡',         3,  1, 1,    NULL, 0, 2),
  ('INVITE',           '邀请有效注册', 20, 0, NULL, NULL, 0, 3),
  ('REVIEW',           '评价晒单',     5,  1, NULL, 5,    0, 4),
  ('PROFILE_COMPLETE', '完善档案',     10, 0, 1,    NULL, 1, 5),
  ('AI_ADVICE',        '查看 AI 建议', 0,  0, 1,    NULL, 0, 6),
  ('SHARE',            '分享',         0,  0, 1,    NULL, 0, 7);

-- 任务清单：每日四条（签到 / 打卡 / 查看 AI 建议 / 分享）+ 每周两条（5 次打卡 / 邀请 1 人）。
INSERT INTO `point_task` (`code`, `name`, `period`, `behavior_code`, `target_count`, `sort_order`) VALUES
  ('DAILY_SIGN_IN',    '每日签到',     1, 'SIGN_IN',   1, 1),
  ('DAILY_CHECK_IN',   '每日打卡',     1, 'CHECK_IN',  1, 2),
  ('DAILY_AI_ADVICE',  '查看 AI 建议', 1, 'AI_ADVICE', 1, 3),
  ('DAILY_SHARE',      '分享给好友',   1, 'SHARE',     1, 4),
  ('WEEKLY_CHECK_IN',  '完成 5 次打卡', 2, 'CHECK_IN', 5, 1),
  ('WEEKLY_INVITE',    '邀请 1 位好友', 2, 'INVITE',   1, 2);
