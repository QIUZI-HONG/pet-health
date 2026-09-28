-- 回滚 V10：删掉 token 两列。
--
-- 代价（按 server/README.md 的回滚三步执行前先看清）：**历史用量数据随列一起丢失**，
-- 而它没法从别处重建（模型供应商的账单按账号汇总，回不到「哪一次咨询」）。
-- 如果只是要停用预算告警，改配置（ai.budget.*）或用不上就留着这两列，别回滚。

ALTER TABLE `ai_consult`
    DROP COLUMN `completion_tokens`,
    DROP COLUMN `prompt_tokens`;
