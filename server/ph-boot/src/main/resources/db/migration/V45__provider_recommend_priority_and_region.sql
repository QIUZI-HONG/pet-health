-- 流量分配：推荐优先级写回服务者 + 区域编码可维护——「流量平衡」专项的落点。
--
-- 迁移前的状态（两处留白，来自 ADR-0052 与 ADR-0035）：
--   1. `assessment_monthly_score.recommend_priority`（等级 → 优先级的映射，运营可改档位）**只存不用**，
--      C 端找店按 `provider.level DESC` 排——等级只有三档、且被考核档位表决定，
--      改档位映射不会影响排序。于是「等级决定 AI 推荐优先级」这条口径实际是断的；
--   2. `provider.region_code` **没有任何写入点**：列在库里、索引也在，但永远是 NULL。
--
-- 本迁移做两件事，都不发明新的业务规则：
--   1. 把优先级**落到 provider 上**（与 level / monthly_score 同一处写回、同一套规则），
--      浏览侧改读它。这样「运营改一档推荐优先级」当场就能影响排序，不需要重算、
--      也不用在浏览查询里 join 考核分表（ADR-0006 的跨模块 join 是禁止的，而这张表在本模块内，
--      但 join 一张按月增长的宽表来排序仍然不划算）；
--   2. 区域编码由运营可写（接口在 `/providers/{provider_id}/region`），C 端浏览可按区域筛选。
--      **注意：排他性的「区域保护」规则仍未定**（谁在哪个区独占、独占多久、冲突怎么判）——
--      本迁移只让这个字段从「永远为空」变成「可维护、可筛选」，不假装已经保护起来了。
--
-- 回填：既有门店按 `level` 反查档位映射（1基础→3普通、2优选→2较高、3战略合作→1最高），
-- 与 V34 种子的映射一致。回填只做一次，之后由考核写回维护。

ALTER TABLE `provider`
  ADD COLUMN `recommend_priority` TINYINT NOT NULL DEFAULT 3
    COMMENT 'AI 推荐优先级（1最高2较高3普通），由月度考核按等级档位写回；默认 3 与 level 默认 1（基础）同档' AFTER `level`,
  ADD KEY `idx_recommend_priority` (`recommend_priority`, `rating`);

-- 回填：既有门店按 `level` 反查档位映射（1基础→3普通、2优选→2较高、3战略合作→1最高），
-- 与 V34 种子的映射一致（V34 的档位表里 level 2 的优先级是 2、level 3 是 1）。
-- 回填只做一次，之后由考核写回维护。
UPDATE `provider`
   SET `recommend_priority` = CASE `level`
       WHEN 1 THEN 3
       WHEN 2 THEN 2
       WHEN 3 THEN 1
       ELSE 3
     END;
