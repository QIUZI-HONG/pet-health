-- V21 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉平台标准服务目录的全部内容**（含种子分类与项目、以及目录外服务的提案与流水）。
-- 回滚代价说明：
--   - 目录项编码（`service_item.code`）已经印进了 `provider_service.service_code` 与
--     将来的 `order_item.service_code`（交付文档 7.2）。本文件只删目录表，**不动**那两张表里的编码，
--     于是它们的 code 会变成悬空引用：C 端与下单侧读不到名称、价格区间校验查不到区间。
--     所以回滚 V21 必须**同时**回滚 V23（服务者上架行）并接受历史订单项失去平台侧定义。
--   - **编码不可复用的承诺会在回滚后失效**：重建目录时 id 与编码都可能重新分配。
--     若回滚后又按同一套编码重建，历史订单项能"重新对上"，但对不上时没有任何机制会发现。
--   - 目录外服务提案（`catalog_item_proposal`）与其审核流水在 ph-provider（V22 / V23），本文件不动它们；
--     回滚后它们会指向不存在的分类与项目编码：提案单还在、但「通过」这一步再也执行不了
--     （没有分类可挂），需要人工处置。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '21';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `service_item`;
DROP TABLE IF EXISTS `service_category`;
