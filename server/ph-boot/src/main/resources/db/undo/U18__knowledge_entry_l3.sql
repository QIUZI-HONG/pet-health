-- 回滚 V18：知识库 L3 结构与种子条目、以及 ai_consult 的两列留痕扩展。
--
-- 回滚顺序与 V18 相反：先撤留痕列，再删表（条目与类目种子随表一起消失，无需单独删）。
--
-- 回滚后的状态：AI 咨询退回「没有知识来源」的样子（citations 恒为空、检索层不存在），
-- 也就是 V18 之前的样子。**引用与检索的代码仍在**，所以回滚后必须同步把 AI 服务退回到
-- 不接检索的版本（`ai/app/main.py` 的检索调用靠 `retrieval_check` 留痕，读不到表即
-- 记 unavailable 并照常回答——这条兜底是检索失败不让咨询失败的设计，见 ADR-0033）。
ALTER TABLE `ai_consult`
  DROP COLUMN `retrieval_check`,
  DROP COLUMN `grading_rule_hits`,
  DROP COLUMN `unvetted_hits`,
  DROP COLUMN `citations`;

DROP TABLE IF EXISTS `knowledge_chunk`;
DROP TABLE IF EXISTS `knowledge_entry`;
DROP TABLE IF EXISTS `knowledge_category`;
