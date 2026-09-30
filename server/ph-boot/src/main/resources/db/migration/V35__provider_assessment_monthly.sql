-- 服务者考核（F022）的**结果**：月度分表、分数明细、单项分覆盖留痕（切片 #58 / #114）
--
-- 决策：ADR-0039 第三节（每月 1 日算上月，**落月度分表并保留历史、跨月可对比**；允许超级管理员
-- 覆盖单项分但必须留痕：谁、何时、理由、覆盖前后值都留）、ADR-0050 第四节（缺项两种口径，
-- 明细里要注明「未参与」）。本表的实现取舍写在 ADR-0052。
--
-- 四条刻意的设计：
--
--   1. **权重与达标线快照进分表**（`invite_weight` / `invite_score` … 与明细里的 `target_value`）：
--      规则是可改的（V34），而历史账期必须能解释「当时为什么是这个分」。不存快照的话，
--      运营一改阈值，去年十二月的分就再也算不出来了——「跨月可对比」也就没了依据。
--   2. **`(provider_id, period)` 唯一**：这就是月度批算的幂等键。批算重跑、多实例并发、
--      运营手动补跑，都只会有一条（先插到的为准，撞唯一键的那次忽略）。**不做「先查后插 + 加锁」**：
--      唯一键是数据库能提供的最强保证，而应用层的先查后插在并发下会双双通过（ADR-0044 的教训）。
--   3. **明细带「是否参与」与「未参与的原因」**（`participated` / `note`）：ADR-0050 第四节点名
--      要求明细里注明「未参与」。未参与的项**也落一行**（score 为 NULL）——只落参与项的话，
--      「这个过程分为什么是 100」在数据上就看不出来（过程子项被剔除过）。
--   4. **覆盖不覆盖算法原值**：`assessment_item_score.calculated_score` 是算法算出来的原值，
--      `score` 是当前生效值。覆盖只改 `score` 与 `overridden`，并往
--      `assessment_override_log` 追加一条（before / after 都是**当时的生效值**）——append-only，
--      覆盖多少次就有多少条，撤销的方式是再覆盖回去（原值一直在，见 ADR-0052）。

