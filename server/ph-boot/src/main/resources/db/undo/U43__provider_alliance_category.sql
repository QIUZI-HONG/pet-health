-- V43 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉联盟分类维度表**，而 `provider.category` 的值域是靠它定义的。
-- 回滚代价说明：
--   - `provider.category` 列本身不动（它在 V22 建的表上），所以服务者的归属值还在，
--     但**维度名查不出来了**：运营后台与服务者后台的分类列会显示为空。
--   - 若运营在本迁移之后新增过维度（id > 3），那些门店的 `category` 会变成指向不存在的维度，
--     重新升级时不会自动恢复——需要人工把这些门店改回 1/2/3 或重建那张维度。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '43';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `provider_alliance_category`;
