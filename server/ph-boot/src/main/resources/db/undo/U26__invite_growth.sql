-- V26 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部邀请码、邀请关系、阶梯达成与反作弊记录**。
-- 回滚代价说明：
--   - 邀请关系是**归因的唯一依据**，而 ADR-0039 明确「归因只在注册那一刻，不做事后补填」：
--     删掉之后没有任何办法重建「谁邀请的谁」，也**无法按原口径重算**已发出的阶梯奖励。
--   - 已发出的券与已授予的权益**不会回滚**（它们在 V24 / V25 的表里），
--     于是数据会变成「有奖励但没有对应的邀请关系」——对账时这笔券的来路就断在这里。
--   - 反作弊记录删掉后，同一批账号（同设备 / 同 IP）的判据也随之消失，
--     重新启用邀请功能时它们会被当成新账号重新刷一遍。
--   - 阶梯档位是种子数据（五档门槛），重新跑 V26 会恢复档位，但**配好的奖励物与达成记录不会回来**。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '26';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `invite_risk_record`;
DROP TABLE IF EXISTS `invite_ladder_achievement`;
DROP TABLE IF EXISTS `invite_ladder_tier`;
DROP TABLE IF EXISTS `invite_relation`;
DROP TABLE IF EXISTS `invite_code`;
