-- 券池（切片 #110）——决策见 ADR-0037 第三节与 ADR-0044
--
-- 池子里是**三个不同的对象**，不要混用（CONTEXT.md「权益与增长」一节）：
--   1. `coupon_template`   券模板：一种券的定义（面额 / 门槛 / 有效期 / 适用范围 / 成本归属），
--                          **只能由平台创建**——统一券池是这个机制的核心卖点，放开自建等于没有券池；
--   2. `coupon_contribution` 券贡献：服务者从池子里选一个模板并**承诺可核销额度**——
--                          它不是发放，是「我店愿意接多少张」（ADR-0037 的原文口径）；
--   3. `coupon`            券实例：发给某个用户的**那一张**。来源五种（见 `source` 注释）。
--
-- 四条刻意的设计：
--   1. **不做抢券**：发放全部定向触发（邀请 / 打卡任务 / 积分兑换 / 平台补贴 / 月度阶梯），
--      所以没有「库存扣减」那样的并发入口，只有「这条贡献还能不能再发一张」。
--   2. **券实例存面额 / 门槛 / 适用范围的快照**：券是平台对**用户**的承诺，
--      模板随后改面额不能改写已经发出去的券（与 `provider_service` 不存名称快照正好相反——
--      那里要的是「目录只有一个真相」，这里要的是「发出去的就是承诺」）。
--   3. **额度是算出来的，不是记出来的**：`可发放 = total_count − 已核销 − 占用中`，
--      其中「占用中」= 已发放、未核销、未过期。**过期未核销自动释放额度回池**，
--      已核销不释放（ADR-0037）。不存 `available_count` 列：存了就要在四个地方同步它，
--      而它一旦漂移，「超发」与「发不出去」都不报错。
--   4. **对账口径**：`实例数 = 已发放 = 已核销 + 未过期未核销 + 已过期未核销`。
--      本项目没有资金可对（ADR-0036），这张就是券的对账，不平即告警。
--
-- 金额一律 DECIMAL(10,2)（docs/conventions.md），**禁止浮点**。
-- `cost_bearer` 只回答「这张券的抵扣算谁头上」，进核销统计与考核（#58），
-- **不涉及结算与打款**——本项目钱在门店付，平台不经手资金。

CREATE TABLE `coupon_template` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`         VARCHAR(32)     NOT NULL COMMENT '模板编码，形如 CP-001；创建后不可改、不可复用（券实例与适用范围挂在它上面）',
  `name`         VARCHAR(128)    NOT NULL COMMENT '券名，如「基础体检立减 30 元」',
  `face_value`   DECIMAL(10,2)   NOT NULL COMMENT '抵扣面额（元）',
  `min_amount`   DECIMAL(10,2)   NOT NULL DEFAULT 0.00 COMMENT '使用门槛：订单总额不低于它才可用；0.00 表示无门槛',
  `valid_days`   INT             NOT NULL DEFAULT 30 COMMENT '有效期：自发放之日起 N 天内有效（到期未用即失效）',
  `cost_bearer`  TINYINT         NOT NULL COMMENT '1服务者成本2平台补贴——只影响核销统计与考核，不产生资金（ADR-0036）',
  `scope_type`   TINYINT         NOT NULL DEFAULT 0 COMMENT '0不限1限服务分类2限目录项（适用范围与标准目录编码挂钩，ADR-0037）',
  `scope_codes`  VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '适用范围编码，逗号分隔（逻辑引用 service_category.code / service_item.code，不建物理外键）',
  `issue_limit`  INT                      DEFAULT NULL COMMENT '发放上限，只对平台补贴券有意义；NULL 表示不限（服务者成本券额度由贡献决定，恒为 NULL）',
  `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用只挡新的发放与新的贡献，已发出的券照常可核销）',
  `description`  VARCHAR(255)             DEFAULT NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id（平台运营），0 表示系统写入',
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status_cost` (`status`, `cost_bearer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '券模板（平台统一定义的券：面额/门槛/有效期/适用范围/成本归属）';

-- 服务者的券贡献：选券 + 承诺额度。
--
-- **一个服务者对一个模板只留一条额度账**（唯一键）：「我店承诺 200 张」再改成「300 张」
-- 是同一件事的两次编辑，不是两条承诺。撤回（status=2）也不新建第二条——
-- 重新承诺时把同一条改回生效，额度账的历史才连得起来。
--
-- 撤回只收回**还没发出去**的那部分：`total_count` 夹到「已核销 + 占用中」。
-- 已经发到用户手里的券照常有效——用户的券不是服务者可撤销的承诺。
CREATE TABLE `coupon_contribution` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`  BIGINT UNSIGNED NOT NULL COMMENT '承诺额度的服务者（逻辑引用 provider.id）',
  `template_id`  BIGINT UNSIGNED NOT NULL COMMENT '选中的券模板（逻辑引用 coupon_template.id）',
  `total_count`  INT             NOT NULL COMMENT '承诺的可核销额度（不是发放，是「我店愿意接多少张」）',
  `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1生效中2已停止发放（服务者撤回；已发出的券不受影响）',
  `remark`       VARCHAR(255)             DEFAULT NULL COMMENT '最近一次承诺或调整的说明',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_template` (`provider_id`, `template_id`),
  KEY `idx_template_status` (`template_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务者券贡献（选券 + 承诺可核销额度）';

