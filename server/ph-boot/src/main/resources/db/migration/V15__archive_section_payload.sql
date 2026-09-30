-- 档案分项的结构化载荷（切片 #102，决策见 ADR-0023 与 ADR-0030）
--
-- ADR-0023 定的是「一张 archive_record + structured_payload 承载 8 个分项」；V3/V4 建表时
-- 只有 `content`（VARCHAR(1024) 的扁平键值，打卡与防疫在用）。这一步把 ADR 里点名的
-- `structured_payload` 补上——它是 **JSON 列**，因为分项记录要装的是
-- 「标题 + 取值 + 单位 + 到期日 + 备注」这类有形状的对象，而且要能被查询而不只是被回显。
--
-- 为什么不让新分项也写 `content`（一列塞两种东西更省事）：
--   1. VARCHAR(1024) 装不下结构化对象，且它不是 JSON 类型，将来按 JSON 路径查询要全表扫描；
--   2. 打卡与防疫的 `content` 有四个既有写路径（打卡 upsert / 防疫录入 / 评分聚合 / 提醒源），
--      改列等于同时改四处与它们的测试，收益只是少一列——不值得在这个切片里做。
--   3. 读接口把两者**归一成同一个 `payload` 视图**再下发，前端看不到这个差别。
--
-- 分项与 category 的映射（ADR-0030 的权威表）：
--   1 体重 / 2 饮食 / 3 排泄 / 4 行为 / 5 情绪 / 6 卫生   —— 打卡六项（一天一条，V4 的 checkin_slot 约束）
--   7 防疫 / 8 就医 / 10 其他指标 / 11 证件 / 12 老年专项  —— 列表语义（同一天可以有多条）
--   9 保留（历史 DDL 里的「其他」），13 起未分配

ALTER TABLE `archive_record`
  ADD COLUMN `structured_payload` JSON DEFAULT NULL
      COMMENT '分项记录的结构化载荷，如 {"title":"免疫证","value":"...","unit":"...","note":"..."}；打卡与防疫用 content'
      AFTER `content`;
