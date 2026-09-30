-- V23 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部服务者的选品与定价，以及目录外服务提案**。
-- 回滚代价说明：
--   - 这些行是服务者自己录入的经营数据，**没有别的地方存着**：删掉之后服务者要重新勾选目录、
--     重新定价、重新提交审核；平台侧也拿不回当时的定价与驳回原因（审核痕迹在
--     `provider_review_log` 里，那张表由 V22 管，本文件不动它）。
--   - **提案被删掉后，其「通过」时生成的正式目录项仍留在 `service_item` 里**（那是 V21 的表）。
--     于是那条目录项失去了来源记录：没人能从库里看出它是平台自建还是某次提案的产物
--     （`service_item.source=2` 还标着，但对不上是哪一次提案了）。
--   - 若服务项已经被下过单，`order_item.service_code` 仍指向目录项——那是 V21 的事，与本表无关；
--     但订单里的成交价是下单时的快照，回滚本表**不会**改变历史订单金额。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '23';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `catalog_item_proposal`;
DROP TABLE IF EXISTS `provider_service`;
