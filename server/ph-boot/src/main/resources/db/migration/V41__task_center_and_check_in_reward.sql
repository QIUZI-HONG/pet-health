-- 任务中心收尾 + 打卡得券（F017 的另一半）
--
-- 三件「机制早就通了、只差最后一根线」的事，前两件在这里，第三件在代码里：
--   1. **打卡得券**：交付文档 F017 要求「打卡得券」，而此前打卡只发积分——券来源码
--      `coupon.source = 2`（`Coupon.SOURCE_CHECK_IN_TASK`）在生产代码里零使用，
--      也就是说「打卡任务」这个来源从来没有真的发过一张券。这一版补上档位表 + 一条种子，
--      触发点在 ph-record（打卡成功 → 判断连续天数 → 调 ph-privilege 的 `CouponApi.issue`）。
--      **这一版只有种子、没有运营页面**：运营要改档位（加一档 / 换券 / 停用）得先写 SQL，
--      后台入口留给下一版——与 V27 建月度阶梯时的取舍一样（骨架先通，配置随后进后台）。
--   2. **「分享给好友」停用**（任务 + 行为两处）：桌面 Web 上分享没有**服务端可观测**的事件，
--      任务进度因此恒为 0/1。停用理由写在下面那两条 UPDATE 上。
--   3. **AI 建议**（`DAILY_AI_ADVICE` / `AI_ADVICE`）的生产者在代码里（ph-ai 的
--      `AiAdviceRecorder`），**不需要改数据**：那两条配置本来就是对的（0 分 = 记行为不发分）。

-- 打卡连续天数的奖励档位（F017 的「打卡得券」）。
--
-- 为什么是**一张表**而不是代码常量：发什么券、几档、每档几张是业务可调项（ADR-0010 的分层：
-- 提示词/红线词/分级规则/降级开关入库 + 运营可改），换一档不该发版。与 `point_ladder_tier`
-- （月度阶梯）、`invite_ladder_tier`（邀请阶梯）是同一种形状：**档位是一行可停用的配置**。
--
-- `streak_days` 唯一：同一档位只该有一条规则，两条「连续 7 天」会让一次达成命中两次
-- （靠唯一键挡住，而不是靠运营小心）。
CREATE TABLE `check_in_reward_rule` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `streak_days`        INT             NOT NULL COMMENT '档位：连续打卡天数**达到**它就发券（连续天数口径见 CheckInService.streak）',
  `coupon_template_id` BIGINT UNSIGNED NOT NULL COMMENT '发什么券（逻辑引用 coupon_template.id）',
  `coupon_count`       INT             NOT NULL DEFAULT 1 COMMENT '发几张',
  `sort_order`         INT             NOT NULL DEFAULT 0,
  `status`             TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用的档位不参与判定）',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`           VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_streak_days` (`streak_days`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '打卡连续天数奖励档位（F017；本版只有种子，运营入口待下一版）';

-- 首条（也是本轮唯一一条）档位：连续 7 天 → 1 张「洗护券 20 元」。
--
-- 券模板取 `CP-101`（**上一批 `V39__first_batch_growth_config.sql` 建的种子**，面额与适用范围
-- 取自交付文档 BPM-2），所以这条迁移必须排在 V39 之后：早跑会取到不存在的模板 id（NULL），
-- 档位变成一条永远取不到券的配置——那正是本轮要消灭的那种「看起来配了、其实不生效」。
-- 「为什么是 7 天」：交付文档 F017 只写了「打卡得券」，没有给天数与档位，7 天是**暂定值**，
-- 与「首批增长配置」同一口径（运营按预算与留存目标改，改法与依据见 V39 文件末尾）。
INSERT INTO `check_in_reward_rule` (`streak_days`, `coupon_template_id`, `coupon_count`, `sort_order`)
VALUES (7, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'), 1, 1);

-- 「分享给好友」停用——两处一起，理由是同一条。
--
-- **为什么停用而不是留着**：桌面 Web 上「分享」没有可靠的服务端可观测事件。用户复制链接、
-- 用浏览器自带分享、把页面发进微信群，这三种都在**客户端**发生，服务端拿不到「它真的发生过」
-- 这个事实（客户端上报说不上可信：同一个人同一分钟可以点无数次，也没有「对方真的收到了」）。
-- 没有可观测事件就没有生产者，任务进度恒 0/1——**留一条永远做不完的任务比没有这条任务更糟**：
-- 用户以为功能坏了，运营以为埋点丢了，客服照着页面解释也解释不出所以然。
--
-- **为什么行为也一起停用**：任务进度是按行为流水聚合的（`point_record`），
-- 只停任务的话「分享」这个行为仍然可发（分值 0，但会记行为）——将来任何人接一条上报上来，
-- 都会静默地开始给用户发东西。两处一起停 = 这个动作在平台侧**整条链路**关闭；
-- 要恢复得连同生产者一起做（那是一个功能，不是一次配置）。
--
-- **为什么是 UPDATE 而不是 DELETE**：`point_record.behavior_code` 是字符串逻辑引用，
-- 删掉行为码会让历史流水失去解释（`SHARE` 的流水即便为 0 分也有「这一天的分享任务进度」这个含义），
-- 而且行为码与任务码是代码约定的一部分（V27 的注释：行为表只能改分值，不能新增）。
-- 顺带：`point_task` 的任务码 `DAILY_SHARE` 也留在表里，C 端的任务列表按 `status` 过滤
-- （AppGrowthConsole.pointsCenter），所以停用项对用户完全不可见，运营后台照常看得见它。
UPDATE `point_task` SET `status` = 0 WHERE `code` = 'DAILY_SHARE';
UPDATE `point_behavior` SET `status` = 0 WHERE `code` = 'SHARE';

-- 运营改法（都不需要发版，但本轮**没有页面**，所以暂时只能写 SQL）：
--   · 加一档：INSERT INTO `check_in_reward_rule` (`streak_days`, `coupon_template_id`, `coupon_count`)
--     VALUES (30, (SELECT `id` FROM `coupon_template` WHERE `code` = 'CP-101'), 1);
--   · 停用某一档：把它的 `status` 置 0；
--   · 改「分享」的启停：`point_task` / `point_behavior` 两处一起改（只改一处会让进度与分值表打架）。
