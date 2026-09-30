-- 社区：经验卡片与点赞（切片 #84 / F020；决策见 ADR-0041 第一节 / ADR-0050 第一节）
--
-- 范围：**经验卡片 + 问答互助**。慢病社群**后置**——它需要按慢病标签聚合的内容流与
-- 内容种子，两样现在都没有，先做就是一个没人发帖的空壳，而空壳比没有更伤
-- （ADR-0050 第一节的原话）。所以这里**没有** `community_group` 之类的社群表。
--
-- 四条刻意的设计，都是「这一块唯一的硬承诺」的落点：
--
--   1. **卡片恒匿名**（ADR-0041 第一节、交付文档 F020「录入即生成卡片（匿名）」）：
--      `author_id` 必须留着（越权判定、作者看自己的内容、下架时追溯），但它**只服务这三件事**——
--      任何对外视图都不得出现作者 id、昵称或宠物昵称。契约（contract/app.yaml 的
--      CommunityCardView）里没有这些字段，实现里也不许补。
--      交付文档写的是「默认匿名」，这里收紧成**恒匿名**：没有非匿名的场景支撑，
--      而留一个开关等于给这条隐私保证开一个口子（ADR-0051 的决定与待澄清）。
--   2. **卡片与来源记录是逻辑关联，不是外键、更不是拷贝**（ADR-0006 不许 join 别人的表）：
--      `source_type`（1 打卡 / 2 就医记录）+ `source_ref`（来源记录 id）+ `pet_id`（哪只宠物的记录）。
--      展示路径**永不回读**那条记录——卡片正文是用户改完提交的正文，档案正文不因社区再暴露一次。
--   3. **聚合靠卡片上的快照标签**，不靠现取宠物属性：`species` / `breed` / `disease_tag` 是
--      生成时的快照。之后宠物改名、品种登记被纠正、慢病标记被去掉，都不该改写已经发出去的历史卡片
--      （与订单快照同一口径，理由见 V29 的注释；与目录项「现取不存快照」相反，因为那说的是**目录**）。
--   4. **审核状态就在这张表上**（0 待审 / 1 已发布 / 2 已驳回，含运营下架）：
--      内容默认进待审，机审敏感词命中直接落 `2`，运营通过才转 `1`，公开列表只读 `status=1`
--      （ADR-0041 第一节：机审 + 运营人工处置；ADR-0037 第一节：内容审核归运营）。
--      `machine_hits` / `reject_reason` / `reviewed_by` / `reviewed_at` 是**审核留痕**：
--      没有它们，「这条为什么没显示」只能靠翻 audit_log 猜。

CREATE TABLE `community_card` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `author_id`      BIGINT UNSIGNED NOT NULL COMMENT '作者（逻辑引用 user.id）。**对外永不出现**：卡片恒匿名',
  `pet_id`         BIGINT UNSIGNED NOT NULL COMMENT '来源记录所属的宠物（逻辑引用 pet.id）。生成时校验归属，之后不再回读',
  `source_type`    TINYINT         NOT NULL COMMENT '来源记录类型：1 打卡 / 2 就医记录',
  `source_ref`     VARCHAR(64)     NOT NULL COMMENT '来源记录 id（逻辑引用；只做关联与追溯，服务端不解引用——ADR-0051 待澄清第 2 条）',
  `species`        TINYINT                  DEFAULT NULL COMMENT '物种快照：1 犬 / 2 猫（「同品种」聚合用）；未带为空',
  `breed`          VARCHAR(32)              DEFAULT NULL COMMENT '品种快照（「同品种」聚合用）',
  `disease_tag`    VARCHAR(32)              DEFAULT NULL COMMENT '慢病标签快照（「同病」聚合用）',
  `title`          VARCHAR(64)     NOT NULL COMMENT '卡片标题',
  `content`        VARCHAR(2000)   NOT NULL COMMENT '卡片正文（前端从来源记录预填、用户改完提交）',
  `like_count`     INT             NOT NULL DEFAULT 0 COMMENT '点赞数（唯一键去重后累加，只增不减：取消点赞会减回去）',
  `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审 1已发布 2已驳回（含运营下架）。公开列表只读 1',
  `machine_hits`   VARCHAR(255)             DEFAULT NULL COMMENT '机审命中的敏感词（逗号分隔）；未命中为空',
  `reject_reason`  VARCHAR(255)             DEFAULT NULL COMMENT '驳回 / 下架理由（运营填的原文，展示给作者自己）',
  `reviewed_at`    DATETIME                 DEFAULT NULL COMMENT '最后一次人工处置时间',
  `reviewed_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处置人（运营）；0 表示还没人处置过',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '作者（与 author_id 相同；写操作留痕 ADR-0011）',
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  -- 列表的两条主查询：公开列表（status=1 时间倒序）、我的卡片（author_id + 时间倒序）
  KEY `idx_status_created` (`status`, `is_deleted`, `id`),
  KEY `idx_author_created` (`author_id`, `is_deleted`, `id`),
  -- 「同品种」「同病」聚合：标签是快照，索引也就建在这两列上
  KEY `idx_species_breed` (`species`, `breed`),
  KEY `idx_disease_tag` (`disease_tag`),
  -- 审核队列（待审先到先审）与「这条卡片是从哪条记录生成的」
  KEY `idx_status_pending` (`status`, `is_deleted`),
  KEY `idx_source` (`pet_id`, `source_type`, `source_ref`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '经验卡片（恒匿名；与来源记录逻辑关联；聚合靠快照标签）';

-- 点赞：**唯一键 (card_id, user_id)** 就是「同一个人只算一次赞」这条不变量本身。
--
-- 为什么不做幂等表 / 不做「点赞记录」之外的统计表：`community_card.like_count` 是**冗余计数**，
-- 它只为列表少一次 COUNT；真值永远以本表为准（两者不一致时，本表是证据）。
-- 并发双击点赞靠唯一键拦下第二个请求，被拦的那次不报错、不加计数（幂等，见契约的 likes 接口）。
CREATE TABLE `community_card_like` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `card_id`    BIGINT UNSIGNED NOT NULL COMMENT '被点赞的卡片（逻辑引用 community_card.id）',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '点赞人（逻辑引用 user.id）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞人（与 user_id 相同；写操作留痕 ADR-0011）',
  `updated_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`   VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted` TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_user` (`card_id`, `user_id`),
  KEY `idx_user` (`user_id`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '经验卡片点赞（(card_id, user_id) 唯一 = 一人一赞）';
