-- 回滚 V8：合规文档表。
-- 回滚后前端取不到条款页面（会走错误态）；若已有法务定稿的正文，先导出再回滚。
DROP TABLE IF EXISTS `compliance_document`;
