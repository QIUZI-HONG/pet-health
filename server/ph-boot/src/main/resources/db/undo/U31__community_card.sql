-- V31 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部经验卡片与点赞**。
--
-- 回滚代价说明：
--   - 卡片是**用户原创内容**，平台没有第二份副本：删掉就是真的没了（导出用户档案
--     ——`ProfileExportApi`——不包含社区内容，它导的是档案与健康报告）。
--     与「权益授予记录」不同，卡片没有 audit_log 之外的追溯途径，而 audit_log 记的是
--     请求的元信息（谁、什么时候、什么接口），**不记正文**——正文重建不了。
--   - **点赞计数一并消失**：`community_card.like_count` 与 `community_card_like` 都在本迁移里，
--     两者一起删，不会出现「计数还在、明细没了」的半截状态。
--   - **逻辑引用不在本表这一侧**：`author_id` / `pet_id` / `source_ref` 都是逻辑引用，
--     删除本身没有外键阻碍，也不会波及 `user` / `pet` / `archive_record`。
--     反过来说，档案侧被删的记录不会让卡片失效——卡片正文是用户自己写的，不依赖档案行。
--   - 审核队列里待审的卡片也一起消失：运营**在回滚前**应先把队列清空或导出，
--     否则「运营还没审的内容」会静默丢失（这是最容易漏掉的一类损失）。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '31';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `community_card_like`;
DROP TABLE IF EXISTS `community_card`;
