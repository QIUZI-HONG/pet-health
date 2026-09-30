-- 回滚 V20：AI 运营可调项的四张表。
--
-- 回滚后的状态：提示词、护栏词表、分级规则退回 `ai/app/*.py` 里的**代码基线**
-- （`ai/app/ops.py` 的 load 在读不到表时就是这个回落路径，所以回滚不会让服务起不来），
-- 三个开关一律视为关闭：`force_rule_only` 关掉 = 模型照常调用、`retrieval_enabled` 关掉 =
-- 退回「无来源的通用建议」、`retrieval_strict` 关掉 = 空召回不降级。
--
-- 也就是说**回滚会丢掉运营侧的所有改动**（谁在什么时候把提示词从 p1 改成 p2 的记录也在表里）。
-- 回滚是运维动作，代价必须写明。
DROP TABLE IF EXISTS `knowledge_switch`;
DROP TABLE IF EXISTS `knowledge_guard_term`;
DROP TABLE IF EXISTS `knowledge_grading_rule`;
DROP TABLE IF EXISTS `knowledge_prompt_template`;
