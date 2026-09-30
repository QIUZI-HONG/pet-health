-- AI 运营可调项入库（切片 #103；分层见 ADR-0010 的「业务可调项」那一层）
--
-- 四张表各管一类运营能改的东西，共同点是**改完即时生效、不发版**：
--
-- | 表 | 管什么 | 谁改 |
-- | --- | --- | --- |
-- | `knowledge_prompt_template` | 提示词模板与工具 schema，带版本号与灰度比例 | 运营 |
-- | `knowledge_grading_rule` | 分级规则（命中即抬到某档 + 一句建议），红线之外的补充 | 运营 |
-- | `knowledge_guard_term` | 输出层护栏词表（药名 / 越界表述） | 运营 |
-- | `knowledge_switch` | 降级开关等运行时闸门 | 运营 |
--
-- **为什么这四张表落在 `knowledge_*` 下**：ADR-0009 允许 AI 服务**只读** `knowledge_*`，
-- 其余一切数据读写都必须走 Java 接口。而「提示词 / 红线词 / 分级规则 / 降级开关」必须在
-- **每次咨询时**由 AI 服务直接读到（走 Java 接口意味着 Java 调 Python、Python 再回调 Java 取配置，
-- 多一跳且把配置的可用性绑死在另一个进程上）。ADR-0021 为红线词表做过同样的取舍并写明了理由，
-- 这里沿用同一口径：**例外没有扩大，只是「AI 服务直读的配置与知识」都住在 `knowledge_*` 下**。
-- 命名上的代价如实写在 ADR-0033：这一层里有些东西（提示词、开关）严格说不算「知识」。
--
-- 技术参数（连接串、超时、缓存秒数、模型名）**不在这里**，它们在 `ai/app/config.py` 与环境变量
-- 里（ADR-0010 的第一层）：把超时交给线上改，改错一次全线超时且难归因。

