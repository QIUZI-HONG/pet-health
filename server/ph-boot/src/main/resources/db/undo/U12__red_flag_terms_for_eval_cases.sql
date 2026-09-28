-- 回滚 V12：把四条规则的 variants 还原成 V6 的首版词表。
--
-- 代价：评测集里 4 条 must_not_miss 样本重新失去规则覆盖，红色召回又变成「看模型这次心情」。
-- 如果是为了复核词表才回滚，记得复核完把口语变体加回来（`ai/tests/test_red_flag_coverage.py`
-- 会在缺覆盖时直接报红）。

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('血便', '拉血', '大便带血', '柏油样便'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-006';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('站不起来', '瘫了', '后腿不能动', '走不了路', '四肢无力'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-008';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('卡住', '卡在喉咙', '呼吸困难', '喘不上气', '张口呼吸', '舌头变紫', '牙龈发白'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-003';

UPDATE `knowledge_red_flag`
   SET `variants` = JSON_ARRAY('幼犬不吃', '幼猫不吃', '一直睡叫不醒', '虚弱站不稳'),
       `updated_at` = CURRENT_TIMESTAMP
 WHERE `code` = 'RF-013';
