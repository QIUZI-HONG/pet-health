-- 知识库 L2 关系层（切片 #100；建模见 63 号调研 §5，决策见 ADR-0022）
--
-- 它存「条目之间显式且可审核的关系」：一张节点表 + 一张边表，**不引入图数据库**（ADR-0003 已删 Neo4j）。
-- 本期它承担三件事，按价值排序：
--
-- 1) **安全门**（最重要）：`contraindicated_for` 是**检索结果的硬过滤**——命中某条条目时，
--    若条目文本提到对当前物种禁忌的药物，整条从上下文与引用里剔除。向量相似度拦不住
--    「猫用对乙酰氨基酚」这类推荐，一张审核过的边表可以（63 号调研 §5.3 收益 1）。
-- 2) **召回补齐**：关键词检索覆盖不了「换一种说法」（ADR-0022 承认的弱点）。用户说「不吃东西」，
--    条目写「食欲下降」——靠 `may_indicate` 边把症状节点引到疾病条目上，比让词表无限膨胀可控。
-- 3) **可校对的排序**：`weight` 是人工维护的原因排序依据，出问题时改数据而不是改提示词。
--
-- `evidence_entry_id` **强制有值**：任何一条边都必须能追到来源条目。没来源的边不允许存在，
-- 这条约束比任何检索算法都更能压住幻觉（63 号调研 §5.1 的建模约定）。
--
-- 边的引用用条目 code（K-xxxx）而不是自增 id：种子里 id 各环境不同，code 才是跨环境可比的那个。

CREATE TABLE `knowledge_node` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `node_type`     VARCHAR(24)     NOT NULL COMMENT 'symptom/disease/drug/food/parasite/breed/species/condition',
  `name`          VARCHAR(120)    NOT NULL COMMENT '规范化名称（走受控词典）',
  `alias`         JSON                     DEFAULT NULL COMMENT '别名与口语说法（JSON 数组），查询前先归一：吐/呕吐/不吃东西',
  `code`          VARCHAR(32)     NOT NULL COMMENT '对外编号，边表引用它',
  `entry_code`    VARCHAR(32)              DEFAULT NULL COMMENT '该节点自己的解释条目（K-xxxx），可空',
  `review_status` VARCHAR(16)     NOT NULL DEFAULT 'pending_review' COMMENT '两级：pending_review（未复核）/ vetted（兽医复核过），见 ADR-0033',
  `reviewed_by`   VARCHAR(64)              DEFAULT NULL,
  `remark`        VARCHAR(256)             DEFAULT NULL,
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_type_name` (`node_type`, `name`),
  KEY `idx_type` (`node_type`, `enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识关系层节点（薄节点：实体详情仍在条目表）';

CREATE TABLE `knowledge_edge` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `src_code`          VARCHAR(32)     NOT NULL COMMENT 'knowledge_node.code（源）',
  `dst_code`          VARCHAR(32)     NOT NULL COMMENT 'knowledge_node.code（目标）',
  `relation`          VARCHAR(32)     NOT NULL COMMENT 'may_indicate/treated_by/contraindicated_for/approved_for/escalates_to/differential_with/predisposed_to/toxic_to/targets/protects_against',
  `species_scope`     VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'dog / cat / all',
  `age_stage_scope`   VARCHAR(16)     NOT NULL DEFAULT 'all',
  `weight`            DECIMAL(4,3)             DEFAULT NULL COMMENT '仅 may_indicate：0~1，人工维护的排序权重',
  `urgency`           VARCHAR(8)               DEFAULT NULL COMMENT '仅 may_indicate：green/yellow/red',
  `note`              VARCHAR(255)             DEFAULT NULL COMMENT '展示用的一句话解释，如「大型犬腹胀伴干呕需警惕胃扭转」',
  `evidence_entry_code` VARCHAR(32)   NOT NULL COMMENT '来源条目 K-xxxx，**强制可追溯**：没来源的边不允许存在',
  `review_status`     VARCHAR(16)     NOT NULL DEFAULT 'pending_review',
  `enabled`           TINYINT         NOT NULL DEFAULT 1,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_edge` (`src_code`, `dst_code`, `relation`, `species_scope`),
  KEY `idx_src_rel` (`src_code`, `relation`, `enabled`, `is_deleted`),
  KEY `idx_dst_rel` (`dst_code`, `relation`, `enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识关系层边（症状→疾病→药物等，全部人工审核）';