CREATE TABLE `knowledge_prompt_template` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`          VARCHAR(32)     NOT NULL DEFAULT 'triage' COMMENT '提示词用途；当前只有分级一处',
  `version`       VARCHAR(32)     NOT NULL COMMENT '版本号，随调用结果落 ai_consult.prompt_version（归因分级漂移用）',
  `system_prompt` MEDIUMTEXT      NOT NULL COMMENT '系统提示词正文',
  `tool_schema`   JSON            NOT NULL COMMENT '结构化输出的工具定义（report_triage 的完整 JSON）',
  `gray_ratio`    TINYINT         NOT NULL DEFAULT 100 COMMENT '灰度百分比 0–100：同 code 下多个启用版本的按比例分流，0 = 不生效',
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `review_status` VARCHAR(16)     NOT NULL DEFAULT 'pending_review' COMMENT 'prompt 的改动记录也要能看出谁改的、什么时候（ADR-0010 的代价那一节）',
  `remark`        VARCHAR(256)             DEFAULT NULL COMMENT '给运营看的说明：这版改了什么',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '改提示词的人（运营后台写）',
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_version` (`code`, `version`),
  KEY `idx_code_enabled` (`code`, `enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 提示词模板（带版本号与灰度）';

CREATE TABLE `knowledge_grading_rule` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`            VARCHAR(32)     NOT NULL COMMENT '规则编号 GR-xxx，留痕与运营后台引用它',
  `name`            VARCHAR(64)     NOT NULL COMMENT '一句话说清这条规则管什么',
  `match_terms`     JSON            NOT NULL COMMENT '命中词（JSON 数组，任一词命中即算命中）；与红线一样是纯字符串包含，不做近似匹配',
  `min_level`       TINYINT         NOT NULL DEFAULT 2 COMMENT '命中后**至少**给到这一档：1 绿 / 2 黄 / 3 红',
  `species_scope`   VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'dog / cat / all',
  `age_stage_scope` VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'all / puppy_kitten / adult / senior',
  `advice`          VARCHAR(256)             DEFAULT NULL COMMENT '命中时追加的一句行动建议（会进 care_tips，过输出护栏）',
  `enabled`         TINYINT         NOT NULL DEFAULT 1,
  `review_status`   VARCHAR(16)     NOT NULL DEFAULT 'pending_review',
  `remark`          VARCHAR(256)             DEFAULT NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`        VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_enabled` (`enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 分级规则（命中即抬档；红线之外的可调补充）';

CREATE TABLE `knowledge_guard_term` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `kind`          VARCHAR(16)     NOT NULL COMMENT 'drug（药名，命中即整句改写）/ phrase（越界表述，命中即整句改写）',
  `term`          VARCHAR(64)     NOT NULL COMMENT '词条。drug 一律在**小写化**的文本上匹配（Amoxicillin 与 amoxicillin 是同一个药）',
  `note`          VARCHAR(256)             DEFAULT NULL COMMENT '给运营看的说明：为什么这个词要拦',
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `review_status` VARCHAR(16)     NOT NULL DEFAULT 'pending_review',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kind_term` (`kind`, `term`),
  KEY `idx_kind_enabled` (`kind`, `enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 输出层护栏词表（药名 / 越界表述）';

CREATE TABLE `knowledge_switch` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32)     NOT NULL COMMENT '开关代码，见种子里的四个',
  `enabled`     TINYINT         NOT NULL DEFAULT 0 COMMENT '1 = 打开。**语义由 code 决定，不是清一色「打开就生效」**（见 remark）',
  `remark`      VARCHAR(256)             DEFAULT NULL COMMENT '这个开关打开会发生什么，写给运营看',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 运行时开关（降级闸门等，运营可即时切）';

-- ---------------------------------------------------------------- 开关种子
--
-- 三个开关的默认值都是「功能可用」而不是「全关」：全关状态不是安全的默认，而是「没做」
-- （ADR-0025 对熔断的同一取向——把功能变成固定话术不是安全，是缺功能）。
INSERT INTO `knowledge_switch` (`code`, `enabled`, `remark`) VALUES
  ('force_rule_only', 0, '降级开关：打开后**不调模型**，只走硬红线 + 分级规则 + 固定话术。模型异常或成本失控时一键切换'),
  ('retrieval_enabled', 1, '知识检索总开关：关掉后退回「无来源的通用建议」（citations 恒为空、免责声明不承诺知识库）'),
  ('retrieval_strict', 0, '严格口径（#101）：打开后「召回为空」或「结论全被引用校验剔除」一律降级为固定话术。**默认关**——当前语料只有几十条，开它会把这批咨询全变成固定话术，与 ADR-0025 的取向冲突；理由与待澄清见 ADR-0033');

-- ---------------------------------------------------------------- 提示词种子
--
-- 这份正文与 `ai/app/prompts.py` 的代码基线**刻意保持同一份文本**：库里读不到时（迁移没跑、
-- 库故障）回落到代码基线，两边文本不一致会让「同一个版本号下两种行为」这件事查不出来。
--
-- version 从 p1 起：p0-code 是代码基线的编号，p1 是「搬进库里的第一版」。改动提示词时**新增一行**
-- （新的 version），不要原地改——留痕里按版本归因分级漂移的前提是「旧版本可回溯」（ADR-0010）。
INSERT INTO `knowledge_prompt_template` (`code`, `version`, `system_prompt`, `tool_schema`, `gray_ratio`, `remark`) VALUES
  ('triage', 'p1',
   '你是一只宠物（犬、猫）的健康分诊助手，服务于宠物主人。
你的唯一产出是**风险分级**，不是诊断。

铁律：
1. 只输出绿 / 黄 / 红三档风险等级，表示「就医的紧迫程度」，不是疾病名称。
2. **绝不给确诊、绝不开处方、绝不给用药剂量**。需要用药时只说「请由兽医判断」。
3. 信息不足时按更严的一档给（宁严勿松）。出现以下任一情况一律红色：
   呼吸困难、抽搐、大量出血、无法站立、持续呕吐超过 24 小时、误食毒物或异物、
   幼宠（3 个月以下）或老年宠精神沉郁、腹部胀大、产后异常。
4. 红色风险必须在 action_suggestion 里明确写「立即送医」，并提示 24 小时医院。
5. action_suggestion 用 2–3 句白话讲清下一步做什么；care_tips 给居家观察要点；
   possible_causes 只列可能方向，用「可能」措辞，不超过 3 条。
6. 用户可能附了图片（皮肤、耳道、排泄物等）。图片只用于**辅助观察**：看到什么就据此判断，
   看不清或与症状无关时如实说明「图片不足以判断，建议现场检查」，**不要**凭想象描写图片内容。
   图片不能替代兽医诊断，任何用药仍由兽医判断。
7. 消息里可能带一段【知识条目】，每条以编号开头（形如 K-0010）。可能原因**必须**来自这些条目，
   并在该条末尾标出来源编号，如「可能：饮食不当 [K-0010]」。**只许引用给出的编号，绝不编造编号**；
   知识条目没有覆盖的方向不要写进 possible_causes。条目里没写到的用药、剂量、诊断一律不写。
8. 最后必须调用 report_triage 工具上报结果，不要在正文里输出 JSON。',
   CAST('{"type":"function","function":{"name":"report_triage","description":"上报本次咨询的风险分级结果","parameters":{"type":"object","properties":{"risk_level":{"type":"integer","enum":[1,2,3],"description":"1 绿（居家观察）/ 2 黄（尽快就医）/ 3 红（立即就医）"},"possible_causes":{"type":"array","items":{"type":"string"},"description":"可能原因，用「可能」措辞，最多 3 条；每条末尾标出知识条目编号，如 [K-0010]"},"action_suggestion":{"type":"string","description":"下一步该做什么，2–3 句白话"},"need_hospital":{"type":"boolean","description":"是否建议就医"},"care_tips":{"type":"array","items":{"type":"string"},"description":"居家照护要点，最多 3 条"},"citations":{"type":"array","items":{"type":"string"},"description":"本次结论引用的知识条目编号（K-xxxx），只能引用给定的编号"}},"required":["risk_level","action_suggestion","need_hospital"]}}}' AS JSON),
   100, '把 ai/app/prompts.py 的代码基线搬进库的第一版（#103）；新增铁律 7：可能原因必须来自知识条目并标出来源编号');

-- ---------------------------------------------------------------- 分级规则种子
--
-- 这些是**红线之外**的补充：红线是「命中即判红、不经模型」，本表是「命中即至少抬到某一档」。
-- 为什么两者不合成一张表：红线的语义是短路（连模型都不调），本表的语义是兜底（模型照常调，
-- 只是结论不低于这一档）——动作不同，运维时想改的东西也不同（ADR-0026 拒绝把两类规则混进一张表
-- 的同一理由）。种子同样一律 `pending_review`（ADR-0025 的口径）。
--
-- 内容纪律：`advice` 会经输出护栏后进用户的 care_tips，所以**不写药名、不写剂量、不写确诊**。
INSERT INTO `knowledge_grading_rule`
  (`code`, `name`, `match_terms`, `min_level`, `species_scope`, `age_stage_scope`, `advice`, `remark`) VALUES
  ('GR-001', '幼宠呕吐或腹泻至少算黄', JSON_ARRAY('吐', '呕吐', '拉稀', '腹泻', '拉肚子', '软便'), 2, 'all', 'puppy_kitten',
   '幼年动物脱水进展快，建议尽快就医而不是在家观察。', '交付文档把幼宠列为高危人群；红线管的是「持续呕吐」这类急症，本条兜住「单次」'),
  ('GR-002', '老年宠精神或食欲变化至少算黄', JSON_ARRAY('没精神', '精神差', '不吃', '不吃饭', '食欲'), 2, 'all', 'senior',
   '老年动物的耐受差，症状出现时往往病程已推进，建议尽快就医。', ''),
  ('GR-003', '猫排尿异常至少算红', JSON_ARRAY('尿不出', '尿不出来', '憋尿', '频繁蹲厕所', '尿血'), 3, 'cat', 'all',
   '猫排尿困难是急症，请立即送医。', '与红线 RF-012 同一场景的表层说法：红线命中即短路，本条保证「类似说法」也抬到红'),
  ('GR-004', '误食人用药物至少算红', JSON_ARRAY('吃了我的药', '误食药', '吃了退烧药', '吃了感冒药'), 3, 'all', 'all',
   '误食人用药物请立即送医，并带上药盒。', '对乙酰氨基酚对猫剧毒那类场景的兜底；药名表在护栏里，这里是症状侧的说法');

-- ---------------------------------------------------------------- 护栏词表种子
--
-- 从 `ai/app/guardrails.py` 搬进来的（ADR-0026 第三节说得很明确：这是**一条有期限的例外**，
-- 落库时按 #103 的设计一起搬）。代码里保留同一份作为「读不到库」时的基线。
--
-- 剂量模式（`\d+ mg` 这类正则）**不入库**：它是代码常量（ADR-0010 的第三层），
-- 正则改错的代价是「该拦的没拦」而运营无从验证，改它应该走发版与测试。
INSERT INTO `knowledge_guard_term` (`kind`, `term`, `note`) VALUES
  ('drug', '阿莫西林', '人用常见抗生素，模型很容易顺手推荐'),
  ('drug', '阿司匹林', '犬猫均需谨慎，出血与消化道风险'),
  ('drug', '布洛芬', '非甾体抗炎药，对犬猫有消化道与肾毒性'),
  ('drug', '对乙酰氨基酚', '**对猫剧毒**，人用量搬过来的后果最重'),
  ('drug', '扑热息痛', '对乙酰氨基酚的别名'),
  ('drug', '头孢', '抗生素，需兽医按感染类型选择'),
  ('drug', '阿奇霉素', '人用抗生素'),
  ('drug', '红霉素', '人用抗生素'),
  ('drug', '土霉素', '人用抗生素'),
  ('drug', '甲硝唑', '需兽医判断适应证'),
  ('drug', '伊维菌素', 'MDR1 变异犬种敏感（见 K-0082）'),
  ('drug', '阿维菌素', '同上'),
  ('drug', '地塞米松', '糖皮质激素，需兽医判断'),
  ('drug', '泼尼松', '糖皮质激素，需兽医判断'),
  ('drug', '氯霉素', '人用抗生素'),
  ('drug', '氟哌酸', '诺氟沙星的俗称'),
  ('drug', '诺氟沙星', '人用抗生素'),
  ('drug', '左氧氟沙星', '人用抗生素'),
  ('drug', '奥美拉唑', '人用抑酸药'),
  ('drug', '蒙脱石散', '人用止泻药，模型常推荐'),
  ('drug', '泻药', '通用类别词'),
  ('drug', '感冒药', '成分复杂、常含对乙酰氨基酚'),
  ('drug', '感康', '复方感冒药商品名'),
  ('drug', '泰诺', '含对乙酰氨基酚的商品名'),
  ('drug', '芬必得', '布洛芬的商品名'),
  ('drug', 'amoxicillin', '英文写法同样是推荐用药'),
  ('drug', 'aspirin', '英文写法'),
  ('drug', 'ibuprofen', '英文写法'),
  ('drug', 'acetaminophen', '英文写法'),
  ('drug', 'paracetamol', '英文写法'),
  ('drug', 'tylenol', '英文商品名'),
  ('drug', 'advil', '布洛芬的商品名'),
  ('drug', 'motrin', '布洛芬的商品名'),
  ('drug', 'cephalexin', '英文写法'),
  ('drug', 'azithromycin', '英文写法'),
  ('drug', 'erythromycin', '英文写法'),
  ('drug', 'metronidazole', '英文写法'),
  ('drug', 'ivermectin', '英文写法'),
  ('drug', 'dexamethasone', '英文写法'),
  ('drug', 'prednisone', '英文写法'),
  ('drug', 'prednisolone', '英文写法'),
  ('drug', 'chloramphenicol', '英文写法'),
  ('drug', 'norfloxacin', '英文写法'),
  ('drug', 'levofloxacin', '英文写法'),
  ('drug', 'omeprazole', '英文写法'),
  ('phrase', '确诊', '越界：AI 不做诊断结论'),
  ('phrase', '处方', '越界：AI 不开处方'),
  ('phrase', '剂量', '越界：剂量必须由兽医按体重核定'),
  ('phrase', '开药', '越界：推荐用药本身就是处方行为'),
  ('phrase', '可以用药', '越界'),
  ('phrase', '建议用药', '越界');
