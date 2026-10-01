-- 回滚 V47：列注释退回「1提交…9改联盟归属」（V43 那一版）。
--
-- 只回滚注释：值域没变过，所以回滚后 action = 10 这条**取值的语义不变**——
-- 只是注释里不再写它。回滚的意义是让 schema 回到 V46 的样子。
ALTER TABLE `provider_review_log`
  MODIFY COLUMN `action` TINYINT NOT NULL
  COMMENT '1提交2重提3通过4驳回5上架6下架7冻结8解冻9改联盟归属（常量在 ProviderReviewLog）';
