-- 回滚 V11：删掉 created_at 的索引。
-- 代价：日预算的聚合查询退回全表扫（功能不受影响，只是每小时一次会越来越慢）。

ALTER TABLE `ai_consult`
    DROP KEY `idx_created_at`;
