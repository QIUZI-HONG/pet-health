-- 服务者联盟分类维度——一期验收标准的「服务者联盟分类：分类维度维护与归属」的落点。
--
-- 迁移前的状态：`provider.category` 是一个写死 1–3 的 TINYINT（见 V22），只在提交入驻申请时
-- 写一次，之后**任何一端都没有入口能改**（运营侧没有接口，服务者侧也没有）。分类维度因此
-- 不可维护：运营想加一档就得改代码。
--
-- 本迁移把它变成一张可维护的字典表，`provider.category` 继续存值、值域由本表定义
-- （**id 即取值**）。这样做的两个理由：
--   1. 既有的列、数据、契约都不用动——种子 id 1/2/3 与交付文档 7.2 的三档一一对应，
--      历史数据不需要搬迁；
--   2. 运营新增一档维度不再需要改代码或改表结构，插入一行即可。
--
-- 为什么不给「删除」接口：分类是被 `provider.category` 引用的维度，删掉会让历史归属悬空。
-- 停用（enabled = 0）是唯一的收敛手段，且**停用不会移动已有归属**——已归属的门店照旧显示
-- 该维度名，只是不能再被新指定。
--
-- 不建物理外键：模块内不加约束（ADR-0011 与 V22 的同一句）。

CREATE TABLE `provider_alliance_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类维度取值（provider.category 直接存它）',
  `code`        VARCHAR(32)     NOT NULL COMMENT '维度编码，稳定标识，供前端与报表引用',
  `name`        VARCHAR(64)     NOT NULL COMMENT '维度名称',
  `description` VARCHAR(255)            DEFAULT NULL COMMENT '一句话说明，运营后台展示',
  `sort_order`  INT             NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
  `enabled`     TINYINT         NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用（停用不影响既有归属）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_enabled_sort` (`enabled`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者联盟分类维度（provider.category 的值域）';

-- 种子 = 交付文档 7.2 写的三档。**id 显式指定**：它就是 `provider.category` 里既有的 1/2/3，
-- 不指定会由自增分配，历史数据的归属会整体错位。
INSERT INTO `provider_alliance_category` (`id`, `code`, `name`, `description`, `sort_order`) VALUES
  (1, 'DIRECT_PEER',    '直接同业', '宠物医疗、洗护、训犬等直接提供宠物服务的同业门店',   1),
  (2, 'DIRECT_CROSS',   '直接异业', '直接服务养宠人群但不提供宠物服务的门店',             2),
  (3, 'INDIRECT',       '间接异业', '宠物摄影、殡葬、保险、托运等间接服务方',             3);

-- 审核流水的 action 多一档「9 改联盟归属」（常量 ProviderReviewLog.ACTION_ASSIGN_ALLIANCE）。
-- 只改列注释、不改取值：`action` 是 TINYINT，没有枚举约束，注释是它唯一的说明书。
ALTER TABLE `provider_review_log`
  MODIFY COLUMN `action` TINYINT NOT NULL
  COMMENT '1提交2重提3通过4驳回5上架6下架7冻结8解冻9改联盟归属（常量在 ProviderReviewLog）';
