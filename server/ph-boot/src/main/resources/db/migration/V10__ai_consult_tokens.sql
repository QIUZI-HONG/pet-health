-- AI 留痕补 token 用量（ADR-0026）：日预算只告警不熔断，前提是知道当天花了多少。
--
-- 为什么要落库而不是只记日志：模型换代、提示词改版、分级漂移的归因都要看用量曲线，
-- 而日志是留不下曲线的。两列都是「本轮实际消耗」，红线短路与降级路径记 0（那两条没调模型）。
--
-- 默认 0 而不是 NULL：聚合时不用写 IFNULL，也让「没调模型」与「历史数据缺失」在代码里少一层判断。
ALTER TABLE `ai_consult`
    ADD COLUMN `prompt_tokens`     INT NOT NULL DEFAULT 0 COMMENT '输入 token（本轮未调模型记 0）' AFTER `latency_ms`,
    ADD COLUMN `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT '输出 token（本轮未调模型记 0）' AFTER `prompt_tokens`;
