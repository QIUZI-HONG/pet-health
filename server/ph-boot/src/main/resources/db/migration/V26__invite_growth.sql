-- 邀请与增长（切片 #111）——决策见 ADR-0039 第一节与 ADR-0046
--
-- 五张表：邀请码、邀请关系、阶梯档位、阶梯达成、反作弊记录。
--
-- 四条刻意的设计：
--   1. **归因只在注册那一刻**（`invite_relation.attributed_at` = 注册时间）：
--      链接只做预填，以用户填的码为准，**不做事后补填**——补填是刷券的入口（ADR-0039）。
--      `invitee_user_id` 唯一：一个被邀请人一辈子只被归因一次。
--   2. **有效邀请 = 被邀请人完成建档 + 24 小时内有行为**（ADR-0039 的口径 +
--      第二层反作弊的「24 小时内无行为不发券」）：所以关系有「待生效」这个中间态，
--      等结算批算给出结论。**阶梯计数与积分都按有效邀请数算，不按注册数**。
--   3. **达成记录与奖励物分开**：`invite_ladder_achievement` 只记「谁在哪一档达成」，
--      奖励物（发券 / 授权益）在 `invite_ladder_tier` 里配。没配奖励的档位照样记录达成——
--      「谁在哪一档」是数据，「发什么」是配置，混在一起会让改配置时丢掉历史。
--      `(user_id, threshold)` 唯一：**阶梯奖只在达到门槛时发一次**（ADR-0038 第四节）。
--   4. **反作弊的判据是数据不是日志**（`invite_risk_record.rule`）：
--      运营要能按判据查出「是哪一类刷量」，而不是在一堆日志里翻。
--      三条判据在归因时判（自邀自 / 同设备 / 同 IP + 同号段），
--      第四条（24 小时无行为）由结算批算判。
--
-- 反作弊需要的「同设备 / 同号段」判据从哪里来：`invite_code` 存**创建邀请码时**的设备与
-- IP、以及创建者手机号的前 7 位（号段）。不存完整手机号——它属于身份信息（ADR-0013），
-- 而「同号段」这个判断只需要前 7 位。注册侧的 device_id / ip 由注册流程随命令传进来
-- （接线点见 ADR-0046 的「需要协调」：本切片不改 ph-account）。

