-- V15 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部分项记录的结构化载荷**——证件、其他指标、老年专项、
-- 就医记录这几类（category 7/8/10/11/12）会退化成「只剩日期与分类」的空壳行，
-- 而它们的内容不在 `content` 里，所以**没有别的列能找回**。生产上执行前必须已备份。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '15';
--   3. 重启应用，确认迁移状态。

ALTER TABLE `archive_record` DROP COLUMN `structured_payload`;
