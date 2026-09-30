-- 服务者入驻与资质审核——切片 #104 的入驻部分，决策见 ADR-0035
--
-- 依据：交付文档 BPM-4（服务者申请入驻 → 平台审核资质 → 后台选品 → 填价 → 选券 → 上架）、
-- 7.2 的 `merchant`（文档用词）表、2.2 的权限矩阵（平台运营可审核 / 服务者管理员只能改自己）。
--
-- 与文档 DDL 的差异（逐条都有出处）：
--   1. 表名 `merchant`（文档用词）→ `provider`，字段 `merchant_id` → `provider_id`：**术语纪律**（CONTEXT.md），
--      「商家 / merchant」（文档用词）是禁用词，这不是格式偏好。
--   2. `phone` 明文字段不存在，改成 `phone_enc`（AES-256-GCM 密文）+ `phone_hash`（HMAC 查找列）——ADR-0013。
--      资质证件号同理（`provider_qualification.cert_no_enc` / `cert_no_hash`）。
--   3. 补审计列（created_by / updated_by / trace_id / is_deleted）——ADR-0011。
--   4. `status` 的取值比文档多一档：文档只有 0 待审 / 1 通过 / 2 拒绝，**冻结（3）是运营处置动作**，
--      与「拒绝」不是一回事（拒绝发生在入驻阶段，冻结发生在经营阶段，见 ADR-0035 的清退一节）。
--   5. `business_hours` 从 VARCHAR 改成 JSON：文档给的是 VARCHAR(128) 却写着「JSON 营业时段」，
--      两者自相矛盾；按注释的语义取 JSON（一天一行，含开放/结束时间）。
--
-- 审核流水单独一张 append-only 表（`provider_review_log`）：交付文档要求「留审核流水」，
-- 而申请单上的 `reviewed_at` / `reject_reason` 只保留**最后一次**结论——驳回后重提会把上一次
-- 结论覆盖掉，那正是最需要留痕的一次。目标类型用 TINYINT 区分「入驻申请 / 服务上架审核 /
-- 目录外服务提案」，这样一张表覆盖三类审核（都在服务者侧发起），不必各建一份。
--
-- 不建物理外键：模块内不加约束，模块间更不能加（ADR-0006 / ADR-0011）。

CREATE TABLE `provider` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`           VARCHAR(256)    NOT NULL COMMENT '服务者名称（门店/机构名）',
  `type`           TINYINT         NOT NULL COMMENT '1医院2洗护3训犬4寄养上门5食品用品6间接服务',
  `category`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1直接同业2直接异业3间接异业（交付文档 7.2，考核与区域保护用）',
  `logo`           VARCHAR(512)             DEFAULT NULL COMMENT 'Logo URL（走 ph-file 上传）',
  `intro`          VARCHAR(1024)            DEFAULT NULL COMMENT '门店简介',
  `address`        VARCHAR(512)    NOT NULL,
  `lng`            DECIMAL(10,7)            DEFAULT NULL COMMENT '经度（附近排序用，暂不参与查询）',
  `lat`            DECIMAL(10,7)            DEFAULT NULL,
  `phone_enc`      VARCHAR(255)    NOT NULL COMMENT '门店联系电话密文（ADR-0013）',
  `phone_hash`     CHAR(64)        NOT NULL COMMENT '联系电话 HMAC，等值查询用',
  `business_hours` JSON                     DEFAULT NULL COMMENT '营业时段：[{"day_of_week":1,"open_time":"09:00","close_time":"18:00"}]',
  `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审核1正常2驳回3冻结',
  `level`          TINYINT         NOT NULL DEFAULT 1 COMMENT '1基础2优选3战略合作（考核结果，本期不计算）',
  `region_code`    VARCHAR(32)              DEFAULT NULL COMMENT '区域编码（区域保护的支撑字段，规则未定，见 ADR-0035 待澄清）',
  `monthly_score`  DECIMAL(8,2)    NOT NULL DEFAULT 0 COMMENT '月度考核分（#58 落地前恒为 0）',
  `rating`         DECIMAL(3,1)    NOT NULL DEFAULT 5.0 COMMENT '评分（评价体系落地前恒为 5.0）',
  `approved_at`    DATETIME                 DEFAULT NULL COMMENT '审核通过时间',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_region` (`region_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者（门店/机构）';

