-- 首批增长配置：让券与积分**开箱可用**（F016 / F017 / F018）
--
-- 为什么需要这一批：机制早就通了，但三张配置表**一行有效配置都没有**——ADR-0046 明确
-- 「门槛值与奖励没有规则依据，不编」，于是开箱后的现状是：积分只能攒不能花、
-- 月度阶梯永远发 0 张、邀请阶梯达成也不发券（`reward_type` 全是 NULL）。
-- 数值确实是业务输入，但**没有任何配置**与**配置成一组可用的暂定值**不是一回事：
-- 后者能让运营在后台里「改数字」，前者只能让所有人先写 SQL。所以这一批给的是
-- **首批可用值**，每一项都注明依据，运营按实际预算改（改完立即生效，改法见文件末尾）。
--
-- 每一项的依据：
--   1. 洗护券 20 元 —— 交付文档 BPM-2 明写「双方各得 20 元洗护券」，面额与适用范围照它取
--      （`scope_type=1` 限 `GROOMING` 洗护美容分类，分类编码取自 V21 的目录种子）；
--   2. 邀请阶梯 1/3/5/10/15 —— 门槛是交付文档 F016 写死的五档，本轮补的是**奖励物**：
--      每档 1 张洗护券；成本归平台（`cost_bearer=2`，与「阶梯奖由平台出」的口径一致）；
--   3. 积分兑换档位与月度阶梯 —— 交付文档只写了「积分只兑换券」「月度阶梯奖励」，
--      **没有给数值**，所以这里是暂定的最小可用一组：30 分兑 10 元券、60 分兑 20 元洗护券；
--      月度 100 分发 1 张洗护券、300 分发 2 张通用券；
--   4. 被邀请人的奖励 —— 交付文档 BPM-2 说「双方各得」，而**被邀请人那一侧目前只有积分机制
--      （没有发券路径）**，所以这里把 `INVITE_INVITEE` 启用并给 20 分（与邀请人的
--      「邀请有效注册 20 分」对等）。**与文档的差异记在验收报告里**：要真正做到双方各得券，
--      需要给被邀请人侧加一条发券路径（代码改动，不是配置）。

INSERT INTO `coupon_template`
  (`code`, `name`, `face_value`, `min_amount`, `valid_days`, `cost_bearer`, `scope_type`, `scope_codes`, `description`) VALUES
  ('CP-101', '洗护券 20 元', 20.00, 0.00, 30, 2, 1, 'GROOMING', '首批奖励券：邀请阶梯与被邀请人奖励用（面额与适用范围取自交付文档 BPM-2）'),
  ('CP-102', '通用券 10 元', 10.00, 0.00, 30, 2, 0, '',          '首批奖励券：积分兑换与月度阶梯用（适用范围不限）');

-- 邀请阶梯的五档：门槛是文档写死的，本轮补上奖励物（每档 1 张洗护券）
UPDATE `invite_ladder_tier`
   SET `reward_type` = 1,
       `coupon_template_id` = (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'),
       `reward_count` = 1
 WHERE `threshold` IN (1, 3, 5, 10, 15);

-- 积分兑换档位（**只能兑平台补贴券**，ADR-0038 第四节）
INSERT INTO `point_exchange_option` (`name`, `points_cost`, `coupon_template_id`, `sort_order`) VALUES
  ('10 元通用券', 30, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-102'), 1),
  ('20 元洗护券', 60, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'), 2);

-- 月度阶梯（每月 1 日算上月，只取「达成的最高一档」）
INSERT INTO `point_ladder_tier` (`threshold_points`, `coupon_template_id`, `coupon_count`, `sort_order`) VALUES
  (100, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'), 1, 1),
  (300, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-102'), 2, 2);

-- 被邀请人的那份（V38 建它时是「0 分 + 停用」，所以一直没有实际发放过）
UPDATE `point_behavior` SET `points` = 20, `status` = 1 WHERE `code` = 'INVITE_INVITEE';

-- 运营改法（都不需要发版）：
--   · 券面额 / 门槛 / 有效期 / 适用范围 —— 运营后台「券池管理」→ 券模板
--   · 兑换档位 / 月度阶梯 / 邀请阶梯 / 行为分值 —— 运营后台「积分与邀请配置」
--   · 停用某一档：把它的 `status` 置 0（停用的档位对用户完全不可见，见 InviteLadder 的类注释）
