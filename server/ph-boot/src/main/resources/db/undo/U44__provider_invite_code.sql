-- V44 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉门店推广码与门店维度的归因**。
-- 回滚代价说明：
--   - `inviter_user_id` 要恢复 NOT NULL，前提是**门店归因那一批关系已经不存在**
--     （它们的 inviter_user_id 是 NULL）。所以本文件先删掉它们——**这一步是不可逆的**：
--     那些被邀请人已经拿到的奖励（积分）不会跟着回收，而他们与门店的关系就此消失。
--     要留档请先 `SELECT * FROM invite_relation WHERE inviter_user_id IS NULL` 导出。
--   - 考核的拉新项会重新变成「未参与（数据源未接线）」，历史账期的分不会重算。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '44'；
--   3. 重启应用，确认迁移状态。

DELETE FROM `invite_relation` WHERE `inviter_user_id` IS NULL;

ALTER TABLE `invite_relation`
  DROP CHECK `ck_invite_relation_inviter`,
  DROP KEY `idx_inviter_provider_status`,
  DROP COLUMN `inviter_provider_id`,
  MODIFY COLUMN `inviter_user_id` BIGINT UNSIGNED NOT NULL COMMENT '邀请人（逻辑引用 user.id）';

DROP TABLE IF EXISTS `provider_invite_code`;
