-- 日预算告警的聚合查询缺索引（ADR-0026 落地时发现）。
--
-- 告警每小时跑一次「今天消耗了多少 token」：`WHERE created_at >= 今天零点` 的聚合。
-- V7 建的三条索引前导列分别是 user_id / pet_id / risk_level，没有一条能用在这个范围扫描上——
-- 没有 idx_created_at 就是每小时一次全表扫（现在表小看不出来，但它会一直长）。
--
-- 索引名与其它表保持同一命名法（idx_<列>）。
ALTER TABLE `ai_consult`
    ADD KEY `idx_created_at` (`created_at`);