-- ---------------------------------------------------------------- 节点种子
--
-- 物种节点是**安全门的靶**（contraindicated_for 指向它），所以它必须有确定的名字。
INSERT INTO `knowledge_node` (`code`, `node_type`, `name`, `alias`, `entry_code`, `remark`) VALUES
  ('SP-DOG', 'species', '犬', JSON_ARRAY('狗', '狗狗', '汪星人'), NULL, '安全门与过滤用的物种节点'),
  ('SP-CAT', 'species', '猫', JSON_ARRAY('猫咪', '猫猫', '喵'), NULL, '安全门与过滤用的物种节点'),
  ('SYM-VOMIT', 'symptom', '呕吐', JSON_ARRAY('吐', '吐了', '反胃', '作呕', '吐黄水', '干呕'), 'K-0010', '口语变体放在 alias 里，查询前归一'),
  ('SYM-DIARRHEA', 'symptom', '腹泻', JSON_ARRAY('拉稀', '软便', '拉肚子', '大便不成形'), 'K-0011', ''),
  ('SYM-NOT-EATING', 'symptom', '食欲下降', JSON_ARRAY('不吃东西', '不吃饭', '没食欲', '食欲不振', '吃得少'), 'K-0012', '用户说「不吃东西」而条目写「食欲下降」，靠这条边补齐召回'),
  ('SYM-LETHARGY', 'symptom', '精神沉郁', JSON_ARRAY('没精神', '精神差', '蔫了', '不爱动', '一直睡'), 'K-0012', ''),
  ('SYM-COUGH', 'symptom', '咳嗽', JSON_ARRAY('干咳', '咳', '卡卡响'), 'K-0013', ''),
  ('SYM-BREATHING', 'symptom', '呼吸异常', JSON_ARRAY('呼吸急促', '喘不上气', '张口呼吸', '呼吸费力'), 'K-0013', ''),
  ('SYM-ITCH', 'symptom', '皮肤瘙痒', JSON_ARRAY('挠', '抓痒', '一直舔', '掉毛', '皮肤红'), 'K-0014', ''),
  ('SYM-LIMP', 'symptom', '跛行', JSON_ARRAY('瘸', '腿瘸', '不愿走', '走路不正常'), 'K-0015', ''),
  ('SYM-EAR', 'symptom', '耳道异常', JSON_ARRAY('甩头', '抓耳朵', '耳朵臭', '耳朵有味道'), 'K-0016', ''),
  ('SYM-EYE', 'symptom', '眼部异常', JSON_ARRAY('眼睛红', '眼屎多', '眼睛睁不开', '流泪'), 'K-0017', ''),
  ('DIS-GASTRO', 'disease', '急性胃肠炎', JSON_ARRAY('肠胃炎', '吃坏肚子'), 'K-0022', ''),
  ('DIS-PARVO', 'disease', '犬细小病毒感染', JSON_ARRAY('细小', '翻肠子'), 'K-0020', ''),
  ('DIS-FPL', 'disease', '猫瘟', JSON_ARRAY('猫泛白细胞减少症', '猫细小'), 'K-0021', ''),
  ('DIS-PANCREATITIS', 'disease', '胰腺炎', JSON_ARRAY('胰腺'), 'K-0024', ''),
  ('DIS-CKD', 'disease', '慢性肾病', JSON_ARRAY('肾衰', '肾功能不全'), 'K-0023', ''),
  ('DIS-ARTHRITIS', 'disease', '关节退行性变化', JSON_ARRAY('关节炎', '关节痛'), 'K-0025', ''),
  ('DIS-UTI', 'disease', '下泌尿道疾病', JSON_ARRAY('尿闭', '尿不出', '膀胱炎'), NULL, '猫尿闭属急症：命中即走红线（RF-012），这里只用于召回'),
  ('DRUG-ACETAMINOPHEN', 'drug', '对乙酰氨基酚', JSON_ARRAY('扑热息痛', '泰诺', 'paracetamol', 'acetaminophen', 'tylenol', '退烧药'), 'K-0070', '对猫剧毒：安全门的首要靶点'),
  ('DRUG-IBUPROFEN', 'drug', '布洛芬', JSON_ARRAY('芬必得', 'ibuprofen', 'advil', 'motrin', '止痛药'), 'K-0070', '犬猫都可能造成消化道与肾损伤'),
  ('DRUG-IVERMECTIN', 'drug', '伊维菌素', JSON_ARRAY('ivermectin', '阿维菌素'), 'K-0082', 'MDR1 变异犬种敏感'),
  ('FOOD-CHOCOLATE', 'food', '巧克力', JSON_ARRAY('可可', '黑巧'), 'K-0040', ''),
  ('FOOD-XYLITOL', 'food', '木糖醇', JSON_ARRAY('无糖口香糖', 'xylitol'), 'K-0040', ''),
  ('FOOD-GRAPE', 'food', '葡萄与葡萄干', JSON_ARRAY('葡萄', '葡萄干', '提子'), 'K-0040', ''),
  ('FOOD-ONION', 'food', '洋葱与大葱', JSON_ARRAY('洋葱', '大葱', '蒜', '葱'), 'K-0040', ''),
  ('FOOD-LILY', 'food', '百合科植物', JSON_ARRAY('百合', '铃兰', '郁金香', '水仙'), 'K-0041', '对猫剧毒，哪怕花粉或花瓶水'),
  ('COND-GDV', 'condition', '胃扩张扭转', JSON_ARRAY('胃扭转', 'GDV', '胀气'), 'K-0081', '大型深胸犬高发；与 RF-009 交叉校验'),
  ('BREED-BRACHY', 'breed', '短头颅品种', JSON_ARRAY('短鼻', '法斗', '巴哥', '英斗', '波斯猫'), 'K-0080', '气道狭窄、耐热差：呼吸症状进展快'),
  ('BREED-MDR1', 'breed', 'MDR1 敏感犬种', JSON_ARRAY('柯利犬', '喜乐蒂', '边牧', '澳牧'), 'K-0082', '伊维菌素等药物敏感');

