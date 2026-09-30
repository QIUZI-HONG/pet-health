-- V24 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部券模板、服务者的承诺额度与已经发到用户手里的券**。
-- 回滚代价说明：
--   - 券实例是**用户已获得的权益凭证**：删掉之后用户手里的券凭空消失，而线下（门店）无法判断
--     一张券该不该认——本项目钱在门店付（ADR-0036），券一旦发出就只能靠这张表说话。
--   - 服务者的承诺额度与它的额度流水一起消失：**考核「券」那一项（ADR-0039 第三节）会失去依据**，
--     事后只能从 `audit_log`（若记录了这些动作）里勉强重建。
--   - 三张表互不引用别的模块的表，所以删除本身没有外键阻碍；
--     `coupon_template.scope_codes` 里引用的分类 / 目录项编码属于 ph-catalog，与本回滚无关。
--   - 回滚后**已核销的券记录也没了**：对账口径（实例数 = 已核销 + 未过期未核销 + 已过期未核销）
--     因此无法复核，「这张券核销过没有」再也查不到。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '24';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `coupon`;
DROP TABLE IF EXISTS `coupon_contribution_log`;
DROP TABLE IF EXISTS `coupon_contribution`;
DROP TABLE IF EXISTS `coupon_template`;