CREATE TABLE `invite_code` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`               VARCHAR(16)     NOT NULL COMMENT '邀请码（用户可读、可手抄；大小写不敏感的字母数字组合）',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '码的主人（逻辑引用 user.id）',
  `channel`            TINYINT         NOT NULL DEFAULT 1 COMMENT '首次生成时的入口：1 分享链接 / 2 注册表单手工填（此处只做记录）',
  `owner_phone_segment` VARCHAR(16)            DEFAULT NULL COMMENT '码主人手机号前 7 位（反作弊「同号段」判据；**不存完整号码**）',
  `owner_device_id`    VARCHAR(64)              DEFAULT NULL COMMENT '生成邀请码时的设备标识（反作弊「同设备」判据）',
  `owner_ip`           VARCHAR(64)              DEFAULT NULL COMMENT '生成邀请码时的来源 IP（反作弊「同 IP」判据）',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请码（一人一码）';

-- 一条邀请关系 = 一次归因 + 一次结算。
--
-- `status`：1 待生效（已注册，或已建档但观察窗未满） / 2 有效 / 3 无效。
-- `reject_reason` 只在无效时有值：SELF_INVITE / SAME_DEVICE / SAME_IP_SEGMENT / NO_ACTIVITY_24H。
--
-- 为什么把「待生效」显式建出来而不是直接判有效：有效邀请要等 24 小时观察窗，
-- 期间它既不是有效也不是无效。若注册即算有效，被邀请人同一批刷出来的号当晚就把券领走了。
CREATE TABLE `invite_relation` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inviter_user_id`     BIGINT UNSIGNED NOT NULL COMMENT '邀请人（逻辑引用 user.id）',
  `invitee_user_id`     BIGINT UNSIGNED NOT NULL COMMENT '被邀请人（逻辑引用 user.id）',
  `invite_code`         VARCHAR(16)     NOT NULL COMMENT '注册时填的那个码（链接预填也算填）',
  `channel`             TINYINT         NOT NULL COMMENT '1 分享链接 / 2 注册表单手工填',
  `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '1待生效2有效3无效',
  `reject_reason`       VARCHAR(32)              DEFAULT NULL COMMENT 'SELF_INVITE / SAME_DEVICE / SAME_IP_SEGMENT / NO_ACTIVITY_24H',
  `attributed_at`       DATETIME        NOT NULL COMMENT '归因时间（= 注册那一刻；之后不再改）',
  `profile_completed_at` DATETIME                DEFAULT NULL COMMENT '被邀请人完成建档的时间',
  `settled_at`          DATETIME                 DEFAULT NULL COMMENT '结算时间（判有效 / 无效的时刻）',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`            VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`          TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitee` (`invitee_user_id`),
  KEY `idx_inviter_status` (`inviter_user_id`, `status`),
  KEY `idx_status_attributed` (`status`, `attributed_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请关系（归因只在注册那一刻；有效邀请 = 完成建档 + 24 小时内有行为）';

-- 邀请阶梯档位：门槛固定五档（1 / 3 / 5 / 10 / 15，ADR-0039），**运营能改的是每档发什么**。
--
-- `reward_type` 为空 = 这一档还没配奖励：**照样记录达成，只是不发东西**。
-- 种子里刻意不配任何奖励（发什么券、给哪个权益码，ADR 没定），
-- 编一个「满 3 人送 20 元券」就是替甲方做产品决策。
CREATE TABLE `invite_ladder_tier` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `threshold`         INT             NOT NULL COMMENT '门槛：有效邀请数（1 / 3 / 5 / 10 / 15）',
  `reward_type`       TINYINT                  DEFAULT NULL COMMENT '1 发券 / 2 授权益（永久）；NULL 表示未配置奖励',
  `coupon_template_id` BIGINT UNSIGNED         DEFAULT NULL COMMENT 'reward_type=1 时的券模板（逻辑引用 coupon_template.id）',
  `rights_code`       VARCHAR(64)              DEFAULT NULL COMMENT 'reward_type=2 时的权益码（逻辑引用 rights_code.code）',
  `reward_count`      INT             NOT NULL DEFAULT 1 COMMENT '发券张数（reward_type=1 时有效）',
  `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_threshold` (`threshold`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请阶梯档位（门槛固定五档，奖励物可配）';

-- 阶梯达成记录：`(user_id, threshold)` 唯一 → **阶梯奖只在达到门槛时发一次**（ADR-0038 第四节）。
-- 发放结果一并记下来（发了哪张券 / 哪条授予），便于「这个人的券是哪来的」一句话答上来。
CREATE TABLE `invite_ladder_achievement` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '达成人（邀请人）',
  `threshold`      INT             NOT NULL COMMENT '达成的门槛',
  `effective_count` INT            NOT NULL COMMENT '达成时的有效邀请数',
  `reward_type`    TINYINT                  DEFAULT NULL COMMENT '当时的奖励类型（存档，配置后来改了也不影响这次记录）',
  `coupon_id`      BIGINT UNSIGNED          DEFAULT NULL COMMENT '发出的券（reward_type=1 时）；未配奖励为 NULL',
  `rights_grant_id` BIGINT UNSIGNED         DEFAULT NULL COMMENT '授予的权益记录（reward_type=2 时）；未配奖励为 NULL',
  `achieved_at`    DATETIME        NOT NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_threshold` (`user_id`, `threshold`),
  KEY `idx_threshold` (`threshold`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请阶梯达成记录（每档只发一次）';

-- 反作弊拦截记录（三条判据命中即记一条）。
--
-- 为什么单独一张表而不是打日志：被拦下的邀请要**看得见、查得到、按判据聚合**——
-- 「今天 SAME_DEVICE 拦了多少」是判断刷量规模的第一步，日志里翻不出来。
-- 记录里带 device_id / ip 是刻意的：排查时要用它串出同一批账号。
CREATE TABLE `invite_risk_record` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `rule`              VARCHAR(32)     NOT NULL COMMENT 'SELF_INVITE / SAME_DEVICE / SAME_IP_SEGMENT / NO_ACTIVITY_24H',
  `inviter_user_id`   BIGINT UNSIGNED NOT NULL,
  `invitee_user_id`   BIGINT UNSIGNED          DEFAULT NULL COMMENT '注册未完成时可能为空',
  `device_id`         VARCHAR(64)              DEFAULT NULL,
  `ip`                VARCHAR(64)              DEFAULT NULL,
  `detail`            VARCHAR(255)             DEFAULT NULL COMMENT '命中的原始判据（给运营看的一句话）',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者；0 表示系统判定',
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_rule` (`rule`, `id`),
  KEY `idx_inviter` (`inviter_user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请反作弊拦截记录（判据是数据，不是日志）';

-- 五档门槛（ADR-0039 第一节）。奖励物留空：等运营在后台配，配了才发。
INSERT INTO `invite_ladder_tier` (`threshold`, `reward_type`, `reward_count`, `status`) VALUES
  (1,  NULL, 1, 1),
  (3,  NULL, 1, 1),
  (5,  NULL, 1, 1),
  (10, NULL, 1, 1),
  (15, NULL, 1, 1);
