-- 回滚 V42：把 `file_url` 加回来、去掉 `file_id`。
--
-- **结构可逆，数据不可逆**：已经存进 `file_id` 的图片引用不会因此变成 URL。
-- 回滚之后这些材料的图就不可读了——列回来了，但没有代码会往它里面填地址
-- （这正是 V42 要修的问题）。所以回滚等于「放弃资质材料图」，
-- 要做之前先想清楚是不是真的要退回去。
ALTER TABLE `provider_qualification`
  ADD COLUMN `file_url` VARCHAR(512) DEFAULT NULL
      COMMENT '材料图片 URL（证件用 PNG，见 docs/conventions.md）'
      AFTER `cert_no_hash`;

ALTER TABLE `provider_qualification`
  DROP COLUMN `file_id`;