-- 额度流水（append-only：只插入，不更新不删除）。
--
-- 「额度是怎么变的」必须查得出来：服务者问「我的额度去哪了」时，答案在这张表里，
-- 而不是在那个算出来的结果数里。四个动作：
--   1 承诺（第一次选券）/ 2 调整额度 / 3 撤回 / 4 过期释放（系统写入，actor=0）
-- 发放与核销本身不在这里——它们各自在 `coupon` 那一行上留下了痕迹（issued_at / redeemed_at），
-- 记两遍只会让「哪一份是真的」变成问题。
CREATE TABLE `coupon_contribution_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `contribution_id` BIGINT UNSIGNED NOT NULL,
  `provider_id`     BIGINT UNSIGNED NOT NULL,
  `action`          TINYINT         NOT NULL COMMENT '1承诺2调整额度3撤回4过期释放',
  `total_count`     INT             NOT NULL COMMENT '本次动作后的承诺额度',
  `available_count` INT             NOT NULL COMMENT '本次动作后的可发放额度（= 承诺 − 已核销 − 占用中），便于事后复盘',
  `remark`          VARCHAR(255)             DEFAULT NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id；0 表示系统写入（如过期释放）',
  `updated_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`        VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_contribution` (`contribution_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '券贡献的额度流水（append-only）';

-- 券实例：发给某个用户的**那一张**。
--
-- `source` 五种：1邀请 2打卡任务 3积分兑换 4平台补贴 5月度阶梯。
-- 前四种是 ADR-0037 定的「四种来源」；第五种（月度阶梯，F018）的触发路径与前四种都不同
-- （上月积分累计达标自动发），硬塞进「积分兑换」会让对账看不清券从哪来——取舍见 ADR-0046。
--
-- `(source, source_ref)` 唯一：**同一行为的同一引用只发一张券**。
-- 这是「同一行为不得重复发奖励」的落点（ADR-0038 第四节）——重放、重试、批算重跑
-- 都命在这条唯一键上，而不是靠调用方小心。`source_ref` 为 NULL 时（运营手动发的补贴券）
-- MySQL 的唯一索引允许多行，符合「手动发放不去重」的口径。
--
-- `status`：1 待使用 / 2 已锁定（下单占用，ADR-0038 第一节的「锁定 → 取消释放 → 核销转已用」）
--           3 已核销 / 4 已过期。
-- **「占用中」= status in (1,2) 且 valid_until ≥ now**：判定看的是时间而不是那一列状态，
-- 因为过期由每日批算翻状态，批算与读取之间有窗口；按时间判才不会有「已经过期还占着额度」的窗口。
-- 已过期不等于删：它留在库里参与对账（实例数 = 已核销 + 未过期未核销 + 已过期未核销）。
CREATE TABLE `coupon` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`              VARCHAR(32)     NOT NULL COMMENT '券码（运营与客服排查用，不是安全凭证）',
  `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '持券用户（逻辑引用 user.id）',
  `template_id`       BIGINT UNSIGNED NOT NULL COMMENT '来自哪个模板（逻辑引用 coupon_template.id）',
  `source`            TINYINT         NOT NULL COMMENT '1邀请2打卡任务3积分兑换4平台补贴5月度阶梯',
  `source_ref`        VARCHAR(64)              DEFAULT NULL COMMENT '来源引用（邀请关系 id / 任务批次 / 兑换单号 / 阶梯账期），与 source 一起做幂等',
  `contribution_id`   BIGINT UNSIGNED          DEFAULT NULL COMMENT '占用了哪条服务者贡献；平台补贴券为 NULL',
  `provider_id`       BIGINT UNSIGNED          DEFAULT NULL COMMENT '核销门店：服务者贡献券只在其本店核销；平台补贴券为 NULL（按模板适用范围）',
  `face_value`        DECIMAL(10,2)   NOT NULL COMMENT '面额快照（发放时的模板面额）',
  `min_amount`        DECIMAL(10,2)   NOT NULL DEFAULT 0.00 COMMENT '门槛快照',
  `scope_type`        TINYINT         NOT NULL DEFAULT 0 COMMENT '适用范围快照：0不限1限分类2限目录项',
  `scope_codes`       VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '适用范围快照',
  `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '1待使用2已锁定3已核销4已过期',
  `valid_from`        DATETIME        NOT NULL COMMENT '生效时间',
  `valid_until`       DATETIME        NOT NULL COMMENT '到期时间（到期未用即失效并释放服务者额度）',
  `issued_at`         DATETIME        NOT NULL COMMENT '发放时间',
  `locked_order_id`   BIGINT UNSIGNED          DEFAULT NULL COMMENT '锁定它的订单（下单占用）',
  `locked_at`         DATETIME                 DEFAULT NULL,
  `redeemed_order_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT '核销它的订单',
  `redeemed_at`       DATETIME                 DEFAULT NULL,
  `redeemed_by`       BIGINT UNSIGNED          DEFAULT NULL COMMENT '核销人（服务者后台账号 id）',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '发放人；0 表示系统发出（任务/阶梯批算）',
  `updated_by`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_source_ref` (`source`, `source_ref`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_contribution_status` (`contribution_id`, `status`),
  KEY `idx_template_status` (`template_id`, `status`),
  KEY `idx_provider_status` (`provider_id`, `status`),
  KEY `idx_status_valid_until` (`status`, `valid_until`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '券实例（定向发给某个用户的券；到期未用即失效）';
