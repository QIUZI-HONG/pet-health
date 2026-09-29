-- 回滚 V13：操作审计表。
--
-- **回滚会丢掉全部审计记录**，而审计的价值恰恰是「事后能证明发生过什么」（ADR-0028）——
-- 合规与安全排查都会用到它。要保留就先导出再执行这条：
--     SELECT * FROM audit_log INTO OUTFILE ...   或用 mysqldump 只导这一张表。
DROP TABLE IF EXISTS `audit_log`;
