-- V3 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部打卡记录与健康评分历史**。生产上执行前必须已备份。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '3';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `health_score`;
DROP TABLE IF EXISTS `archive_record`;
