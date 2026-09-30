-- V39 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- 回滚要删的是「首批增长配置」，**不是已经发出去的东西**：
--   · 用户手里已发的券、已记的积分流水都是既成事实，回滚不会也不能撤销它们；
--   · 回滚后新的一轮批算不再发券（档位没了），积分的兑换档位消失（已兑出的券照常可用）；
--   · `invite_ladder_tier` 回到「有档位、没奖励」——达成照样记录（谁在哪一档是数据），只是不发东西。
--
-- 顺序：先删引用模板的配置行，再删模板本身（都是逻辑引用，没有外键，顺序只是为了不留悬空 id）。

DELETE FROM `point_ladder_tier`;
DELETE FROM `point_exchange_option`;

UPDATE `invite_ladder_tier`
   SET `reward_type` = NULL,
       `coupon_template_id` = NULL,
       `reward_count` = 1;

DELETE FROM `coupon_template` WHERE `code` IN ('CP-101', 'CP-102');

-- 被邀请人奖励回到 V38 建它时的样子：0 分 + 停用（此前从未发过，回滚后也不再发）
UPDATE `point_behavior` SET `points` = 0, `status` = 0 WHERE `code` = 'INVITE_INVITEE';
