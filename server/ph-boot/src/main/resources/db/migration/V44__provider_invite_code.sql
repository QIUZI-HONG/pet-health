-- 门店推广码与拉新归属——考核「拉新」那一项的取数来源（F022 / ADR-0039 第三节）。
--
-- 迁移前的状态：邀请关系只有**用户对用户**（`invite_relation.inviter_user_id` 是账号 id），
-- 而考核要的是「这家门店拉来几个人」——交付文档 F022 写的是「店内铺设二维码，用户扫码绑定门店
-- 为拉新推荐人」。门店侧没有任何入口，也没有地方存「这个用户是哪家店拉来的」，
-- 于是考核的拉新项在 ph-privilege 里无处取数（ADR-0052 的「需要协调」第 1 条记的就是这条）。
--
-- 本迁移补上两样：
--   1. `provider_invite_code`：**一店一码**，码形如 `PV` + 8 位（与用户邀请码的 8 位不同长，
--      所以两张表之间的查找不会互相撞）。用户可以扫这个码注册，归因到门店；
--   2. `invite_relation` 加一列 `inviter_provider_id`、并把 `inviter_user_id` 放开为可空——
--      一条关系要么来自用户邀请码（有 inviter_user_id），要么来自门店推广码（有 inviter_provider_id），
--      **两者互斥**，用 CHECK 钉住。
--
-- 为什么是「互斥」而不是「两个都能有」：门店推广码背后**没有人**。让 inviter_user_id 也填上
-- （比如填门店管理员）会把「门店拉新」算成「这个人的邀请战绩」，顺带把用户的 1/3/5/10/15 阶梯
-- 也一起推进——那是两件不同的事，混起来之后两边的数都不再可信。
--
-- 不建物理外键（ADR-0006 / ADR-0011），`provider_id` 是逻辑引用。

CREATE TABLE `provider_invite_code` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '归属门店（逻辑引用 provider.id）',
  `code`        VARCHAR(16)     NOT NULL COMMENT '推广码：PV + 8 位（与用户邀请码的 8 位不同长，两张表不会互撞）',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider` (`provider_id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '门店推广码（一店一码，扫它注册的用户归因到门店）';

-- 归因归属加一列。`inviter_user_id` 放开为可空是**这条改动的代价**：
-- 原来它 NOT NULL 的前提是「邀请人一定是用户」，门店推广码打破了这个前提。
-- 既有数据不受影响（都是用户邀请码归因，inviter_user_id 都有值），CHECK 也成立。
ALTER TABLE `invite_relation`
  MODIFY COLUMN `inviter_user_id` BIGINT UNSIGNED NULL COMMENT '邀请人（逻辑引用 user.id）；门店推广码归因时为 NULL',
  ADD COLUMN `inviter_provider_id` BIGINT UNSIGNED NULL COMMENT '拉新归属门店（逻辑引用 provider.id）；用户邀请码归因时为 NULL',
  ADD KEY `idx_inviter_provider_status` (`inviter_provider_id`, `status`),
  ADD CONSTRAINT `ck_invite_relation_inviter`
    CHECK ((`inviter_user_id` IS NOT NULL) <> (`inviter_provider_id` IS NOT NULL));
