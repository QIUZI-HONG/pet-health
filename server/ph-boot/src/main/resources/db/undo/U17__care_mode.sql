-- V17 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：
--   1. 用户手动关闭专项照护的意愿会**丢失**（`pet.care_mode_disabled` 整列删掉）——
--      回滚后这些宠物会重新按生日/慢病派生为「已开启」，用户需要再关一次。
--   2. 照护档的提醒阈值键会从 `reminder_rule.config` 里移除，照护宠物回到普通档的提醒提前量。
--   这两个后果都是**可以接受的**（不丢原始记录、不丢历史报告），所以本回滚不需要备份即可执行；
--   但「用户意愿丢失」这件事要在变更记录里写明。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '17';
--   3. 重启应用，确认迁移状态。

UPDATE `reminder_rule`
   SET `config` = JSON_REMOVE(`config`, '$.careAdvanceDays', '$.careOverdueGraceDays')
 WHERE `type` IN (1, 2);

UPDATE `reminder_rule`
   SET `config` = JSON_REMOVE(`config`, '$.careWeightChangePercent')
 WHERE `type` = 5;

ALTER TABLE `pet` DROP COLUMN `care_mode_disabled`;

DROP TABLE IF EXISTS `care_mode_rule`;
