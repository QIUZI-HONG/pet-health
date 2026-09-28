-- 回滚 V7：AI 咨询留痕表。
--
-- **回滚会丢掉全部咨询历史**，而这些历史正是分级漂移的事后归因依据（ADR-0021）。
-- 要保留就先导出：SELECT * FROM ai_consult 到别处，再执行这条。
DROP TABLE IF EXISTS `ai_consult`;
