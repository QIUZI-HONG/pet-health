-- 评价晒单（第一版：评分 + 一句话）
--
-- 交付文档 7.2 的评价表按本项目的决定收敛成这一张，三处刻意的取舍：
--
--   1. **一单一评**：唯一键加在 `order_id` 上。评价是订单的产物（没有订单就没有评价），
--      而「同一单能不能评两次」不是配置项——它是「这次服务发生过几次」的语义，
--      一个订单只有一次。唯一键同时是并发双击的兜底（先查后写之间会漏，键不会）。
--   2. **没有图片 / 追评 / 匿名 / 回复 / 审核 / 维度拆分**：这一版要的是「用户能评价、
--      门店评分会动」这个最小闭环。少一张从属表就少一处「先做一半、后来要迁移」的欠账。
--   3. **表归订单域（ph-order）**：读路径 `GET /api/v1/app/providers/{provider_id}/reviews`
--      虽然挂在门店（服务者）域的路径下，那条接口也由 ph-order 服务——路径按调用方语义、
--      数据按归属。**不为了「路径在门店域」把表挪给 ph-provider**：评价行要回答的是
--      「哪一单、谁、什么体验」，这件事只有订单域有完整上下文（ADR-0006 也禁止它去读别人的表）。
--
-- 与 `order` 一样，这里**没有用户身份的展示字段**：昵称 / 手机号是 ph-account 的表，
-- C 端门店页不显示评价人（第一版没想清楚「评价是否带身份、带身份怎么脱敏与授权」，
-- 所以先不给——`user_id` 只用于「谁评的」这一条数据事实与将来的客服排查）。
--
-- 索引按**查询方式**建：唯一的列表读法是「某门店 + 时间倒序」，所以是
-- `(provider_id, created_at DESC)`，而不是给每一列都配一个索引等它自己长出来。
-- `order_review` 不是保留字（`order` 是），所以此处不需要反引号以外的特殊处理。

CREATE TABLE `order_review` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT UNSIGNED NOT NULL COMMENT '被评价的订单（逻辑引用 order.id）。**唯一键：一单一评**',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '评价人 = 下单人（逻辑引用 user.id；越权校验与客服排查用）',
  `pet_id`      BIGINT UNSIGNED NOT NULL COMMENT '被服务的宠物快照（逻辑引用 pet.id；C 端不下发，用于「这条评价是哪只宠物的服务」）',
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '被评价的服务者（逻辑引用 provider.id）；平均分按它聚合',
  `rating`      TINYINT         NOT NULL COMMENT '评分 1–5（整数，不给半星）',
  `content`     VARCHAR(500)             DEFAULT NULL COMMENT '一句话评价（可选，≤500 字）；只打分不写字时为 NULL，不存空串',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评价人（与 user_id 相同；写操作留痕 ADR-0011）',
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  KEY `idx_provider_time` (`provider_id`, `created_at` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单评价（一单一评；门店评分 = 这里的平均分）';