-- ---------------------------------------------------------------- 边种子
--
-- 这一批边按 63 号调研 §5.3 的三类收益各取几条：安全门（contraindicated_for / toxic_to）、
-- 召回（symptom may_indicate disease）、一致性校验（escalates_to 与红线交叉）。
-- 全部 `evidence_entry_code` 有值——没有来源的边不允许存在。
INSERT INTO `knowledge_edge`
  (`src_code`, `dst_code`, `relation`, `species_scope`, `weight`, `urgency`, `note`, `evidence_entry_code`) VALUES
  -- 安全门：药物 → 物种的禁忌（检索命中的条目若提到这些药，整条会被剔除）
  ('DRUG-ACETAMINOPHEN', 'SP-CAT', 'contraindicated_for', 'cat', NULL, NULL,
   '对乙酰氨基酚对猫是明确剧毒，可引起高铁血红蛋白血症与肝损伤', 'K-0070'),
  ('DRUG-ACETAMINOPHEN', 'SP-DOG', 'contraindicated_for', 'dog', NULL, NULL,
   '犬同样不可自行使用人用退烧药，剂量与适应证由兽医判断', 'K-0070'),
  ('DRUG-IBUPROFEN', 'SP-CAT', 'contraindicated_for', 'cat', NULL, NULL,
   '非甾体抗炎药对猫代谢慢，风险更高', 'K-0070'),
  ('DRUG-IBUPROFEN', 'SP-DOG', 'contraindicated_for', 'dog', NULL, NULL,
   '可致消化道溃疡与肾损伤', 'K-0070'),
  ('DRUG-IVERMECTIN', 'BREED-MDR1', 'contraindicated_for', 'dog', NULL, NULL,
   'MDR1 基因变异犬种在常规剂量下就可能出现神经毒性', 'K-0082'),
  -- 毒物 → 物种（安全门第二组：营养类的误食场景）
  ('FOOD-LILY', 'SP-CAT', 'toxic_to', 'cat', NULL, NULL,
   '百合科植物对猫剧毒，花瓣、花粉与花瓶水都可致病', 'K-0041'),
  ('FOOD-CHOCOLATE', 'SP-DOG', 'toxic_to', 'dog', NULL, NULL, '可可碱中毒风险', 'K-0040'),
  ('FOOD-XYLITOL', 'SP-DOG', 'toxic_to', 'dog', NULL, NULL, '引起低血糖与肝损伤', 'K-0040'),
  ('FOOD-GRAPE', 'SP-DOG', 'toxic_to', 'dog', NULL, NULL, '可致急性肾损伤', 'K-0040'),
  ('FOOD-ONION', 'SP-CAT', 'toxic_to', 'cat', NULL, NULL, '葱属植物可致溶血性贫血', 'K-0040'),
  ('FOOD-ONION', 'SP-DOG', 'toxic_to', 'dog', NULL, NULL, '葱属植物可致溶血性贫血', 'K-0040'),
  -- 召回：症状 → 疾病（weight 是人工维护的排序依据）
  ('SYM-VOMIT', 'DIS-GASTRO', 'may_indicate', 'all', 0.800, 'yellow', '饮食变化或误食后最常见的方向', 'K-0022'),
  ('SYM-VOMIT', 'DIS-PANCREATITIS', 'may_indicate', 'dog', 0.600, 'yellow', '反复呕吐伴腹痛与弓背时要考虑', 'K-0024'),
  ('SYM-VOMIT', 'DIS-PARVO', 'may_indicate', 'dog', 0.700, 'red', '未完成免疫的幼犬反复呕吐要优先排除', 'K-0020'),
  ('SYM-VOMIT', 'DIS-FPL', 'may_indicate', 'cat', 0.700, 'red', '未免疫幼猫呕吐伴精神沉郁要优先排除', 'K-0021'),
  ('SYM-DIARRHEA', 'DIS-GASTRO', 'may_indicate', 'all', 0.800, 'yellow', '常见方向，先看性状与脱水程度', 'K-0022'),
  ('SYM-DIARRHEA', 'DIS-PARVO', 'may_indicate', 'dog', 0.700, 'red', '幼犬血便伴精神沉郁属急症方向', 'K-0020'),
  ('SYM-DIARRHEA', 'DIS-FPL', 'may_indicate', 'cat', 0.600, 'red', '幼猫腹泻伴发热要排除猫瘟', 'K-0021'),
  ('SYM-NOT-EATING', 'DIS-CKD', 'may_indicate', 'cat', 0.500, 'yellow', '中老年猫食欲下降伴多饮多尿要查肾', 'K-0023'),
  ('SYM-NOT-EATING', 'DIS-GASTRO', 'may_indicate', 'all', 0.600, 'yellow', '短期食欲下降常见于胃肠道不适', 'K-0022'),
  ('SYM-LETHARGY', 'DIS-CKD', 'may_indicate', 'cat', 0.450, 'yellow', '慢性肾病的早期信号之一是精神与食欲变化', 'K-0023'),
  ('SYM-LETHARGY', 'DIS-PARVO', 'may_indicate', 'dog', 0.550, 'red', '幼犬精神沉郁要先排除传染病', 'K-0020'),
  ('SYM-LIMP', 'DIS-ARTHRITIS', 'may_indicate', 'all', 0.600, 'yellow', '中老年动物渐进性跛行要考虑关节退变', 'K-0025'),
  ('SYM-BREATHING', 'COND-GDV', 'may_indicate', 'dog', 0.500, 'red', '腹胀伴干呕与呼吸费力要警惕胃扭转', 'K-0081'),
  ('SYM-EAR', 'DIS-ARTHRITIS', 'differential_with', 'all', NULL, NULL, '耳道问题与关节疼痛都可能表现为甩头/抓挠，需要现场区分', 'K-0016'),
  -- 升级到红线（一致性校验的抓手：标红的症状必须能被红线规则命中）
  ('SYM-BREATHING', 'COND-GDV', 'escalates_to', 'dog', NULL, 'red', '呼吸异常 + 腹部膨大 = 立即送医', 'K-0081'),
  ('DIS-UTI', 'SP-CAT', 'escalates_to', 'cat', NULL, 'red', '猫尿闭是急症，超过 24 小时可致死', 'K-0030');
