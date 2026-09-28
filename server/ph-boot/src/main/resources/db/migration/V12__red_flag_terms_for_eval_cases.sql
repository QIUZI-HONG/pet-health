-- 补齐红线词表对「不许漏」样本的覆盖（ADR-0021 的保障缺口）。
--
-- 怎么发现的：把发布门槛跑起来之后（`pytest -m eval`，见 ai/tests/test_eval.py），
-- 评测集里 9 条 `must_not_miss` 有 **4 条没有规则覆盖**——它们的预期等级写在样本的 note 里
-- （「便血（RF-006）」），但规则里的词匹配不上用户的实际说法，于是这几条落到模型手上，
-- 红色召回变成「看模型这次心情」——正是 ADR-0021 明说不能接受的那种押注。
-- 第二次跑门槛时 EV-006 就真的漏了（模型判黄），召回率掉到 89%。
--
-- 缺口都是**同一类**：词表写的是书面的短词，用户说的是带插入语的口语。
--   便血   ← 「便里带血」   （主词 便血 不是它的子串）
--   无法站立 ← 「后腿突然不能动了，拖着走」（variants 里是「后腿不能动」，中间多了「突然」）
--   窒息   ← 「舌头都发紫了」 （variants 里是「舌头变紫」）
--   幼宠不吃不动 ← 「幼犬今天不吃也不动，一直睡」（variants 里是「幼犬不吃」）
--
-- 处理办法不是再想更多同义词，而是**把口语里真实会出现的整块说法补进去**，并加一条测试
-- （`ai/tests/test_red_flag_coverage.py`）把「must_not_miss 样本必须有规则覆盖」钉住——
-- 下次给评测集加一条红线样本，忘了补词就会红。
--
-- 词表仍然是**工程按公开兽医急诊共识起草**、`review_status = pending_review`（与 V6 同一口径），
-- 复核与后续增删属 #103 的词表治理。只动 variants，不改主词与动作文案。

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('血便', '拉血', '大便带血', '柏油样便',
                               '便里带血', '便中带血', '粪便带血', '便便带血', '屎里带血',
                               '大便有血丝', '便里有血丝', '拉血便', '血样便'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-006';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('站不起来', '瘫了', '后腿不能动', '走不了路', '四肢无力',
                               '后腿动不了', '后腿突然不能动', '腿不能动', '拖着走', '后腿无力',
                               '前腿不能动', '突然瘫了'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-008';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('卡住', '卡在喉咙', '呼吸困难', '喘不上气', '张口呼吸',
                               '舌头变紫', '舌头发紫', '舌头紫', '舌头都发紫', '发紫',
                               '牙龈发白', '牙龈发紫'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-003';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('幼犬不吃', '幼猫不吃', '一直睡叫不醒', '虚弱站不稳',
                               '不吃不动', '不吃也不动', '一直睡', '叫不醒', '不愿动',
                               '幼犬不吃不喝', '幼猫不吃不喝'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-013';