CREATE TABLE `provider_qualification` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`   BIGINT UNSIGNED NOT NULL COMMENT '逻辑引用 provider.id',
  `type`          TINYINT         NOT NULL COMMENT '1营业执照2执业许可证3法人身份证4训犬师认证5健康证6其他',
  `name`          VARCHAR(128)    NOT NULL COMMENT '材料名称，如「动物诊疗许可证」',
  `cert_no_enc`   VARCHAR(255)             DEFAULT NULL COMMENT '证件号密文（ADR-0013），无编号的材料留空',
  `cert_no_hash`  CHAR(64)                 DEFAULT NULL COMMENT '证件号 HMAC：查重与将来的唯一约束都走它（密文没有保序性）',
  `file_url`      VARCHAR(512)             DEFAULT NULL COMMENT '材料图片 URL（证件用 PNG，见 docs/conventions.md）',
  `valid_from`    DATE                     DEFAULT NULL,
  `valid_until`   DATE                     DEFAULT NULL COMMENT '到期日——续期提醒（BPM-4 / 用户故事 82）的支撑字段',
  `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审1通过2驳回（跟随申请单的审核结论）',
  `review_remark` VARCHAR(255)             DEFAULT NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_provider` (`provider_id`),
  KEY `idx_cert_hash` (`cert_no_hash`),
  KEY `idx_valid_until` (`valid_until`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者资质材料';

CREATE TABLE `provider_onboarding_application` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`         BIGINT UNSIGNED NOT NULL COMMENT '逻辑引用 provider.id：申请即建一条 status=0 的服务者',
  `applicant_user_id`   BIGINT UNSIGNED NOT NULL COMMENT '申请人账号 id（服务者后台登录身份；后台账号体系未落地前与 user.id 同域，见 ADR-0035）',
  `applicant_name`      VARCHAR(64)     NOT NULL COMMENT '申请人姓名',
  `contact_phone_enc`   VARCHAR(255)    NOT NULL COMMENT '联系电话密文（ADR-0013）',
  `contact_phone_hash`  CHAR(64)        NOT NULL COMMENT '联系电话 HMAC',
  `status`              TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审核1通过2驳回',
  `reject_reason`       VARCHAR(255)             DEFAULT NULL COMMENT '驳回原因（展示给服务者，重提时清空）',
  `submit_count`        INT             NOT NULL DEFAULT 1 COMMENT '提交次数：驳回后每次重提 +1（申请单自身的重试次数）',
  `submitted_at`        DATETIME        NOT NULL COMMENT '最后一次提交时间',
  `reviewed_at`         DATETIME                 DEFAULT NULL,
  `reviewer_id`         BIGINT UNSIGNED          DEFAULT NULL COMMENT '审核人账号 id（平台运营）',
  `review_remark`       VARCHAR(255)             DEFAULT NULL COMMENT '审核备注（通过/驳回都可有，内部可见）',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`            VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`          TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_submitted` (`status`, `submitted_at`),
  KEY `idx_applicant` (`applicant_user_id`),
  KEY `idx_provider` (`provider_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者入驻申请单';

CREATE TABLE `provider_review_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `target_type`  TINYINT         NOT NULL COMMENT '1入驻申请2服务上架审核3目录外服务提案4服务者状态变更',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '对应申请单 / 服务项 / 提案 id',
  `provider_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '冗余的服务者 id，便于按服务者看审计线索',
  `action`       TINYINT         NOT NULL COMMENT '1提交2重提3通过4驳回5上架6下架7冻结8解冻（常量在 ProviderReviewLog）',
  `actor_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id',
  `actor_domain` VARCHAR(16)     NOT NULL DEFAULT '' COMMENT '操作者登录域：provider / admin',
  `remark`       VARCHAR(255)             DEFAULT NULL COMMENT '备注（驳回原因、审核意见）',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0 COMMENT '恒为 0：审核流水 append-only，能删就等于没有（ADR-0028 的同一取舍）',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`, `created_at`),
  KEY `idx_provider_time` (`provider_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '审核流水（append-only）';

CREATE TABLE `provider_user` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id` BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '服务者后台账号 id（逻辑引用；后台账号表尚未落地，见 ADR-0035 待协调）',
  `role`        TINYINT         NOT NULL DEFAULT 1 COMMENT '1管理员2技师（技师的角色范围由 #87 定）',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常0停用',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_user` (`provider_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者账号与角色绑定（审核通过时由申请人成为管理员）';
