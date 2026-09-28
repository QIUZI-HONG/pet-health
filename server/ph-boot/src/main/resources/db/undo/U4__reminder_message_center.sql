-- V4 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部消息与提醒设置**，还会**删掉同日重复的档案记录**
-- （见下面「为什么要先去重」）。生产上执行前必须已备份。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '4';
--   3. 重启应用，确认迁移状态。

-- ---------------------------------------------------------------------------
-- 为什么要先去重：V4 之前 archive_record 的唯一键是 (pet_id, record_date, category)，
-- **同一天同一个分项只允许一条**；V4 起防疫记录（category = 7）改成允许同一天多条
-- （同一天既打疫苗又驱虫是常态）。所以直接把旧唯一键加回去会当场失败。
--
-- 2026-09-28 的回滚演练实测（带数据的 scratch 库上跑本文件）：
--   ERROR 1062: Duplicate entry '1-2026-09-21-7' for key 'archive_record.uk_pet_date_category'
-- 也就是说：**没有这一段，这个回滚脚本在真实数据上根本跑不动**——那样「能回滚」只是纸面结论。
--
-- 去重规则：同一 (pet_id, record_date, category) 只留 id 最小的一条，其余物理删除。
-- 与「会丢消息」同一性质，是这个脚本的既定代价；要保住多条防疫记录就别回滚 V4，
-- 或先把重复行导出到别处。
-- ---------------------------------------------------------------------------
DELETE a
  FROM `archive_record` a
  JOIN `archive_record` b
    ON a.`pet_id` = b.`pet_id`
   AND a.`record_date` = b.`record_date`
   AND a.`category` = b.`category`
   AND a.`id` > b.`id`;

-- 先还原唯一键的形状，再删 V4 加的两列（顺带删索引）
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
