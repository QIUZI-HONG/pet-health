-- V4 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部消息与提醒设置**。生产上执行前必须已备份。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '4';
--   3. 重启应用，确认迁移状态。

-- 先撤 V4 给 archive_record 加的两列（顺带删索引），再删三张表
-- 先还原唯一键的形状，再删 V4 加的三列
ALTER TABLE `archive_record`
  DROP KEY `uk_checkin_slot`,
  DROP COLUMN `checkin_slot`,
  ADD UNIQUE KEY `uk_pet_date_category` (`pet_id`, `record_date`, `category`),
  DROP KEY `idx_due_on`,
  DROP COLUMN `numeric_value`,
  DROP COLUMN `due_on`;

DROP TABLE IF EXISTS `reminder_rule`;
DROP TABLE IF EXISTS `reminder_setting`;
DROP TABLE IF EXISTS `message`;
