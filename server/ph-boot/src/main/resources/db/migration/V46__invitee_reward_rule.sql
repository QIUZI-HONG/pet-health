-- 被邀请人得券：BPM-2「双方各得 20 元洗护券」里**被邀请人**那一半（F016）
--
-- 背景：邀请人一侧的券走 `invite_ladder_tier`（V39 给五档都配了 1 张洗护券），而被邀请人一侧
-- 此前**只有积分机制**（`point_behavior.INVITE_INVITEE`，V39 给 20 分）——交付文档要的
-- 「双方各得券」少了一半，而且是**代码缺口不是配置缺口**（V38 当年的注释就写了：
-- 「券形态若将来要做，再单开一列/一表」）。
--
-- 为什么单开一张单行表，而不是给 `point_behavior` 加列：
--   1. 这张表描述的是「被邀请人这一次注册得什么券」，与积分的频次 / 上限 / 日上限那套配置
--      没有共同字段；塞进 `point_behavior` 会让另外七个行为码各背两个用不上的列；
--   2. `check_in_reward_rule`（V41）已经是同一形态的先例（规则表 → 券模板），照它抄最省事。
--
-- 与积分的分工：**两条路径各自独立、都读配置**。券是交付文档口径，积分是「对等」口径
-- （邀请人拿 20 分 + 阶梯券，被邀请人拿 20 分 + 这张券）；关掉任何一条都不影响另一条。
--
-- 发放时机与积分一致：**观察窗结束、关系判有效那一刻**（不是注册即发——注册即发会让批量刷号
-- 当晚就领到奖励，而 ADR-0039 第三层的观察窗正是为拦住它设计的）。
-- 幂等靠券的 `(source, source_ref)`：引用是 `invitee-coupon:{关系 id}`（与阶梯奖同一个机制）。
--
-- 这一版**只有种子、没有运营页面**（与 `check_in_reward_rule` 同一处境，见 V41 的注释）：
--   · 换券 / 改张数：改这一行的 `coupon_template_id` / `coupon_count`
--   · 停发券：`status = 0`（只影响券这条路径；积分那条由 `point_behavior` 管）

CREATE TABLE `invitee_reward_rule` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coupon_template_id` BIGINT UNSIGNED NOT NULL COMMENT '被邀请人得什么券（逻辑引用 coupon_template.id）',
  `coupon_count`       INT             NOT NULL DEFAULT 1 COMMENT '发几张',
  `status`             TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用时只走积分那条路径）',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '被邀请人奖励（券）：BPM-2「双方各得券」的被邀请人那一半（F016）';

-- 单行种子：1 张「洗护券 20 元」（`CP-101`，V39 建的，面额与适用范围取自交付文档 BPM-2）。
-- 与 V41 同理，这条迁移**必须排在 V39 之后**：早跑会取到不存在的模板 id（那个子查询会给 NULL，
-- 而这一列是 NOT NULL —— 迁移当场失败，比留一条「配了但取不到券」的配置好）。
INSERT INTO `invitee_reward_rule` (`id`, `coupon_template_id`, `coupon_count`, `status`)
VALUES (1, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'), 1, 1);
