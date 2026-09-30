-- V22 的回滚脚本（人工执行，Flyway 社区版没有 undo 命令）
--
-- ⚠️ 执行前先确认：这一步会**丢掉全部服务者、资质材料、入驻审核流水与账号绑定**。
-- 回滚代价说明（按依赖顺序讲清楚，别只写「会丢数据」）：
--   - `provider_user` 一删，服务者后台的身份就无法解析到服务者——所有 `/api/v1/provider/**`
--     接口对这些人返回 40400「尚未绑定服务者」，**不是报错但等于全部不可用**；
--   - `provider_review_log` 是 append-only 的审核流水，删掉**无法从别处重建**：
--     申请单上的 reject_reason / reviewed_at 只保留最后一次结论，中间几次驳回重提的痕迹只在这里；
--   - `provider_service`（V23）里的行会变成悬空的 provider_id，所以回滚本文件必须**先回滚 V23**；
--   - 资质证件号是**密文存储**（ADR-0013），备份时注意到这一点：备份文件里的 cert_no 不是明文，
--     要还原成明文得同时留着 FIELD_ENC_KEY。
--
-- 人工回滚的三步（ADR-0011）：
--   1. 执行本文件；
--   2. DELETE FROM flyway_schema_history WHERE version = '22';
--   3. 重启应用，确认迁移状态。

DROP TABLE IF EXISTS `provider_user`;
DROP TABLE IF EXISTS `provider_review_log`;
DROP TABLE IF EXISTS `provider_onboarding_application`;
DROP TABLE IF EXISTS `provider_qualification`;
DROP TABLE IF EXISTS `provider`;
