-- V45 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉推荐优先级列**，浏览侧会退回按 `provider.level DESC` 排序。
-- 回滚代价说明：
--   - `region_code` 列**不动**（它在 V22 建的表上），运营写过的区域值照旧保留；
--   - 考核写回的 `recommend_priority` 会随列一起消失；重新升级时按 `level` 回填（本文件的恢复口径）。
--   - 「运营改档位映射 → 排序变化」这条链路在回滚期间失效（排序只看 level 三档）。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '45'；
--   3. 重启应用，确认迁移状态。

ALTER TABLE `provider`
  DROP KEY `idx_recommend_priority`,
  DROP COLUMN `recommend_priority`;
