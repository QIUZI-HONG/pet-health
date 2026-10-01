-- 回滚 V48：把 `provider_invite_code.status` 加回来（默认 1 = 启用，与删除前库里的取值一致）。
--
-- 注意：这一列加回来之后**仍然是死的**（没有写入点、归因也不读）——回滚恢复的是结构，
-- 不是那个本该有的「停用」能力。
ALTER TABLE `provider_invite_code`
  ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用0停用（当前无写入点，见 V48 的说明）' AFTER `code`;
