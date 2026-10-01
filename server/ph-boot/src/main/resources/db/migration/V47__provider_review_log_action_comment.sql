-- V43 的列注释漏了一档：`action = 10 改区域编码`（V45 引入的端点写它，常量 ProviderReviewLog.ACTION_ASSIGN_REGION）
--
-- 为什么值得单独一条迁移：`action` 是 TINYINT，**没有枚举约束——注释是它唯一的说明书**
-- （V43 自己写下的口径）。漏一档的后果不是「界面显示不对」，是事后查流水时没人知道 10 是什么，
-- 而这条表的存在意义正是「事后能查是谁改的」（ADR-0037 第一节）。
--
-- 只改注释，不改取值、不加约束：既有数据与代码都不动。回滚见 db/undo/U47。

ALTER TABLE `provider_review_log`
  MODIFY COLUMN `action` TINYINT NOT NULL
  COMMENT '1提交2重提3通过4驳回5上架6下架7冻结8解冻9改联盟归属10改区域编码（常量在 ProviderReviewLog）';
