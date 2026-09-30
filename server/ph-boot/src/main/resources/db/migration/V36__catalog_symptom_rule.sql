-- F011「AI 帮我找服务」的**症状 → 目录项映射**（切片 #54 / 规则版）
--
-- 决策：ADR-0050 第二节——F011 **先做规则版**：用已入库的症状分诊知识识别症状词，
-- 再用本表把症状映射到标准目录项，**不调模型**。理由是「推荐理由要说得出来」：
-- 规则版能给出「因为你说腹泻，建议常见病诊疗」，而模型版在知识条目还没复核的情况下，
-- 只能给出一段编出来的理由。模型的意图路由（ADR-0025 第五节）将来是**叠加**，不是替换。
--
-- 三条刻意的设计：
--
--   1. **症状词用知识库的规范名**（`knowledge_node.name`：呕吐 / 腹泻 / 食欲下降 / 精神沉郁 /
--      咳嗽 / 呼吸异常 / 皮肤瘙痒 / 跛行 / 耳道异常 / 眼部异常）。用户口语（「拉稀」「没精神」）
--      由知识侧的 alias 归一后到这里——所以**本表只存规范名**，不存口语变体：
--      口语词进这一层会让同一件事在两处维护，而知识库那边已经有受控词典。
--   2. **映射只到「项目」这一级，不到门店**：哪家店能做由 `provider_service` 回答
--      （`GET /catalog/items/{code}/providers`），本表不掺和——它一掺和就要面对
--      「这家店今天不上架了」这类状态同步问题。
--   3. **只映射医疗类项目**（HE-*）：症状是靠身体信号推出来的，把它映到洗护 / 用品这类项目
--      等于在疼痛上做推销。非医疗需求有别的入口（服务页分类、按项目找服务）。
--
-- 为什么入库而不是写成代码常量（ADR-0010 的三层配置）：**症状到项目的对应关系是运营会调的
-- 业务可调项**（新项目上线、某个项目改名/停用、某条映射不合适），而「怎么匹配、怎么排序」
-- 是代码常量。混在一起会让「加一条映射」变成一次发版。
--
-- 排序：同一症状下 `sort_order` 升序（越小越先展示）。**首条是主推**，其余是备选——
-- 界面上按顺序列出来，不下发「主推/备选」这种标签（那是排版的事，不是数据的事）。

CREATE TABLE `catalog_symptom_rule` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `symptom_keyword` VARCHAR(64)     NOT NULL COMMENT '症状规范名（与 knowledge_node.name 同一套词，如「腹泻」）',
  `item_code`       VARCHAR(32)     NOT NULL COMMENT '推荐的标准目录项编码（逻辑引用 service_item.code，不建物理外键）',
  `sort_order`      INT             NOT NULL DEFAULT 0 COMMENT '同一症状下的展示顺序，升序',
  `enabled`         TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用即不再推荐，不删行）',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id（平台运营），0 表示系统写入',
  `updated_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symptom_item` (`symptom_keyword`, `item_code`),
  KEY `idx_symptom_enabled_sort` (`symptom_keyword`, `enabled`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'F011 规则版：症状 → 推荐目录项（ADR-0050 第二节）';

-- 初始映射：10 个症状 × 1–2 个项目，全部落在「医院」类项目上。
--
-- 取值范围刻意保守：**「需要现场检查」类的症状优先推检查与诊疗**（皮肤、耳道、眼部、跛行、
-- 咳嗽这些外观上分不清病因的，条目正文里自己写着「需要现场检查 / 刮片 / 灯检」），
-- 而「反复呕吐 / 腹泻 / 精神差」这类先推诊疗、再给体检兜底。
-- 这些是**工程按条目正文起草的初始值**，运营可改（改它不需要发版）——与目录的价格区间同一纪律：
-- 初始值不是权威，能改才是。
INSERT INTO `catalog_symptom_rule` (`symptom_keyword`, `item_code`, `sort_order`) VALUES
  ('呕吐',     'HE-012', 1),   -- 常见病诊疗
  ('呕吐',     'HE-013', 2),   -- 专项检查（影像/化验）
  ('腹泻',     'HE-012', 1),
  ('腹泻',     'HE-013', 2),
  ('食欲下降', 'HE-012', 1),
  ('食欲下降', 'HE-004', 2),   -- 基础体检
  ('精神沉郁', 'HE-012', 1),
  ('精神沉郁', 'HE-004', 2),
  ('咳嗽',     'HE-013', 1),
  ('咳嗽',     'HE-012', 2),
  ('呼吸异常', 'HE-013', 1),
  ('皮肤瘙痒', 'HE-013', 1),
  ('皮肤瘙痒', 'HE-012', 2),
  ('跛行',     'HE-013', 1),
  ('跛行',     'HE-012', 2),
  ('耳道异常', 'HE-012', 1),
  ('耳道异常', 'HE-013', 2),
  ('眼部异常', 'HE-012', 1);