-- 一个月一个服务者一条。三个主项各两列（得分 / 是否参与）+ 总分 + 等级与优先级 + 覆盖标记。
--
-- 三个主项的 `*_score` 为 NULL 与 `*_participated = 0` 是同一件事的两种表达，
-- 冗余保留是为了让「按分数排序 / 求均值」这类查询不用处理 NULL（0 与未参与在这里不混：
-- **未参与的项不写 0，写 NULL**——写 0 会被 SUM/AVG 当成「这项得了 0 分」）。
CREATE TABLE `assessment_monthly_score` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`         BIGINT UNSIGNED NOT NULL COMMENT '服务者（逻辑引用 provider.id）',
  `period`              CHAR(7)         NOT NULL COMMENT '考核账期（yyyy-MM）。每月 1 日算**上月**（ADR-0039 第三节）',
  `invite_weight`       INT             NOT NULL COMMENT '拉新项权重快照',
  `invite_score`        DECIMAL(5,2)             DEFAULT NULL COMMENT '拉新项得分（0.00–100.00）；未参与时为 NULL',
  `invite_participated` TINYINT         NOT NULL DEFAULT 0 COMMENT '拉新项是否参与（0 = 平台侧无该维度要求，ADR-0050 第四节）',
  `coupon_weight`       INT             NOT NULL COMMENT '券项权重快照',
  `coupon_score`        DECIMAL(5,2)             DEFAULT NULL,
  `coupon_participated` TINYINT         NOT NULL DEFAULT 0,
  `process_weight`      INT             NOT NULL COMMENT '过程项权重快照',
  `process_score`       DECIMAL(5,2)             DEFAULT NULL,
  `process_participated` TINYINT        NOT NULL DEFAULT 0,
  `participated_weight` INT             NOT NULL DEFAULT 0 COMMENT '参与项权重合计（100 = 三项全参与；缺项后小于 100，总分按它归一）',
  `total_score`         DECIMAL(5,2)    NOT NULL DEFAULT 0.00 COMMENT '总分（按参与项权重归一后的加权平均；无参与项时为 0.00）',
  `level`               TINYINT         NOT NULL DEFAULT 1 COMMENT '等级：1基础2优选3战略合作（按 assessment_level_rule 分档）',
  `level_name`          VARCHAR(16)     NOT NULL DEFAULT '基础' COMMENT '等级中文名快照',
  `recommend_priority`  TINYINT         NOT NULL DEFAULT 3 COMMENT 'AI 推荐优先级（当时档位配置的快照）',
  `overridden`          TINYINT         NOT NULL DEFAULT 0 COMMENT '本期是否发生过单项分覆盖',
  `calculated_at`       DATETIME        NOT NULL COMMENT '本期的计算时刻（服务端时间，用 AppTime）',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示系统写入（定时批算）',
  `updated_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`            VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`          TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_period` (`provider_id`, `period`),
  KEY `idx_period_level` (`period`, `level`),
  KEY `idx_period_score` (`period`, `total_score`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '月度考核分（一个服务者一个账期一条；唯一键即幂等键）';

-- 分数明细：三项主项 + 过程分的五个子项。
--
-- `item_code` 的取值（与契约的 `AssessmentItemView.item_code` 同一套）：
--   INVITE 拉新 / COUPON 券 / PROCESS 过程（三项主项，`parent_code` 为空）；
--   PROCESS_RESPONSE 接单响应 / PROCESS_REDEEM_RATE 核销率 / PROCESS_REPORT_RATE 报工完整率 /
--   PROCESS_REVIEW 评价分 / PROCESS_CANCEL_RATE 服务者取消率（`parent_code` = PROCESS）。
--
-- 过程子项之间**等权**（ADR-0039 第二节只说「评价分等权进过程分」，过程分的分子项同样等权，
-- 这是本切片的收口；权重不进明细的 `weight` 列——那一列只给三项主项）。
CREATE TABLE `assessment_item_score` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `score_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属月度分（逻辑引用 assessment_monthly_score.id）',
  `provider_id`      BIGINT UNSIGNED NOT NULL COMMENT '服务者（冗余一列：服务者侧按它取明细，免得先查分表）',
  `period`           CHAR(7)         NOT NULL,
  `item_code`        VARCHAR(32)     NOT NULL COMMENT 'INVITE / COUPON / PROCESS / PROCESS_RESPONSE / PROCESS_REDEEM_RATE / PROCESS_REPORT_RATE / PROCESS_REVIEW / PROCESS_CANCEL_RATE',
  `item_name`        VARCHAR(32)     NOT NULL COMMENT '中文名快照',
  `parent_code`      VARCHAR(32)              DEFAULT NULL COMMENT '父项：过程子项为 PROCESS；三项主项为空',
  `weight`           INT                      DEFAULT NULL COMMENT '主项权重（百分比）；过程子项为空（子项等权）',
  `participated`     TINYINT         NOT NULL DEFAULT 1 COMMENT '是否参与计分；0 = 平台侧无该维度要求（ADR-0050 第四节）',
  `score`            DECIMAL(5,2)             DEFAULT NULL COMMENT '当前生效得分（被覆盖后是覆盖值）；未参与时为 NULL',
  `calculated_score` DECIMAL(5,2)             DEFAULT NULL COMMENT '算法算出来的原始得分（覆盖不改它，它是「还原」与申诉的依据）',
  `raw_value`        VARCHAR(64)              DEFAULT NULL COMMENT '原始指标值（人话，如「有效邀请 3 人」「完成率 0.60 × 核销 8 张」）',
  `target_value`     VARCHAR(32)              DEFAULT NULL COMMENT '达标线快照（规则改了不影响历史账期）',
  `data_source`      VARCHAR(64)     NOT NULL COMMENT '数据来源（如 ph-order 订单统计）；未参与时写清是哪条事实缺了',
  `note`             VARCHAR(255)             DEFAULT NULL COMMENT '说明：未参与的原因 / 覆盖说明 / 降级说明',
  `overridden`       TINYINT         NOT NULL DEFAULT 0 COMMENT '该项是否被超级管理员覆盖过',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`         VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_score_item` (`score_id`, `item_code`),
  KEY `idx_provider_period` (`provider_id`, `period`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考核分数明细（三项主项 + 过程子项，未参与的也落行）';

-- 单项分覆盖留痕：**append-only**，每次覆盖追加一条（不 UPDATE 旧行）。
--
-- 为什么 `before_score` 允许 NULL：覆盖一个「未参与」的项时，覆盖前没有值（NULL ≠ 0），
-- 而这两者在申诉语境里必须区分开（「原本没参与」与「原本是 0 分」是两种事实）。
--
-- 为什么不加「撤销」状态列：撤销就是再覆盖一次（`calculated_score` 一直在，
-- 覆盖回原值即可），留痕表里两次都留着——审计要的是完整序列，不是最终状态。
CREATE TABLE `assessment_override_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `score_id`     BIGINT UNSIGNED NOT NULL COMMENT '所属月度分（逻辑引用 assessment_monthly_score.id）',
  `provider_id`  BIGINT UNSIGNED NOT NULL,
  `period`       CHAR(7)         NOT NULL,
  `item_code`    VARCHAR(32)     NOT NULL COMMENT '被覆盖的主项：INVITE / COUPON / PROCESS（过程子项不可覆盖）',
  `item_name`    VARCHAR(32)     NOT NULL COMMENT '中文名快照',
  `before_score` DECIMAL(5,2)             DEFAULT NULL COMMENT '覆盖前的**生效值**；覆盖前未参与时为 NULL',
  `after_score`  DECIMAL(5,2)    NOT NULL COMMENT '覆盖后的生效值',
  `reason`       VARCHAR(255)    NOT NULL COMMENT '覆盖理由（必填；会随明细下发给服务者——否则是黑箱）',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者的账号 id（超级管理员）',
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_score` (`score_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '单项分覆盖留痕（append-only：谁、何时、改成多少、为什么）';
