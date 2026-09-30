-- 专项照护模式（切片 #116，决策见 ADR-0024 第二节与 ADR-0032）
--
-- 三件事，都在这一条迁移里：
--
-- 1) `care_mode_rule`：**年龄阈值入库**（ADR-0010：业务可调项入库 + 运营后台，不写死在代码里）。
--    ADR-0024 记过阈值仍是占位（犬 7 / 猫 10 来自 63 号调研，交付文档 F009 写的是「7 岁以上」），
--    要兽医定稿。做成一行数据之后，定稿变成一次运营改配置，不是一次发版。
--    首版三行都取 F009 的字面值 7——**不按物种分离**是刻意的：调研的「猫 10」还没被兽医确认，
--    现在按它上线等于用工程判断替代临床判断（ADR-0032 待澄清第 1 条）。
--
-- 2) `pet.care_mode_disabled`：**唯一落库的一位**。照护模式是派生事实（生日实时算 + 慢病标记），
--    不落状态位（ADR-0024 第二节）；但「用户明确关掉了」既不能从生日推导也不能从慢病推导，
--    所以用户意愿落这一枚位，重新打开 = 清除它（ADR-0032 决定一）。
--
-- 3) `reminder_rule` 的**照护档阈值**：照护宠物的提醒提前量收紧，走配置而不是代码里的新数字
--    （ADR-0032 决定三）。**缺键回落默认值，且默认值等于今天的阈值**——非照护宠物的行为不变。

CREATE TABLE `care_mode_rule` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `species`       TINYINT         NOT NULL COMMENT '0通用1犬2猫；按物种给阈值，0 是兜底',
  `min_age_years` TINYINT         NOT NULL COMMENT '达到该实足年龄即开启专项照护（生日实时算）',
  `enabled`       TINYINT         NOT NULL DEFAULT 1 COMMENT '平台级开关：关掉则该物种不自动开启（慢病仍可触发）',
  `remark`        VARCHAR(256)             DEFAULT NULL COMMENT '给运营看的说明',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_species` (`species`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '专项照护模式的年龄阈值（业务可调项，ADR-0010）';

-- 首版取交付文档 F009 的字面「7 岁以上」；犬猫同值，待兽医定稿后只改这一行
INSERT INTO `care_mode_rule` (`species`, `min_age_years`, `enabled`, `remark`) VALUES
  (0, 7, 1, '通用兜底：7 岁以上开启专项照护（交付文档 F009 的字面值，待兽医定稿）'),
  (1, 7, 1, '犬：7 岁以上（63 号调研的占位，待兽医定稿）'),
  (2, 7, 1, '猫：7 岁以上（调研占位是 10 岁，未采纳——兽医未确认，见 ADR-0032 待澄清）');

ALTER TABLE `pet`
  ADD COLUMN `care_mode_disabled` TINYINT NOT NULL DEFAULT 0
      COMMENT '1=用户手动关闭专项照护（派生事实不落位，这一枚是用户意愿）'
      AFTER `chronic_desc`;

-- 照护档的提醒阈值：**只加键，不改既有键**。
--   careAdvanceDays / careOverdueGraceDays  疫苗(1)与驱虫(2)：老年宠免疫应答弱、预约周期长，提前一个月提醒；
--                                            过期后宽限 60 天（默认 30 天）
--   careWeightChangePercent                 趋势(5)：老年宠体重下降更值得早提醒（默认 5% → 照护档 3%）
-- 阈值本身的取值是**工程占位**（交付文档只要求「照护模式提醒更密」，没给数字），
-- 与年龄阈值一样待兽医确认；改数据即可生效，不需要发版。
-- type=6（慢病/老年）不另立照护键：那条规则**只对处于照护模式的宠物生成**（ADR-0019 已实现），
-- 它的 intervalMonths 本身就已经是照护档。
UPDATE `reminder_rule`
   SET `config` = JSON_SET(`config`, '$.careAdvanceDays', 30, '$.careOverdueGraceDays', 60)
 WHERE `type` IN (1, 2);

UPDATE `reminder_rule`
   SET `config` = JSON_SET(`config`, '$.careWeightChangePercent', 3)
 WHERE `type` = 5;
