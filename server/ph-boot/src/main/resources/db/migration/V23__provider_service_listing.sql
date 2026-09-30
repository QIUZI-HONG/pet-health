-- 服务者选品定价与上架——切片 #105，决策见 ADR-0034
--
-- 对应交付文档 7.2 的 `merchant_service`（文档用词，「商家从标准目录勾选」）与 BPM-4 的
-- 「后台选品（勾选标准目录）→ 填价（区间内）→ 选券 → 上架」。
--
-- 与文档 DDL 的差异：
--   1. 表名 `provider_service`（术语纪律：禁用 merchant（文档用词），CONTEXT.md）。
--   2. `status` 从文档的「1上架 / 0下架」扩成五态：待审核 / 已上架 / 已下架 / 已驳回。
--      原因是交付文档 2.2 的权限矩阵写明「审核商家/服务」（文档用词）是平台运营的职责，而 BPM-4 又写
--      服务者自己上架——两者只有加上审核环节才同时成立（审核意见落在 reject_reason 上）。
--      精确取值见 ADR-0034，代码常量在 `ServiceListingStatus`。
--   3. 补审计列（ADR-0011）。
--
-- **不存服务项名称快照**：名称属于目录（`service_item.name`），平台改名后所有服务者页面上
-- 都该跟着变。跨模块取名字走 ph-catalog 的 api 包（ADR-0006 禁止 join 别人的表），
-- 每次列表按 code 批量取一次，不做冗余列——冗余列的第一天就与目录不一致。
--
-- `price` 越界由应用层拦（90001），数据库不写 CHECK 约束：区间会随运营调整，
-- 存量行不该因为区间收窄而变成「库里非法」，而 100% 校验是验收项（交付文档 2.5），
-- 落在拦截点比落在约束上更容易解释给前后端。

CREATE TABLE `provider_service` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`   BIGINT UNSIGNED NOT NULL COMMENT '逻辑引用 provider.id',
  `service_code`  VARCHAR(32)     NOT NULL COMMENT '标准目录项编码（逻辑引用 service_item.code）',
  `price`         DECIMAL(10,2)   NOT NULL COMMENT '服务者定价（元），必须落在目录项价格区间内',
  `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审核1已上架2已下架3已驳回',
  `reject_reason` VARCHAR(255)             DEFAULT NULL COMMENT '上架审核驳回原因',
  `submitted_at`  DATETIME                 DEFAULT NULL COMMENT '最后一次提交审核时间',
  `reviewed_at`   DATETIME                 DEFAULT NULL,
  `reviewer_id`   BIGINT UNSIGNED          DEFAULT NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_service` (`provider_id`, `service_code`),
  KEY `idx_status_submitted` (`status`, `submitted_at`),
  KEY `idx_provider_status` (`provider_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者上架的服务项（从标准目录勾选 + 定价）';

-- 目录外服务提案（交付文档 F012 / 2.5：「目录外服务须经平台审核」）
--
-- 为什么在 ph-provider 而不是 ph-catalog：提案是**服务者的申请动作**，与入驻申请、上架审核同属
-- 「服务者提交 → 平台审核」这一类；审核通过后由 ph-provider 调 ph-catalog 的接口
-- （`CatalogItemApi.createFromProposal`）建出正式目录项。这样模块依赖是单向的 provider → catalog，
-- 不会有环；反过来放 catalog 的话，catalog 要展示「谁提的」就得回头依赖 provider。
--
-- **服务者建议的区间只是参考**：审核通过时由运营给出最终区间与编码
-- （`service_item.price_min/max`、`code`），不能默认照抄——被审对象不该定义平台规则。
--
-- 审核流水记在 `provider_review_log`（target_type=3），本表只留最后一次结论。
CREATE TABLE `catalog_item_proposal` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`          BIGINT UNSIGNED NOT NULL COMMENT '提出申请的服务者（逻辑引用 provider.id）',
  `category_code`        VARCHAR(32)     NOT NULL COMMENT '希望归入的分类编码（逻辑引用 service_category.code）',
  `name`                 VARCHAR(128)    NOT NULL COMMENT '建议的项目名称',
  `description`          VARCHAR(512)             DEFAULT NULL COMMENT '服务内容说明（审核时的判断依据）',
  `suggested_price_min`  DECIMAL(10,2)   NOT NULL COMMENT '建议区间下限（参考值）',
  `suggested_price_max`  DECIMAL(10,2)   NOT NULL COMMENT '建议区间上限（参考值）',
  `suggested_price_unit` VARCHAR(16)     NOT NULL DEFAULT '次',
  `status`               TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审核1通过2驳回',
  `reject_reason`        VARCHAR(255)             DEFAULT NULL COMMENT '驳回原因（展示给服务者）',
  `item_code`            VARCHAR(32)              DEFAULT NULL COMMENT '通过后生成的正式项目编码，用于回溯这次提案产生了哪一项',
  `submitted_at`         DATETIME        NOT NULL,
  `reviewed_at`          DATETIME                 DEFAULT NULL,
  `reviewer_id`          BIGINT UNSIGNED          DEFAULT NULL COMMENT '审核人账号 id（平台运营）',
  `review_remark`        VARCHAR(255)             DEFAULT NULL,
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`             VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`           TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_submitted` (`status`, `submitted_at`),
  KEY `idx_provider` (`provider_id`),
  KEY `idx_category` (`category_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '目录外服务提案（F012：目录外服务须经平台审核）';
