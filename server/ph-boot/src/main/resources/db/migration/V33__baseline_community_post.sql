-- 注册基线权益的**存量回填**：给已经存在的账号补上 `community.post`
--
-- 背景（ADR-0051 待澄清第 1 条「内容冷启动」／交付文档 2.2 的权限矩阵）：
-- 矩阵里「发布内容 / 评论」给的是**注册用户**，而社区发文的门禁是权益码
-- `community.post`（ADR-0041 第一节）。这条路径在本轮之前**根本不存在**——
-- 邀请阶梯没配 `rights_code`、打卡不发权益、注册也不发，于是社区那道门关着且没人能开。
--
-- 本轮的落点是**注册流程**（`ph-account` 的 `BaselineRights`：新账号在注册事务里授予一条
-- 平台默认的 `community.post`），但那条路径只能覆盖**之后**注册的人；这一版迁移负责另一半：
-- 把**已经存在**的账号补齐，两半合起来才是「注册即可发布」这句口径的完整落地——
-- 只做一半就会留下「有的人能发内容、有的人不能，而他们看起来没有任何区别」的错位。
--
-- 三个刻意的写法：
--   1. **来源用 4（运营补偿）、永久、source_ref 固定为 `register:baseline`**——与
--      `BaselineRights` 逐字相同。理由见那个类：ADR-0038 / 0045 的枚举里没有「注册 / 平台默认」，
--      而基线必须是**优先级最低**的那一条（判定取 source 最小者），所以现有四项里只有 4 合适。
--      两边**必须一致**，否则同一个用户会有两条来源不同的授予记录，
--      而 `(user_id, code, source, source_ref)` 唯一键也拦不住这种重复；
--   2. **只补还没有这条授予的账号**（LEFT JOIN ... IS NULL）：迁移重跑、手工补跑都不会撞唯一键；
--   3. **不给禁用与已注销的账号补**：他们现在登不上，补了也只是多一行无主数据
--      （解禁时若仍需要，走运营手动授予——ADR-0045 第六节的「手动授予」就是为这种情形留的口子）。
--
-- 回滚见 `db/undo/U33__baseline_community_post.sql`（那个回滚**不会**动注册流程发的授予，
-- 只删本迁移插入的那一批：`source_ref = 'register:baseline'` 的行——见该文件的说明）。

INSERT INTO `rights_grant`
  (`user_id`, `code`, `source`, `source_ref`, `expire_at`, `status`, `remark`,
   `created_by`, `updated_by`, `trace_id`, `is_deleted`)
SELECT u.`id`, 'community.post', 4, 'register:baseline', NULL, 1,
       '注册即得（交付文档 2.2：发布内容 = 注册用户；V33 存量回填）',
       0, 0, '', 0
FROM `user` u
LEFT JOIN `rights_grant` g
       ON g.`user_id` = u.`id`
      AND g.`code` = 'community.post'
      AND g.`source` = 4
      AND g.`source_ref` = 'register:baseline'
WHERE u.`is_deleted` = 0
  AND u.`status` = 1
  AND g.`id` IS NULL;
