-- 回滚 V46：被邀请人奖励（券）规则表整张删掉。
--
-- 与其它 undo 一样：这一条**只回滚结构**。已经发出去的券（`coupon` 表里 source=1 且
-- source_ref 形如 `invitee-coupon:*` 的那些）不在这里删——券是用户资产，回滚迁移不该
-- 把用户手里的券收走；真要收，那是运营动作（券实例有自己的状态机）。
DROP TABLE IF EXISTS `invitee_reward_rule`;
