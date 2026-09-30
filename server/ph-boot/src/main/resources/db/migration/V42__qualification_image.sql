-- 资质材料传图（ADR-0053）
--
-- 背景：`provider_qualification` 从建表起就有个 `file_url` 列（交付文档的 DDL 示例里有），
-- 但**从来没有人写过它**——服务者侧一直没有上传入口，前端也不建模这个字段。
-- 它的问题不只是「空着」：契约把它定义成客户端自由字符串，服务端原样入库、零校验；
-- 而文件读地址是签名 URL、默认 10 分钟过期（ADR-0020），**把 URL 存库必然存成死链**。
--
-- 改成与三道照片墙（ADR-0040 第四节）同构的做法：**库里存 `file_id`，读的时候当场签发地址**。
--   存 id     → 不会过期，也不随域名/密钥轮换而失效；
--   读时签发  → 审核员过几天点「打开材料」照样能看（这正是本次要一次做对的那半）。
--
-- 为什么可以直接删 `file_url` 而不是留着：
--   该列在本机开发库里是 2 行、**0 行有值**；代码侧也没有任何路径写过它
--   （OnboardingService / ProviderProfileService 原先只是把请求里的字符串原样透传）。
--   它不是「将来可能有用」，是「从未通电」——留着只会让下一个读代码的人以为图存在那里。
--   要回滚，U42 会把它加回来（结构可逆，数据不可逆，见 U42 的说明）。
--
-- 老数据（改动前提交的材料）在这次之后是「没有图」的状态。口径是**必填只在写入时强制**：
-- 不追溯否定已有记录——`ProviderAccess.hasValidQualification` 决定「有没有有效资质」，
-- 若把「没图」也算成无效，会连带把门店已上架的服务项下架，那是一次静默的数据事故，不做。
-- 服务者在「补交 / 更新材料」里重传一次即可（那次就必须带图了）。
--
-- 顺序说明：先加后删。MySQL 的 DDL 不在事务里，万一中间失败，先加后删留下的是
-- 「两列都在」这种无害状态；反过来则会留下「图没了、新列也还没建」的坏状态。

ALTER TABLE `provider_qualification`
  ADD COLUMN `file_id` BIGINT UNSIGNED DEFAULT NULL
      COMMENT '材料图片的文件 id（ph-file，biz_type=qualification）；读用的签名地址由后端当场签发，不落库（ADR-0053）'
      AFTER `cert_no_hash`;

ALTER TABLE `provider_qualification`
  DROP COLUMN `file_url`;
