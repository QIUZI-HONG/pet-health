-- 权益引擎（切片 #112）——决策见 ADR-0038 第三节与 ADR-0045
--
-- 两张表：**码表**（有哪些能力）+ **授予记录**（谁、从哪来、到什么时候）。
--
-- 四条刻意的设计：
--   1. **每条来源各写一条授予**（`code` + `source` + `expire_at`）：
--      「邀请得的永久」与「订阅得的月度」是两条记录。合并成一条之后，
--      「订阅到期要不要回收」就分不清收的是哪一份了。
--   2. **来源优先级 订阅(1) > 邀请(2) > 打卡(3)，取生效里优先级最高的那条**。
--      **不按到期时间比较**：邀请得永久 + 订阅得月度这种组合下，取最晚到期会把语义算错
--      （ADR-0038 第三节的原话）。
--   3. **到期只回收该来源那一条**（`status` 1→2）：订阅到期不触碰邀请得的永久权益。
--      判定是**实时**的（每次查库，不缓存），因为权益的生效与否决定用户能不能用功能，
--      缓存漂移的代价是「明明有权益却被拦住」——这类错误用户无法自救。
--   4. `care.mode`（专项照护模式）**不在这张码表里**：它按医学事实自动开启（ADR-0032），
--      挂成权益会出现「够条件但权益不足，于是看不到专项入口」的自相矛盾（ADR-0038）。
--      权益码只表达**可授予的能力**，照护事实归档案模块。

CREATE TABLE `rights_code` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(64)     NOT NULL COMMENT '权益码（ai.unlimited / report.full / community.post / quota.ai.bonus）；**创建后不可改**（授予记录与各模块判定都引用它）',
  `name`        VARCHAR(64)     NOT NULL COMMENT '中文名，如「无限 AI 问答」',
  `description` VARCHAR(255)             DEFAULT NULL COMMENT '这项能力是什么、由哪个模块判定',
  `sort_order`  INT             NOT NULL DEFAULT 0,
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用只挡新的授予，已有的授予照常生效）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id（运营），0 表示系统写入',
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权益码表（运营可扩；新增的码默认不生效，判定逻辑仍在代码里）';

-- 授予记录：一条 = 一次「给某人一项能力、到某个时间为止」。
--
-- `source`：1 订阅 / 2 邀请 / 3 打卡 / 4 运营补偿。
-- 前三个是 ADR-0038 的三条路径（订阅在 ADR-0036 之后没有支付载体，只能线下签约 + 后台标记）；
-- 第四个（运营补偿）是客诉处理的显式口子——不给运营这个口子，客诉只能靠改数据库解决。
--
-- `expire_at` 为 NULL 表示**永久**（邀请来源就是这样）；打卡来源按「当月有效」在授予时算出到期日。
--
-- `(user_id, code, source, source_ref)` 唯一：同一来源的同一引用只授予一次。
-- 批算重跑、事件重放都命在这条键上——「同一行为不得重复发奖励」在权益这一侧的落点。
-- `source_ref` 为 NULL 时唯一索引允许多行（运营手动授予不做隐式去重，重复授予是看得见的动作）。
CREATE TABLE `rights_grant` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '被授予人（逻辑引用 user.id）',
  `code`        VARCHAR(64)     NOT NULL COMMENT '权益码（逻辑引用 rights_code.code）',
  `source`      TINYINT         NOT NULL COMMENT '1订阅2邀请3打卡4运营补偿',
  `source_ref`  VARCHAR(64)              DEFAULT NULL COMMENT '来源引用（邀请关系 id / 打卡账期 / 订阅编号），幂等与追溯用',
  `expire_at`   DATETIME                 DEFAULT NULL COMMENT '到期时间；NULL 表示永久',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1生效2已回收（到期回收或运营回收）',
  `remark`      VARCHAR(255)             DEFAULT NULL COMMENT '手动作业的理由（客诉、线下签约）或回收原因',
  `revoked_at`  DATETIME                 DEFAULT NULL COMMENT '回收时刻',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '授予人；0 表示系统授予（批算）',
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_ref` (`user_id`, `code`, `source`, `source_ref`),
  KEY `idx_user_code_status` (`user_id`, `code`, `status`),
  KEY `idx_status_expire` (`status`, `expire_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权益授予记录（码 + 来源 + 到期；每条来源各一条）';

-- 初始码表：交付文档的四项能力（ADR-0038 第三节的初始一组，运营可扩）。
-- 每个码的**判定逻辑在各自模块里**（AI 额度在 ph-ai、报告在 ph-record、社区发帖在 ph-content），
-- 这张表只负责「有没有、从哪来、到什么时候」。
INSERT INTO `rights_code` (`code`, `name`, `description`, `sort_order`) VALUES
  ('ai.unlimited',   '无限 AI 问答', '不受每日免费咨询次数限制',       1),
  ('report.full',    '完整报告',     '可查看完整版健康报告',           2),
  ('community.post', '社区发帖',     '可在社区发布内容',               3),
  ('quota.ai.bonus', '额外咨询额度', '在每日免费次数之上增加咨询额度', 4);
