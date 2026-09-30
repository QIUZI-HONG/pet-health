-- 回滚 V19：知识关系层。
--
-- 回滚后的状态：L2 关系层不存在。**AI 服务仍能回答**（关键词检索与 L1 结构化查询是另外两条腿），
-- 但丢掉两件事：① 安全门（contraindicated_for / toxic_to 的硬过滤没了）；
-- ② 口语变体的召回补齐（用户说「不吃东西」而条目写「食欲下降」时召回率下降）。
-- 这正是 ADR-0022 把 L2 与 L3 并列、而不是只做关键词检索的理由。
DROP TABLE IF EXISTS `knowledge_edge`;
DROP TABLE IF EXISTS `knowledge_node`;
