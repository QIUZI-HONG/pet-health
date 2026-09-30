-- 社区：问答互助与内容审核词表（切片 #84 / F020；决策见 ADR-0041 第一节 / ADR-0037 第一节）
--
-- 三张表：**提问**、**回答**、**机审敏感词表**。
--
-- 三条刻意的设计：
--
--   1. **采纳只有一份真值**：`community_question.adopted_answer_id`。回答行上**不**再存
--      `is_adopted`——同一个事实存两处，迟早会出现「问题说采纳了 A、A 说自己没被采纳」。
--      唯一性不靠「先查有没有采纳过再写」：那是 read→if→write，并发下双通过（ADR-0044 记的
--      同类教训）。落点是**条件更新** `UPDATE ... SET adopted_answer_id = ? WHERE id = ?
--      AND adopted_answer_id IS NULL`，受影响行数为 0 就是「已经采纳过了」→ 40900。
--   2. **提问只有提问者能采纳**：这一条**不是**靠权限位而是靠归属判定
--      （`author_id` 与当前用户比对，不匹配 → 40400，与不存在同码）。
--      采纳按钮只会出现在提问者界面上，别人来调只能是拿 id 试探。
--   3. **审核状态在两张内容表上都有一份**（同 V31 的口径）：0 待审 / 1 已发布 / 2 已驳回（含下架）。
--      回答的状态与提问互不影响：提问被下架时它的回答不必跟着改状态——公开可见性由
--      **读取路径**一起判（提问不可见则回答也不可见），而不是靠级联写状态维持两种一致的副本。
--
-- 敏感词表（`content_sensitive_word`）属于**业务可调项**，按 ADR-0010 的分层入库 + 运营后台维护，
-- **不写死在代码里**：词表是会随热点变的运营资产，改一次要发一次版，等于没人会改。
-- 与 AI 侧 `knowledge_guard_term`（V20）的区别见 contract/admin.yaml 的 content-review 标签说明：
-- 那张表管模型**输出**里的药名与越界表述，这张表管**用户发进来**的内容（CONTEXT.md 的硬红线条目：
-- 敏感词属内容审核域，与红线词无关）。两张表**不做合并**：它们的读者、判据与责任人都不同。

CREATE TABLE `community_question` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `author_id`         BIGINT UNSIGNED NOT NULL COMMENT '提问人（逻辑引用 user.id）。**对外只出 `mine`**，不出 id',
  `title`             VARCHAR(64)     NOT NULL COMMENT '问题标题',
  `content`           VARCHAR(2000)   NOT NULL COMMENT '问题正文',
  `disease_tag`       VARCHAR(32)              DEFAULT NULL COMMENT '慢病标签（「同病」聚合用）',
  `answer_count`      INT             NOT NULL DEFAULT 0 COMMENT '已过审回答数（冗余计数；真值以 community_answer 为准）',
  `adopted_answer_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT '**被采纳的回答**（唯一的采纳真值）。为空 = 还没采纳；不为空后不允许改写（采纳不回退）',
  `adopted_at`        DATETIME                 DEFAULT NULL COMMENT '采纳时间',
  `status`            TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审 1已发布 2已驳回（含下架）。公开列表只读 1',
  `machine_hits`      VARCHAR(255)             DEFAULT NULL COMMENT '机审命中的敏感词（逗号分隔）',
  `reject_reason`     VARCHAR(255)             DEFAULT NULL COMMENT '驳回 / 下架理由（运营填的原文）',
  `reviewed_at`       DATETIME                 DEFAULT NULL,
  `reviewed_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '提问人（与 author_id 相同；写操作留痕 ADR-0011）',
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `is_deleted`, `id`),
  KEY `idx_author_created` (`author_id`, `is_deleted`, `id`),
  KEY `idx_disease_tag` (`disease_tag`),
  KEY `idx_adopted` (`adopted_answer_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '问答互助：提问（采纳唯一真值在 adopted_answer_id）';

CREATE TABLE `community_answer` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question_id`   BIGINT UNSIGNED NOT NULL COMMENT '所属提问（逻辑引用 community_question.id）',
  `author_id`     BIGINT UNSIGNED NOT NULL COMMENT '回答人（逻辑引用 user.id）。**对外只出 `mine`**，不出 id',
  `content`       VARCHAR(2000)   NOT NULL COMMENT '回答正文',
  `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审 1已发布 2已驳回（含下架）。详情只读 1',
  `machine_hits`  VARCHAR(255)             DEFAULT NULL COMMENT '机审命中的敏感词（逗号分隔）',
  `reject_reason` VARCHAR(255)             DEFAULT NULL COMMENT '驳回 / 下架理由（运营填的原文）',
  `reviewed_at`   DATETIME                 DEFAULT NULL,
  `reviewed_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '回答人（与 author_id 相同；写操作留痕 ADR-0011）',
  `updated_by`    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`      VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  -- 详情页的主查询：一条提问下按时间升序取已过审的回答
  KEY `idx_question_status` (`question_id`, `status`, `is_deleted`, `id`),
  KEY `idx_author_created` (`author_id`, `is_deleted`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '问答互助：回答（采纳与否由提问表的 adopted_answer_id 决定，本表不存副本）';

CREATE TABLE `content_sensitive_word` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word`       VARCHAR(64)     NOT NULL COMMENT '词条。判定是**包含匹配**（不做正则、不做分词，大小写不敏感）：运营要能肉眼预测拦截结果',
  `category`   VARCHAR(32)              DEFAULT NULL COMMENT '分类标签（广告 / 引流 / 虚假疗效…），**不参与判定**，只给运营自己看',
  `enabled`    TINYINT         NOT NULL DEFAULT 1 COMMENT '1 参与机审 / 0 停用。**停用不删**：留着它才能解释「昨天为什么拦了那条内容」',
  `remark`     VARCHAR(255)             DEFAULT NULL COMMENT '为什么拦这个词（写给运营看）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '维护人（运营）；0 表示迁移种子',
  `updated_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`   VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted` TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_enabled` (`enabled`, `is_deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容机审敏感词表（运营后台维护；ADR-0010 的业务可调项分层）';

-- 初始词表：**种子不是标准**，只是让机审第一天就有东西可拦。
-- 选词口径（ADR-0051 的待澄清：词表来源）：只放「放之四海皆可拦」的引流与虚假疗效表述——
-- 平台自己的合规立场（说明书式的医疗广告词在宠物健康社区里必然是误导），不引任何外部词库。
-- **刻意不放**的几类：宠物品种名、疾病名、药名。它们在正常交流里都会出现，一放进去
-- 机审就会把真实用户拦在门外，而词表误伤的代价由用户承担（这才是「宁漏勿错」的方向：
-- 漏放的内容还有人工队列兜着，误伤的内容作者只会再也不发）。
INSERT INTO `content_sensitive_word` (`word`, `category`, `enabled`, `remark`) VALUES
  ('加微信',   '引流',     1, '把用户往站外私域引，出了纠纷平台无从介入'),
  ('加v',      '引流',     1, '同上（大小写不敏感，加V 也会命中）'),
  ('私信我',   '引流',     1, '同上'),
  ('代购',     '广告',     1, '站外交易与药品代购，宠物健康场景下风险最高的一类'),
  ('包治百病', '虚假疗效', 1, '医疗与合规宁严勿松：这类绝对化疗效表述在健康社区一律拦'),
  ('祖传秘方', '虚假疗效', 1, '同上');
