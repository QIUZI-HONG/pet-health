-- 知识库 L3 文本检索层 + 类目字典 + L1 结构化载荷（切片 #100；层间边界见 63 号调研 §1.2）
--
-- 三层里的这一层存「需要解释、需要引用原文」的知识，检索靠 **MySQL 8.4 的 ngram 全文索引**
-- （ADR-0022 定：关键词检索先做，向量层挂起不实现；#63 已实测中文子串可用）。
--
-- 三条设计取舍写在这里，免得下次有人「顺手」改掉：
--
-- 1) **检索单位是条目（entry），不是分块（chunk）**。引用要给的是条目编号（CONTEXT.md 的
--    知识库三层：引用回答时给检索层的条目编号），分块只在向量层用。所以 ngram 全文索引建在
--    `knowledge_entry` 上；`knowledge_chunk` 照 ADR-0022 仍建（含 `embedding_model` 列），
--    用来「留接口不实现」——检索侧**不回落到向量**。
--
-- 2) **`code` 是对外的稳定编号（K-0001）**，不是主键。主键 id 各环境不同（种子在开发库与线上
--    库拿到的自增值不一样），而引用与留痕要跨环境可比；同理 `knowledge_edge` 引用条目用 code
--    而不是 id。
--
-- 3) **`structured_payload` 承担 L1 的确定性答案**（疫苗/驱虫周期、毒物清单、急救步骤序列）：
--    交付文档要求「疫苗/驱虫这类走结构化查询，不走检索」，落法就是同一条目既带可检索正文
--    （解释为什么）、又带可判定的结构化载荷（回答「几针、间隔多久」）。这样引用模型只有一套，
--    而「能不能/多久/几岁」的判定不经过模型（63 号调研 §1.2 的反向约束）。
--
-- `review_status` 只有两级：`pending_review`（未复核）/ `vetted`（兽医复核过）——**项目所有者定的
-- 口径**（2026-09-29，见 ADR-0033），两级就够了，工程不要在代码里再编一门复核流程。
-- 三条后果写在这里，实现与测试都按它来：
--   1) 检索侧两种状态都取（种子全是 pending_review，只取 vetted 等于检索层空转）；
--   2) **只有 `vetted` 条目能进 `citations`**——没经兽医复核的内容不许被当作依据（ADR-0025 的硬要求
--      在新口径下的具体形态）；未复核条目被用到时，回答要明说「该建议尚未经兽医复核」；
--   3) 「没有 vetted 引用时 C 端不许说『基于知识库』」——这句话的解禁条件是「至少有一条 vetted 引用」。

