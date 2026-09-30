-- V27 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部积分账户、流水、任务与档位配置（含月度阶梯档位）**。
-- 回滚代价说明：
--   - 积分流水是**用户已获得积分的唯一凭据**：删掉之后用户攒的分凭空消失，
--     而积分是能用来的兑换券的（等价于用户资产），无法用别处数据重建——
--     `audit_log` 里没有逐笔积分的记录。
--   - 已兑换发出的券**不会回滚**（在 V24 的 `coupon` 表里），于是会留下
--     「券发出去了但看不出是花多少分换的」这笔烂账。
--   - 月度阶梯发放记录删掉后，**同一账期可以再发一次**（唯一键没了）；
--     若重建时忘记这一点，重复发券不会被拦。
--   - 任务清单、行为分值表、月度阶梯档位表都带**种子数据**，重新跑 V27 会恢复种子值，
--     但**运营改过的分值 / 档位不会回来**（配置也是数据）。
--   - `point_ladder_grant.coupon_id` 指向 V24 的券；删本表不影响券，只丢失溯源。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '27';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `point_config`;
DROP TABLE IF EXISTS `point_ladder_grant`;
DROP TABLE IF EXISTS `point_ladder_tier`;
DROP TABLE IF EXISTS `point_exchange_option`;
DROP TABLE IF EXISTS `point_task`;
DROP TABLE IF EXISTS `point_behavior`;
DROP TABLE IF EXISTS `point_record`;
DROP TABLE IF EXISTS `user_point`;
