-- 硬红线规则表（切片 #98 的验收标准「硬红线命中直接判红，不经模型」；决策见 ADR-0021）
--
-- 它是**知识库 L1 结构化事实层的第一张表**（63 号调研 §1.1 的「红线规则表」）：
-- 放"命中即立即就医"的症状词，不走检索、不走模型，纯匹配。
--
-- 命名落在 `knowledge_*` 下是有意的：ADR-0009 允许 AI 服务**只读** `knowledge_*`，
-- 而匹配发生在 Python 服务里（docs/design/ai-service.md 的职责边界），
-- 这张表正好在例外范围内，不需要为它开第二个例外。
--
-- 与交付文档的偏离：文档 7.2 没有这张表（它把红线写进了提示词，9.5 只说"必须建议就医"）。
-- 我们把它做成表，理由是 ADR-0010：红线的增删是**业务可调**、且现场最频繁的一类改动
-- （新毒物、新疫情），写死在提示词里等于每次都要改代码发版。
--
-- 词表首版由工程按公开兽医急诊共识 + 交付文档 9.5 的四个高危症状（中暑/中毒/窒息/持续呕吐）构造，
-- `review_status` 一律 `pending_review`、`reviewed_by` 为空——**标注待兽医复核**（ADR-0021 的缺口）。
-- 但 `enabled` 是 1：红色是安全方向，宁可多召回一次，也不要因为"没人签字"而让这一层空转。

CREATE TABLE `knowledge_red_flag` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)     NOT NULL COMMENT '规则编号 RF-xxx，留痕与运营后台引用它',
  `pattern`          VARCHAR(64)     NOT NULL COMMENT '主词，如「中暑」',
  `variants`         JSON                     DEFAULT NULL COMMENT '同义词与口语变体（JSON 数组）。近义表达靠它覆盖，本期不做向量近似匹配',
  `species_scope`    VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'dog / cat / all——犬猫混答是这类产品最常见的错误来源',
  `age_stage_scope`  VARCHAR(16)     NOT NULL DEFAULT 'all' COMMENT 'all / puppy_kitten / adult / senior；阈值待兽医定稿（ADR-0021）',
  `level`            TINYINT         NOT NULL DEFAULT 3 COMMENT '命中后的风险等级：3 红（保留 2 以便将来加「黄线」）',
  `action_hint`      VARCHAR(256)    NOT NULL COMMENT '命中后给用户的第一句话，明确到「立刻做什么」',
  `enabled`          TINYINT         NOT NULL DEFAULT 1 COMMENT '运营总开关',
  `review_status`    VARCHAR(16)     NOT NULL DEFAULT 'pending_review' COMMENT 'pending_review / approved（红线要求双人复核，见 63 号调研）',
  `reviewed_by`      VARCHAR(64)              DEFAULT NULL,
  `remark`           VARCHAR(256)             DEFAULT NULL COMMENT '给运营看的说明：为什么这条算红线',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`         VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_enabled` (`enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '硬红线规则（命中即判红，不经模型）';

INSERT INTO `knowledge_red_flag`
  (`code`, `pattern`, `variants`, `species_scope`, `age_stage_scope`, `level`, `action_hint`, `remark`) VALUES
  ('RF-001', '中毒', '["误食","吃了老鼠药","吃了农药","吃了百合","吃了巧克力","木糖醇","杀鼠剂","有机磷"]', 'all', 'all', 3,
   '疑似中毒请立刻送医，不要自行催吐（腐蚀性物质催吐会造成二次伤害），把包装或呕吐物一起带上。',
   '毒物清单见 63 号调研的营养类目（L2 的 food toxic_to species 是它的结构化版本）'),
  ('RF-002', '中暑', '["热射病","暴晒后","大热天喘","体温烫手"]', 'all', 'all', 3,
   '立即移到阴凉处、用常温水淋湿身体并马上送医，不要用冰水。',
   '交付文档 9.5 点名的高危症状之一'),
  ('RF-003', '窒息', '["卡住","卡在喉咙","呼吸困难","喘不上气","张口呼吸","舌头变紫","牙龈发白"]', 'all', 'all', 3,
   '呼吸受阻是分钟级急症，立即送最近的 24 小时医院，路上保持头部低于胸部。',
   '交付文档 9.5 点名的高危症状之一'),
  ('RF-004', '抽搐', '["抽风","痉挛","癫痫发作","四肢僵直","口吐白沫"]', 'all', 'all', 3,
   '抽搐时不要把手伸进嘴里，移开周围硬物，记录持续时间并立即送医。', ''),
  ('RF-005', '呕血', '["吐血","呕吐带血","吐咖啡色","呕吐物有血"]', 'all', 'all', 3,
   '呕血提示消化道出血，立即送医，带上呕吐物照片。', ''),
  ('RF-006', '便血', '["血便","拉血","大便带血","柏油样便"]', 'all', 'all', 3,
   '立即送医；拍下排泄物照片，医生需要判断出血位置。', ''),
  ('RF-007', '持续呕吐', '["呕吐不止","吐了一整天","反复呕吐","喝水都吐"]', 'all', 'all', 3,
   '持续呕吐会在数小时内导致脱水，立即送医，不要自行喂药。',
   '交付文档 9.5 点名的高危症状之一；单次呕吐不在红线内（那是黄级）'),
  ('RF-008', '无法站立', '["站不起来","瘫了","后腿不能动","走不了路","四肢无力"]', 'all', 'all', 3,
   '立即送医：后肢突然瘫痪可能是血栓或椎间盘急症，越早处理预后越好。', ''),
  ('RF-009', '腹部膨大', '["肚子胀大","腹部膨隆","胃扭转","肚子鼓得发硬"]', 'all', 'all', 3,
   '腹部突然膨大且干呕，可能是胃扭转，属分钟级急症，立即送医。', '大型犬高发'),
  ('RF-010', '大出血', '["流血不止","伤口大量出血","止不住血"]', 'all', 'all', 3,
   '先用干净纱布直接压迫止血，同时立即送医。', ''),
  ('RF-011', '难产', '["生了很久生不出","卡在产道","产程停滞"]', 'all', 'all', 3,
   '立即送医，不要在家强拉。', ''),
  ('RF-012', '排尿困难', '["尿不出","憋尿","频繁蹲下没尿","尿血"]', 'cat', 'all', 3,
   '猫尿闭是急症，超过 24 小时可致死，立即送医。', 'species_scope=cat：犬的尿闭同样急，但先说最常见的场景'),
  ('RF-013', '幼宠不吃不动', '["幼犬不吃","幼猫不吃","一直睡叫不醒","虚弱站不稳"]', 'all', 'puppy_kitten', 3,
   '幼年宠物低血糖与脱水进展极快，立即送医。', ''),
  ('RF-014', '眼球外伤', '["眼球突出","眼睛被抓伤","眼睛流脓血","眼睛睁不开"]', 'all', 'all', 3,
   '不要自行冲洗或涂药，用湿纱布轻盖后立即送医。', ''),
  ('RF-015', '吞食异物', '["吞了袜子","吞了线","吞了骨头","吞了玩具","吞了针"]', 'all', 'all', 3,
   '线绳类异物会切割肠道，不要催吐、不要拉拽，立即送医并告知吞了什么。', '');
