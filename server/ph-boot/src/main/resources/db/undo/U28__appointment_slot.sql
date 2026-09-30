-- V28 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部号源占用记录（已建的时段行）**。
-- 回滚代价说明：
--   - `appointment_slot` 是**唯一的占用凭据**：删掉之后「某天某时段还约得进几个」无从判断，
--     只能按订单表反推（`order` 里 status in (0,1,2,3) 的行按
--     `(provider_id, service_id, appointment_date, start_time)` 数一遍，再重建时段行）。
--     反推不难，但**必须做**——否则同一个时段会被重复售卖（超卖），而那是本表存在的唯一理由。
--   - 本表没有种子数据，重建只需重跑 V28；已被运营调过的 `capacity` 不会回来（配置也是数据）。
--   - 回滚**不影响订单与券**：它们各自在自己的表里，V28 只丢掉「还剩多少容量」这一个事实。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '28';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `appointment_slot`;