CREATE TABLE `knowledge_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32)     NOT NULL COMMENT '类目代码，条目与引用都引它',
  `name`        VARCHAR(64)     NOT NULL COMMENT '交付文档的八大类（文档列了十项，按十项建，见 63 号调研 §1.3）',
  `layer_hint`  VARCHAR(16)     NOT NULL DEFAULT 'l3' COMMENT 'l1/l2/l3：这个类目的知识主要落在哪一层，给运营看的提示，不参与检索逻辑',
  `sort_order`  INT             NOT NULL DEFAULT 0,
  `description` VARCHAR(256)             DEFAULT NULL,
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识类目字典（十大类）';

CREATE TABLE `knowledge_entry` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`                VARCHAR(32)     NOT NULL COMMENT '对外编号 K-xxxx，引用与留痕都用它（跨环境可比）',
  `category_code`       VARCHAR(32)     NOT NULL COMMENT 'knowledge_category.code',
  `title`               VARCHAR(200)    NOT NULL,
  `summary`             VARCHAR(500)    NOT NULL COMMENT '一句话摘要：进模型上下文与引用展示都用它，不做整篇注入',
  `body`                MEDIUMTEXT      NOT NULL COMMENT '正文（可含 Markdown），进模型上下文时按上限截断',
  `species_scope`       VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'dog / cat / all——犬猫混答是这类产品最常见的错误来源',
  `age_stage_scope`     VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'all / puppy_kitten / adult / senior；阈值待兽医定稿（63 号调研 §2.3）',
  `structured_payload`  JSON                     DEFAULT NULL COMMENT 'L1 载荷（周期表/毒物清单/步骤序列）。NULL = 该条目不承担判定，只做解释',
  `risk_hint`           VARCHAR(8)               DEFAULT NULL COMMENT 'green/yellow/red，仅症状分诊与急救类填；与 knowledge_red_flag 交叉校验',
  `confidence`          VARCHAR(8)      NOT NULL DEFAULT 'medium' COMMENT 'high/medium/low：措辞强度与降级策略看它（来源等级映射）',
  `source_type`         VARCHAR(16)     NOT NULL COMMENT 'guideline/textbook/label/paper/web/vet_input',
  `source_title`        VARCHAR(200)    NOT NULL COMMENT '来源名。**必填**：引用要能点回到原始资料',
  `source_url`          VARCHAR(500)             DEFAULT NULL,
  `source_version`      VARCHAR(64)              DEFAULT NULL COMMENT '如「2024 版」「第 5 版」',
  `source_published_at` DATE                     DEFAULT NULL,
  `review_status`       VARCHAR(16)     NOT NULL DEFAULT 'pending_review' COMMENT '两级：pending_review（未复核）/ vetted（兽医复核过）。只有 vetted 能进 citations',
  `reviewed_by`         VARCHAR(64)              DEFAULT NULL COMMENT 'vetted 时必填：谁担这个责任要落在数据里（复核流程本身待澄清，见 ADR-0033）',
  `reviewed_credential` VARCHAR(128)             DEFAULT NULL COMMENT '如「执业兽医师，证号 XXXX」',
  `reviewed_at`         DATETIME                 DEFAULT NULL,
  `effective_from`      DATE                     DEFAULT NULL,
  `effective_to`        DATE                     DEFAULT NULL COMMENT '过期即停用（把 review_status 置回 pending_review 或加 effective_to），不物理删除：历史回答仍要能追溯',
  `version`             INT             NOT NULL DEFAULT 1,
  `metadata`            JSON                     DEFAULT NULL COMMENT '分块提示等附加信息；L1/L2 用不到',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`            VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`          TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_cat_status` (`category_code`, `review_status`, `is_deleted`),
  KEY `idx_scope` (`species_scope`, `age_stage_scope`),
  -- 中文子串检索的机械保障（#63 实测可用）。ngram_token_size 是**只读**参数（默认 2，只能启动时设），
  -- 改它必须重建本索引，否则新旧索引的 token 粒度不一致（63 号调研 §3 的索引参数）。
  FULLTEXT KEY `ft_title_summary_body` (`title`, `summary`, `body`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识条目（L3 检索层 + L1 结构化载荷）';

-- 分块表：**本期不参与检索**（向量层挂起，ADR-0022）。建它有两个理由：
-- 换嵌入模型时不必再补一次迁移；以及「向量层是留了接口的、不是没想到」这句话有实物。
-- `embedding_model` 与 `vector_ref` 是留给未来向量层的：换模型 = 全量重建，不记录就会出现新旧向量混用。
CREATE TABLE `knowledge_chunk` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `entry_id`        BIGINT UNSIGNED NOT NULL,
  `seq`             INT             NOT NULL DEFAULT 1,
  `text`            TEXT            NOT NULL COMMENT '入库前应注入条目上下文（标题/物种/年龄段），63 号调研 §3 第 4 步',
  `token_count`     INT             NOT NULL DEFAULT 0,
  `embedding_model` VARCHAR(64)              DEFAULT NULL COMMENT '留空 = 尚未向量化（当前全部留空）',
  `vector_ref`      VARCHAR(64)              DEFAULT NULL COMMENT '未来 Redis 键名 kb:chunk:<id>',
  `review_status`   VARCHAR(16)     NOT NULL DEFAULT 'pending_review' COMMENT '冗余自条目，供检索前置过滤',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`        VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entry_seq` (`entry_id`, `seq`),
  KEY `idx_status` (`review_status`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识分块（向量层留接口，本期不做检索）';

-- AI 咨询留痕扩展：把「这次用了哪些知识条目、检索层有没有生效」记进留痕。
-- 留痕的价值在归因（ADR-0021）：分级漂移要能区分「是检索没生效」还是「模型改口」。
-- `citations` 只记 **vetted** 的引用（与出给 C 端的那份同一口径），未复核条目另记一列——
-- 两者分开才能回答「这句话当时是有依据的，还是拿未复核内容生成的」。
ALTER TABLE `ai_consult`
  ADD COLUMN `citations` JSON DEFAULT NULL COMMENT '本次引用的知识条目（只含 vetted，K-xxxx + 来源）',
  ADD COLUMN `unvetted_hits` JSON DEFAULT NULL COMMENT '本次上下文里用到的未复核条目编号（pending_review）',
  ADD COLUMN `grading_rule_hits` JSON DEFAULT NULL COMMENT '命中的分级规则编号（GR-xxx）：归因「是规则抬的档还是模型判的」',
  ADD COLUMN `retrieval_check` VARCHAR(16) NOT NULL DEFAULT 'ok' COMMENT 'ok/empty/unavailable/disabled/skipped：知识检索这一层是否生效';

-- ---------------------------------------------------------------- 类目种子（十项）

INSERT INTO `knowledge_category` (`code`, `name`, `layer_hint`, `sort_order`, `description`) VALUES
  ('breed',        '品种',     'l1', 1,  '体型、成年体重区间、易感病清单'),
  ('vaccine',      '疫苗',     'l1', 2,  '剂次与间隔（结构化），原理与不良反应说明（文本）'),
  ('antiparasitic','驱虫',     'l1', 3,  '体内外频次与季节修正（结构化），寄生虫科普（文本）'),
  ('triage',       '症状分诊', 'l3', 4,  '症状说明、家庭观察要点、就医时机'),
  ('disease',      '疾病',     'l3', 5,  '疾病全文（不承担判定：判定在 L1）'),
  ('behavior',     '行为',     'l3', 6,  '行为学文章：行为异常也可能指向疾病'),
  ('nutrition',    '营养',     'l1', 7,  '喂养建议（文本）+ 毒物清单（结构化安全门）'),
  ('care',         '护理',     'l3', 8,  '护理流程与频次'),
  ('first_aid',    '急救',     'l1', 9,  '急救步骤（顺序敏感，编号存表）+ 说明'),
  ('drug',         '药物',     'l1', 10, '说明摘录与禁忌（**不给剂量的唯一权威在兽医**）');

-- ---------------------------------------------------------------- 条目种子（40 条）
--
-- 口径（ADR-0025 第二节 + 2026-09-29 的复核口径补充，见 ADR-0033）：**工程按公开兽医共识起草，
-- `review_status` 一律 `pending_review`、`reviewed_by` 为空**——「内容的权威性」与「管线能不能用」
-- 分开推进，零条目下检索管线的好坏无法评估。所以每条都带 source_title（可追溯）。
--
-- **这一批里没有一条 `vetted`，是刻意的**：vetted 只能由兽医复核产出，工程不许自己盖章
-- （那正是「在代码里假造一个复核状态」）。后果按新口径如实生效：这些条目能进模型上下文，
-- **但不会出现在 citations 里**，用户看到的是「尚未经兽医复核」+ 免责声明。
--
-- 内容纪律（交付文档 9.5）：不写确诊、不写处方、**不出现任何剂量数字**；急症一律写「立即送医」。
-- 还有一条实现中发现的内容规则：**正文里不要出现护栏的禁用词**（`knowledge_guard_term` 的
-- phrase 那一档：「确诊」「处方」「剂量」…）——检索文本进模型上下文前要过护栏，命中会把
-- 那一段整段换成兜底话术（`knowledge._sanitize` 有测试），等于自己把自己的说明擦掉。
-- 描述身体状况用「要确认需要检查」「用多少由兽医决定」这类说法。

INSERT INTO `knowledge_entry`
  (`code`, `category_code`, `title`, `summary`, `body`, `species_scope`, `age_stage_scope`,
   `structured_payload`, `risk_hint`, `confidence`, `source_type`, `source_title`, `source_url`, `source_version`)
VALUES
  -- ---- 疫苗（L1：结构化周期表）----
  ('K-0001', 'vaccine', '犬核心疫苗的接种时间表', '幼犬核心疫苗一般从 6–8 周龄开始，每隔 2–4 周接种一次，最后一针在 16 周龄之后。',
   '幼犬的核心疫苗（犬瘟热、犬细小病毒、犬腺病毒、犬副流感）一般从 6–8 周龄开始接种，之后每隔 2–4 周加强一次，直到 16 周龄之后完成最后一针。母源抗体会干扰早期接种，所以最后一针不宜提前。成年后按指南定期加强。具体时间表请与兽医师确认，本条目只说明原则。',
   'dog', 'puppy_kitten',
   CAST('{"kind":"vaccine_schedule","species":"dog","start_age_weeks":"6-8","interval_weeks":"2-4","final_after_weeks":16,"note":"以 WSAVA 2024 犬猫疫苗接种指南的分组原则整理；具体剂次与间隔由兽医师按个体情况确定"}' AS JSON),
   NULL, 'high', 'guideline', 'WSAVA 2024 犬猫疫苗接种指南', 'https://wsava.org/global-guidelines/vaccination-guidelines/', '2024 版'),
  ('K-0002', 'vaccine', '猫核心疫苗的接种时间表', '幼猫核心疫苗一般从 6–8 周龄开始，每 3–4 周一次，最后一针在 16 周龄之后。',
   '幼猫的核心疫苗（猫瘟、猫杯状病毒、猫疱疹病毒）一般从 6–8 周龄开始接种，每隔 3–4 周加强一次，最后一针在 16 周龄之后完成。成年后按指南定期加强；散养或接触其他猫的个体通常需要更完整的免疫方案。具体时间表请与兽医师确认。',
   'cat', 'puppy_kitten',
   CAST('{"kind":"vaccine_schedule","species":"cat","start_age_weeks":"6-8","interval_weeks":"3-4","final_after_weeks":16,"note":"以 WSAVA 2024 犬猫疫苗接种指南的分组原则整理；具体剂次与间隔由兽医师按个体情况确定"}' AS JSON),
   NULL, 'high', 'guideline', 'WSAVA 2024 犬猫疫苗接种指南', 'https://wsava.org/global-guidelines/vaccination-guidelines/', '2024 版'),
  ('K-0003', 'vaccine', '狂犬病疫苗的接种与法定要求', '狂犬病疫苗通常 3 月龄以上接种，之后按当地规定定期加强；这是人畜共患病防线。',
   '狂犬病疫苗通常在 3 月龄以上接种，之后按当地兽医主管部门的要求定期加强。狂犬病是人畜共患病，接种既是保护宠物，也是公共场所活动的合规要求（各地对免疫证明与出行要求不同，请以当地规定为准）。若被咬伤或抓伤，人需要按人的暴露后处置流程处理，不要只处理宠物。',
   'all', 'all',
   CAST('{"kind":"vaccine_schedule","species":"all","start_age_months":3,"note":"加强周期以当地主管部门规定为准"}' AS JSON),
   NULL, 'high', 'guideline', 'WSAVA 2024 犬猫疫苗接种指南', 'https://wsava.org/global-guidelines/vaccination-guidelines/', '2024 版'),
  ('K-0004', 'vaccine', '接种后常见反应与需要就医的信号', '轻微精神差、食欲下降、注射部位肿胀多在 1–2 天内自行缓解；面部肿胀、呕吐、呼吸急促要立即就医。',
   '接种后 24–48 小时内出现轻微精神不振、食欲下降或注射部位轻度肿胀，通常可自行缓解，注意保暖与观察即可。出现面部或眼睑肿胀、全身荨麻疹、反复呕吐、呼吸急促、站立不稳等表现，属于需要立即就医的信号，不要在家观察等待。观察记录（体温、精神、进食）对兽医师判断有帮助。',
   'all', 'all', NULL, 'yellow', 'high', 'guideline', 'WSAVA 2024 犬猫疫苗接种指南', 'https://wsava.org/global-guidelines/vaccination-guidelines/', '2024 版'),

  -- ---- 驱虫（L1：结构化频次）----
  ('K-0005', 'antiparasitic', '犬的体内外驱虫频次原则', '幼犬按月驱虫；成犬体内驱虫一般每 3 个月一次，体外驱虫按月或按季，视地区与生活方式调整。',
   '体内驱虫（蛔虫、钩虫、绦虫等）在幼犬阶段通常按月进行，成年犬一般每 3 个月一次；体外驱虫（跳蚤、蜱虫）建议按月或按当地寄生虫季节安排。生活在蜱虫高发地区、经常外出草地或接触其他动物的个体需要更密的频次。具体药物与频次请由兽医师按体重、年龄与地区给出。',
   'dog', 'all',
   CAST('{"kind":"antiparasitic_frequency","species":"dog","internal_months":"3","external_months":"1-3","note":"地区与生活方式会改变频次；CAPC 指南按地区风险分级"}' AS JSON),
   NULL, 'medium', 'guideline', 'CAPC 寄生虫控制指南', 'https://www.capcvet.org/guidelines/', '2026 在线版'),
  ('K-0006', 'antiparasitic', '猫的体内外驱虫频次原则', '幼猫按月驱虫；成猫体内驱虫一般每 3 个月一次，外出的猫体外驱虫按月。',
   '体内驱虫在幼猫阶段通常按月进行，成年猫一般每 3 个月一次；有外出习惯或与外界动物接触的猫，体外驱虫建议按月。完全室内饲养的猫也需要基础驱虫，因为虫卵可以通过鞋底、生肉等途径带入家中。具体药物与频次请由兽医师按体重与生活环境给出。',
   'cat', 'all',
   CAST('{"kind":"antiparasitic_frequency","species":"cat","internal_months":"3","external_months":"1-3","note":"室内猫也需要基础驱虫；具体频次由兽医师按风险给出"}' AS JSON),
   NULL, 'medium', 'guideline', 'ESCCAP 犬猫蠕虫控制指南', 'https://www.esccap.org/guidelines/', 'GL1'),
  ('K-0007', 'antiparasitic', '驱虫后可能出现的反应', '轻度呕吐、腹泻、精神差多在 24 小时内缓解；持续呕吐或明显嗜睡要就医。',
   '驱虫后短时间内出现轻度呕吐、软便或精神稍差，通常与药物作用有关，多在 24 小时内缓解，可以少量多次给水并观察。出现持续呕吐、明显嗜睡、站立不稳、面部肿胀或抽搐，需要立即就医。不同个体对药物的耐受差异很大，反应重时不要自行加量或补服。',
   'all', 'all', NULL, 'yellow', 'medium', 'guideline', 'CAPC 寄生虫控制指南', 'https://www.capcvet.org/guidelines/', '2026 在线版'),
  ('K-0008', 'antiparasitic', '体内寄生虫的常见感染途径', '吞入虫卵、捕食中间宿主、母体传播是主要途径；幼年动物与户外活动多的个体风险更高。',
   '蛔虫、钩虫等可通过吞入环境中的虫卵或幼虫感染；绦虫常通过捕食跳蚤、鼠类或生肉传播；部分寄生虫还可以经母体传给幼崽。因此幼年动物、经常在户外草地活动或有捕食行为的个体风险更高。定期驱虫与及时清理粪便既保护宠物，也降低环境中的人畜共患风险。',
   'all', 'all', NULL, NULL, 'medium', 'guideline', 'ESCCAP 犬猫蠕虫控制指南', 'https://www.esccap.org/guidelines/', 'GL1'),

  -- ---- 症状分诊（L3）----
  ('K-0010', 'triage', '犬猫呕吐的家庭观察要点', '记录次数、性状与时间，单次呕吐可先观察；反复呕吐、带血、喝水都吐要立即就医。',
   '呕吐后先做三件事：记录呕吐次数与时间、观察呕吐物性状（食物、黄水、泡沫、是否带血）、观察精神与饮水情况。单次呕吐且精神正常时，通常可以先禁食数小时后少量给水再逐步恢复进食。反复呕吐、呕吐物带血或呈咖啡色、喝水都吐、伴随腹部明显膨大或精神沉郁，属于需要立即就医的情况。家庭观察期间不要自行给药。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0011', 'triage', '犬猫腹泻的家庭观察要点', '注意大便性状与颜色；带血、柏油样、持续超过 24 小时或伴随呕吐要就医。',
   '腹泻首先要看性状（成形、糊状、水样）与颜色，并记录次数。饮食变化、换粮、着凉都可能引起短暂软便；少量多餐、保证饮水、暂时避免油腻食物通常有助于恢复。大便带血、呈柏油样、腹泻持续超过 24 小时、伴随反复呕吐或明显精神沉郁，需要尽快就医。幼年动物腹泻脱水进展很快，不要在家久等。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0012', 'triage', '精神不振与食欲下降怎么判断', '先观察饮水与排尿是否正常；幼宠、老年宠或伴随呕吐、呼吸异常要尽快就医。',
   '精神不振与食欲下降是很多问题的共同表现，本身不指向某一种疾病。可以先观察：饮水是否正常、排尿排便是否正常、有没有呕吐或腹痛表现（不愿被抱、弓背）、体温是否明显异常。幼年动物和老年动物对疾病的耐受差，出现精神沉郁就建议尽早就医；如果伴随呼吸急促、反复呕吐、无法站立，请立即送医。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0013', 'triage', '咳嗽与呼吸异常的区分', '偶发咳嗽可观察；张口呼吸、呼吸费力、牙龈发白或发紫属于急症，立即送医。',
   '偶发的咳嗽（比如喝水呛到、短暂干咳）可以先观察其频次与诱因。需要立即就医的信号包括：张口呼吸、呼吸明显费力、腹部随呼吸起伏明显、牙龈发白或发紫、安静状态下呼吸次数明显增快、咳嗽伴精神沉郁或发热。呼吸相关症状在短鼻品种（如法斗、巴哥、波斯猫）上进展更快，不要在家观察太久。',
   'all', 'all', NULL, 'red', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0014', 'triage', '皮肤瘙痒与脱毛的观察方法', '记录部位与进展；抓挠严重、皮肤破溃流脓或伴有明显异味时需就医检查。',
   '皮肤问题先记录：从哪个部位开始、是否对称、有没有红点、皮屑、结痂、异味，以及最近是否换粮、洗澡、外出草地或更换环境。寄生虫、真菌、细菌感染与过敏都可能表现为瘙痒与脱毛，外观上很难区分，需要现场检查（刮片、灯检等）。抓挠剧烈、皮肤破溃流脓、耳道有异味或分泌物增多时，建议尽快就医，不要自行使用人用药膏。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0015', 'triage', '跛行与不愿走动的观察', '先看是突发还是渐进；突发不能负重、后肢无力或伴疼痛叫喊要立即就医。',
   '观察跛行先看三点：是突然出现还是逐渐加重、是一条腿还是多条、是否愿意把脚放地承重。关节或软组织损伤、指甲损伤（常被忽略，可检查脚垫与指甲）、椎间盘问题都可能表现跛行。突然不能负重、后肢无力或拖行、触摸时明显疼痛叫喊、伴随呕吐或大小便异常，需要立即就医：后肢突然瘫痪可能是血栓或椎间盘急症。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0016', 'triage', '耳朵问题的常见表现', '频繁甩头、抓耳、耳道有异味或分泌物提示耳道问题，需要现场检查。',
   '频繁甩头、反复抓耳、耳道内有深色分泌物或异味、触碰耳根时抗拒，都提示耳道可能有感染或异物。耳道问题的原因（寄生虫、真菌、细菌、过敏）在外观上很难区分，需要用耳镜与采样检查；自行用棉签深入清理或使用人用药水容易加重损伤。耳道内出现明显肿胀、头部持续歪斜或伴随平衡异常时，建议尽快就医。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0017', 'triage', '眼睛发红与分泌物增多的处理', '不要自行冲洗或涂药；眼球突出、角膜发白、无法睁眼要立即就医。',
   '眼睛发红、流泪或分泌物增多时，可以先用干净的湿棉片轻轻清理眼角分泌物，并注意是否伴随畏光、频繁眨眼或角膜混浊。**不要自行使用眼药水或人用眼膏**，也不要用自来水冲洗。眼球突出、角膜发白、明显疼痛（不让碰、用爪子抓眼）、无法睁眼或眼内有异物，属于需要立即就医的情况：眼球外伤处理越早，保住视力的机会越大。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),

  -- ---- 疾病（L3，不承担判定）----
  ('K-0020', 'disease', '犬细小病毒感染的常见表现', '幼犬高发：呕吐、腹泻（可能带血）、精神沉郁与不吃；疑似即需立即就医。',
   '犬细小病毒多见于未完成免疫的幼犬，典型表现是突然精神沉郁、食欲废绝、反复呕吐与腹泻（腹泻可能带血并有明显异味）。病程进展快，脱水与电解质紊乱可以在数小时内加重，因此**疑似就要立即就医**，不要在家观察或自行喂药。预防手段是按时完成幼犬免疫程序并减少未免疫期间的公共场合暴露。',
   'dog', 'puppy_kitten', NULL, 'red', 'high', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0021', 'disease', '猫瘟（猫泛白细胞减少症）的常见表现', '幼猫高发：发热、精神沉郁、不吃、呕吐；疑似立即就医，且需隔离其他猫。',
   '猫瘟由猫细小病毒感染，幼猫与未免疫的猫风险最高，表现包括发热、精神沉郁、食欲废绝、呕吐，部分个体出现腹泻。病情进展快，需要住院支持治疗，**疑似就要立即就医**。家中有多只猫时应立即隔离并做好环境消毒，同时告知兽医师家中其他猫的情况。预防同样依靠完整的幼猫免疫程序。',
   'cat', 'all', NULL, 'red', 'high', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0022', 'disease', '急性胃肠炎的常见诱因', '换粮过快、误食、着凉、感染都可能引起；持续呕吐或带血要就医。',
   '急性胃肠炎的常见诱因包括饮食突然变化、进食不易消化的食物、误食异物、着凉与病原感染。表现多为呕吐、腹泻与食欲下降，轻重差别很大。轻症在调整饮食与观察后可以好转，但**出现持续呕吐、便血、明显腹痛、精神沉郁或幼年/老年动物必须就医**——这些表现也可能来自更严重的问题（异物梗阻、胰腺炎、传染病）。',
   'all', 'all', NULL, 'yellow', 'medium', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0023', 'disease', '慢性肾病在猫身上的早期信号', '多饮多尿、体重下降、食欲下降与口臭是常见早期线索，需要血检与尿检确认。',
   '慢性肾病在中老年猫中较常见，早期信号包括饮水量与尿量增加、体重逐渐下降、食欲下降、毛发光泽变差与口臭。这些表现并不特异，**要确认需要血液与尿液检查**，单凭外观无法判断。日常照护上，保证充足饮水、定期体检（尤其 7 岁以上）与按兽医师建议调整饮食是主要手段。',
   'cat', 'senior', NULL, 'yellow', 'medium', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0024', 'disease', '犬胰腺炎的常见表现与风险因素', '反复呕吐、腹痛、弓背与食欲废绝；高脂饮食与肥胖是常见诱因。',
   '犬胰腺炎常表现为反复呕吐、明显腹痛（弓背、不愿被抱、腹部紧张）、食欲废绝与精神沉郁，症状与胃肠炎相似但更重。高脂饮食（包括节日剩菜、肥肉）、肥胖、某些药物与代谢问题是已知风险因素。**疑似应尽快就医**：胰腺炎需要血液检查确认，并可能需要输液与禁食管理，家庭处理容易延误。',
   'dog', 'all', NULL, 'yellow', 'medium', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0025', 'disease', '老年犬猫的关节退行性变化', '起步僵硬、跳不上高处、不愿上下楼梯是常见信号；需现场检查确认。',
   '随着年龄增长，关节软骨退行性变化在中老年犬猫中都常见。信号包括起身僵硬（活动后减轻）、跳不上原来能跳的高处、不愿上下楼梯、舔舐关节部位、活动量下降。体重管理与适度活动对缓解有帮助，但疼痛管理必须由兽医师决定——**人用止痛药对犬猫有明确毒性风险**，不要自行给药。',
   'all', 'senior', NULL, 'yellow', 'medium', 'textbook', '兽医内科学教材（工程整理，待复核）', NULL, '待复核摘录'),

  -- ---- 行为（L3；行为异常也可能指向疾病）----
  ('K-0030', 'behavior', '猫乱排泄可能的原因', '先排除疾病（泌尿与消化道问题），再看猫砂盆、环境变化与应激因素。',
   '猫在猫砂盆外排泄的原因分两大类：**身体原因**（泌尿系统问题、疼痛、消化道不适、老年认知变化）与**行为原因**（猫砂盆过脏或位置不佳、数量不足、猫砂类型改变、搬家或新成员带来的应激）。排尿困难或频繁进砂盆却尿不出属于急症，需要立即就医；排除身体问题后，再调整环境（猫砂盆数量为猫数加一、位置安静、避免频繁更换猫砂）。',
   'cat', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0031', 'behavior', '犬分离焦虑的常见表现', '独处时持续吠叫、破坏门窗、排泄与自伤；需要行为评估而不是惩罚。',
   '分离焦虑的典型表现是主人离开后不久出现持续吠叫或嚎叫、抓挠门窗、在室内排泄、破坏物品甚至自伤，回家时情绪异常激动。惩罚通常会让问题加重。改善需要逐步脱敏（从短时间离开开始）与建立固定的出门/回家流程，必要时由兽医师评估是否存在需要干预的焦虑问题与其他疾病（甲状腺、疼痛等也会表现为不安）。',
   'dog', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0032', 'behavior', '突然的攻击行为要先排查疼痛与疾病', '原本温顺的动物突然攻击，常见于疼痛、感官退化或内分泌问题，需就医评估。',
   '原本温顺的动物突然出现攻击或易怒，首先要考虑**身体原因**：局部疼痛（牙病、关节炎、耳道问题）、视力或听力退化导致的惊吓反应、内分泌或神经系统问题都会改变行为。这类情况下去做「行为训练」往往无效，正确顺序是先就医排查身体状况，再由兽医师评估是否需要行为干预。家中有小孩时，应先做好隔离等安全措施。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),

  -- ---- 营养（L1：毒物清单是安全门）----
  ('K-0040', 'nutrition', '对犬猫有毒的食物清单（安全门）', '巧克力、木糖醇、葡萄与葡萄干、洋葱与大葱、酒精、夏威夷果等对犬猫有毒性风险，误食立即就医。',
   '已知对犬猫有明确毒性风险的食物包括：巧克力（可可碱）、含木糖醇的甜食与口香糖、葡萄与葡萄干、洋葱与大蒜等葱属植物、酒精、夏威夷果、生面团、发霉食物。**误食后不要自行催吐**（腐蚀性物质或尖锐物催吐会造成二次伤害），立即联系兽医并带上包装或剩余食物。毒性反应出现的时间从数十分钟到数天不等，没有立刻症状不代表安全。',
   'all', 'all',
   CAST('{"kind":"toxic_food_list","items":[{"name":"巧克力","risk":"高","note":"可可碱与咖啡因"},{"name":"木糖醇","risk":"高","note":"引起低血糖与肝损伤"},{"name":"葡萄与葡萄干","risk":"高","note":"可致急性肾损伤"},{"name":"洋葱与大葱","risk":"高","note":"可致溶血性贫血"},{"name":"酒精","risk":"高"},{"name":"夏威夷果","risk":"中"},{"name":"生面团","risk":"中","note":"发酵产气并可产生酒精"},{"name":"发霉食物","risk":"高","note":"霉菌毒素"}],"action":"误食后立即就医，不要自行催吐"}' AS JSON),
   'red', 'high', 'web', 'ASPCA 动物毒物控制中心公开有毒植物与食物清单', 'https://www.aspca.org/pet-care/animal-poison-control', '2026 在线版'),
  ('K-0041', 'nutrition', '对猫有毒的植物与常见来源', '百合科植物（百合、铃兰、郁金香等）对猫剧毒，哪怕少量花粉或花瓶水也可能造成肾损伤。',
   '百合科植物（百合、萱草、铃兰、郁金香、水仙等）对猫有明确毒性，摄入花瓣、花粉甚至花瓶里的水都可能引起急性肾损伤，需要立即就医。其他常见室内风险还包括部分观叶植物与精油类产品。家中养猫时，选植物前先确认是否安全；出现流口水、呕吐、精神差、排尿异常等表现且有接触史，按急症处理。',
   'cat', 'all',
   CAST('{"kind":"toxic_plant_list","items":[{"name":"百合（及百合科植物）","risk":"高","note":"花瓣、花粉、花瓶水均可能致病"},{"name":"铃兰","risk":"高"},{"name":"郁金香与水仙","risk":"中高"},{"name":"部分精油（如茶树、薄荷）","risk":"中"}],"action":"立即就医，说明接触的部位与时间"}' AS JSON),
   'red', 'high', 'web', 'ASPCA 动物毒物控制中心公开有毒植物与食物清单', 'https://www.aspca.org/pet-care/animal-poison-control', '2026 在线版'),
  ('K-0042', 'nutrition', '换粮的正确节奏', '换粮建议用 7 天左右逐步替换比例，突然换粮容易引起腹泻与呕吐。',
   '突然更换食物是引起短期腹泻与呕吐的常见原因。建议用大约 7 天时间逐步替换：前几天新粮占四分之一，之后逐步提高比例，同时观察大便性状与食欲。肠胃敏感的个体需要更慢的节奏。若在换粮期间出现持续呕吐、便血或精神沉郁，应就医而不是继续调整比例。',
   'all', 'all',
   CAST('{"kind":"food_transition","days":7,"note":"按天逐步替换比例，观察大便性状"}' AS JSON),
   NULL, 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0043', 'nutrition', '猫的饮水与湿粮偏好', '猫天生饮水少，湿粮与流动饮水有助于提高摄水量，与泌尿系统健康相关。',
   '猫的祖先来自干燥地区，天生口渴感较弱、饮水偏少。保证饮水的方法包括：提供湿粮、多放几个水碗、使用流动饮水器、把水碗放在远离猫砂盆的安静处。摄水量与泌尿系统健康有关，但出现排尿困难、频繁进砂盆却尿不出，属于**需要立即就医的急症**，不能靠多喝水解决。',
   'cat', 'all',
   CAST('{"kind":"hydration_advice","methods":["湿粮","多处水碗","流动饮水器"]}' AS JSON),
   NULL, 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),

  -- ---- 护理（L3）----
  ('K-0050', 'care', '洗澡频次与皮肤屏障', '犬一般 3–4 周一次，猫多数不需要频繁洗澡；过勤洗澡会破坏皮肤屏障。',
   '犬的洗澡频次一般 3–4 周一次，长毛或体味重的个体可以适当缩短；猫多数情况下能自行清洁，通常不需要频繁洗澡。过勤洗澡会破坏皮肤屏障，反而增加皮肤问题。洗澡要用宠物专用洗剂，水温接近体温，洗后彻底吹干（尤其耳道与趾间）。皮肤有破溃、结痂或明显瘙痒时先就医，不要用洗澡代替治疗。',
   'all', 'all', NULL, NULL, 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0051', 'care', '指甲与趾间护理', '指甲过长会影响行走与关节，趾间潮湿易滋生细菌与真菌。',
   '指甲过长会改变着力点，长期可能影响关节与步态；行走时听到指甲敲击地面的声音通常提示该修剪了。修剪时注意避开血线，剪到有粉色区域前就停手，宁少勿多。趾间的毛发与潮湿环境容易滋生细菌与真菌，外出或洗澡后应擦干；出现红肿、异味或舔舐频繁要就医。',
   'all', 'all', NULL, NULL, 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),
  ('K-0052', 'care', '口腔护理与牙结石', '口臭、牙龈发红与牙结石提示牙周问题；需现场检查，必要时洗牙。',
   '口臭、牙龈边缘发红、牙面出现黄褐色沉积、进食时偏侧咀嚼或掉食，都提示可能存在牙周问题。牙周病不仅影响口腔，还与心脏、肾脏等系统性疾病相关。日常可用宠物专用牙刷与牙膏逐步建立刷牙习惯，但**已经形成的牙结石需要现场检查并处理**，不要自行在家刮除。老年动物麻醉风险评估由兽医师决定。',
   'all', 'all', NULL, 'yellow', 'medium', 'vet_input', '合作兽医审核稿（工程整理，待复核）', NULL, '待复核稿 v1'),

  -- ---- 急救（L1：步骤序列，顺序敏感）----
  ('K-0060', 'first_aid', '误食毒物的现场处理原则', '带上包装与呕吐物立即送医，不要自行催吐，不要喂牛奶或油。',
   '发现误食后：①立刻移开剩余毒物，防止继续摄入；②保留包装、说明书或呕吐物样本一同带去；③**不要自行催吐**，是否催吐必须由兽医判断（腐蚀性物质、尖锐异物催吐会造成二次伤害）；④不要喂牛奶、油或所谓解毒偏方；⑤立即联系或前往最近的医院，说明吃了什么、大致数量与时间。',
   'all', 'all',
   CAST('{"kind":"first_aid_steps","steps":["移开剩余毒物","保留包装与样本","不自行催吐","不喂牛奶或偏方","立即送医并说明摄入物、数量与时间"]}' AS JSON),
   'red', 'high', 'textbook', '兽医急救手册（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0061', 'first_aid', '外伤出血的现场止血', '用干净纱布直接压迫止血并立即送医；不要把深刺入的异物拔出。',
   '外伤出血的现场处理：①用干净纱布或毛巾**直接压迫**出血部位，持续按压不要反复掀开查看；②四肢伤口可在近心端加压包扎；③如果异物（玻璃、铁丝）深刺入体内，**不要拔出**，围绕异物加压固定后送医；④不要涂抹药粉、牙膏等。止血的同时立即送医，注意保持动物安静、保暖，并防止被疼痛中的动物咬伤。',
   'all', 'all',
   CAST('{"kind":"first_aid_steps","steps":["干净纱布直接压迫","近心端加压包扎","深刺异物不拔出","不涂药粉","立即送医"]}' AS JSON),
   'red', 'high', 'textbook', '兽医急救手册（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0062', 'first_aid', '抽搐发作时的现场应对', '不要把手伸进嘴里，移开周围硬物，记录持续时间并立即送医。',
   '抽搐发作时：①**不要把手伸进动物嘴里**，它无法咬住自己的舌头，而你会被咬伤；②移开周围硬物与家具，垫软物保护头部；③不要强行按住或呼喊，保持环境安静；④记录开始时间、持续时长与表现（是否口吐白沫、是否失禁），可以用手机录像；⑤发作停止后立即送医。若抽搐持续超过数分钟或短时间内反复发作，属于急症，途中保持呼吸道通畅。',
   'all', 'all',
   CAST('{"kind":"first_aid_steps","steps":["不把手伸进嘴里","移开硬物并垫软物保护头部","不强按不呼喊","记录时长与表现","立即送医"]}' AS JSON),
   'red', 'high', 'textbook', '兽医急救手册（工程整理，待复核）', NULL, '待复核摘录'),

  -- ---- 药物（L1：只讲禁忌与原则，不给剂量）----
  ('K-0070', 'drug', '人用药为什么不能给宠物吃', '对乙酰氨基酚对猫剧毒、布洛芬对犬猫都可能造成肾与消化道损伤；用药必须由兽医决定。',
   '人用药物不能按人用量折算给宠物：对乙酰氨基酚对猫是明确剧毒（可引起高铁血红蛋白血症与肝损伤），布洛芬等非甾体抗炎药对犬猫都可能造成消化道溃疡与肾损伤，部分抗生素与感冒药中的成分对犬猫也有毒性。**是否用药、用哪种、用多少，必须由兽医根据体重与身体状况决定**，误食人用药按急症处理并带上药盒。',
   'all', 'all',
   CAST('{"kind":"drug_contraindication_summary","note":"不给用药建议；误食按急症处理","examples":[{"name":"对乙酰氨基酚","species":"cat","risk":"剧毒"},{"name":"布洛芬","species":"all","risk":"消化道与肾损伤"}]}' AS JSON),
   'red', 'high', 'label', '药品说明书与公开发布的药物安全资料（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0071', 'drug', '用药前要告诉兽医的信息', '同时使用的其他药物、既往病史、是否怀孕或哺乳、体重与最近一次进食时间。',
   '就诊或咨询用药前，准备好这些信息对安全很重要：目前正在使用的所有药物与保健品（包括体外驱虫药）、既往病史（肝肾疾病、癫痫、心脏问题等）、是否怀孕或哺乳、准确的体重（不是估计值）、最近一次进食与排便情况。多药并用时的相互作用需要兽医判断；不同药之间可能需要间隔时间。',
   'all', 'all',
   CAST('{"kind":"medication_history_checklist","items":["在用药物与保健品","既往病史","怀孕或哺乳","准确体重","最近进食与排便"]}' AS JSON),
   NULL, 'high', 'label', '药品说明书与公开发布的用药安全资料（工程整理，待复核）', NULL, '待复核摘录'),

  -- ---- 品种（F024 的品种库；L1 的结构化部分是体型与易感病清单）----
  ('K-0080', 'breed', '短鼻（短头颅）犬种的气道特点', '法斗、巴哥、英斗、波斯猫等短鼻品种气道狭窄，耐热差，呼吸症状进展更快。',
   '短头颅（短鼻）品种包括法斗、巴哥、英斗、波士顿梗等犬，以及波斯猫、异国短毛猫等猫。它们的气道结构狭窄，容易出现打呼、运动耐力差、怕热，麻醉与高温环境下的风险也更高。这类个体出现张口呼吸、呼吸费力、舌色发紫、剧烈干呕时，**病情进展比一般品种快，要立即就医**。日常注意控制体重、避免高温时段外出与使用颈圈压迫气道。',
   'all', 'all',
   CAST('{"kind":"breed_trait","trait":"brachycephalic","risks":["气道狭窄","耐热差","麻醉风险高"]}' AS JSON),
   NULL, 'medium', 'textbook', '犬猫品种标准与兽医教科书（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0081', 'breed', '大型犬与深胸犬的胃扭转风险', '大型、深胸犬种（德牧、大丹、金毛等）进食后剧烈活动是胃扭转的已知诱因。',
   '胃扩张扭转（GDV）多见于大型与深胸犬种（大丹、德牧、金毛、拉布拉多、圣伯纳等），进食过快、进食后立即剧烈活动、一次性大量进食与饮水是已知诱因。表现是干呕但吐不出东西、腹部快速膨大发硬、焦躁不安、流口水，属于**分钟级急症，必须立即送医**。日常预防包括少食多餐、使用慢食碗、饭后一小时内避免剧烈运动。',
   'dog', 'all',
   CAST('{"kind":"breed_trait","trait":"gdv_prone","breeds":["大丹","德牧","金毛","拉布拉多","圣伯纳"],"action":"腹部快速膨大伴干呕 = 立即送医"}' AS JSON),
   'red', 'medium', 'textbook', '犬猫品种标准与兽医教科书（工程整理，待复核）', NULL, '待复核摘录'),
  ('K-0082', 'breed', '柯利犬系与伊维菌素类药物的敏感性', '柯利犬及部分牧羊犬携带 MDR1 基因变异，对伊维菌素等药物异常敏感。',
   '柯利犬、喜乐蒂、边境牧羊犬、澳大利亚牧羊犬等牧羊犬系中，一部分个体携带 MDR1（ABCB1）基因变异，对伊维菌素等药物的血脑屏障外排功能下降，常规用量下就可能出现神经系统中毒表现（共济失调、流口水、抽搐）。**用药前应告知兽医师犬种与血统**，是否需要基因检测与替代方案由兽医师决定。出现用药后步态不稳、精神异常，立即就医。',
   'dog', 'all',
   CAST('{"kind":"breed_trait","trait":"mdr1_sensitive","breeds":["柯利犬","喜乐蒂","边牧","澳牧"],"action":"用药前告知兽医师犬种，由兽医师决定替代方案"}' AS JSON),
   NULL, 'high', 'textbook', '犬猫品种标准与兽医教科书（工程整理，待复核）', NULL, '待复核摘录');
