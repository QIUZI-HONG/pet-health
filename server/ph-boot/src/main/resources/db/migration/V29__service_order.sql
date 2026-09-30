-- 订单（切片 #77 / #109，决策见 ADR-0038 第一节、ADR-0036）
--
-- 对应交付文档 7.2 的 `order` 表，但它按本项目的决定改了三处：
--
--   1. **状态从六态收敛为四态 + 取消**（ADR-0038 第一节，票里点名要改这里的注释）：
--      `0待接单 1已预约 2履约中 3已完成 4已取消`。文档的 `0待支付/1已支付/5已退款`
--      **不定义**——到店付下它们没有语义（ADR-0036：平台不经手资金）。
--      非法迁移一律 40900（状态冲突，不是参数错）。
--   2. **`merchant_id` → `provider_id`**：术语纪律（CONTEXT.md：禁用「商家」，代码标识符用
--      provider；`merchant_id` 是交付文档的字段名，文档用词）。
--   3. **没有 `pay_amount`，改叫 `estimated_pay_amount`**：钱在门店付，平台不经手资金，
--      所以订单上不许出现任何收款 / 结算字段（ADR-0036）。这个值是
--      「总额 − 券面额」的**展示用计算值**，不参与对账、不构成收款事实。
--      `check_in_photo / protect_photo / check_out_photo` 三个 JSON 列也没有照抄：
--      三道照片墙落在了 V30 的 `order_photo_slot` / `order_photo` 两张表上——JSON 列没法给
--      「同一张照片不能挂两个订单」加约束，也没法给「三个槽位各至少一张」做计数。
--
-- 快照与现取的分界（这条是刻意选的，别随手改）：
--   - **快照**：`service_code` / `service_name` / `total_amount` / `pet_name` / `pet_species`。
--     订单是一份历史凭证——目录改名、服务项改价、宠物改名之后，这笔已发生的交易写的仍应是
--     当时的样子（与目录项「现取不存快照」相反，因为那说的是**服务目录**：目录改了，
--     服务者页面与 C 端要同时变，见 ADR-0034 决定 9）；
--   - **现取**：门店名称、预约人的昵称与脱敏手机号（订单不该留一个会过期的联系人快照，
--     门店要打的是**此刻**能打通的号码）。现取走 ph-api 的只读接口，不 join 别人的表（ADR-0006）。
--
-- `redeem_code` 与 `redeem_code_active`（ADR-0038 第二节）：
--   - 核销码是**可读出的 6 位数字**，不是安全凭证（不防截图、不防转发）。它的风险由
--     「谁有权核销 + 核销幂等 + 核销需要服务者在场」兜住；
--   - 唯一键加在 `redeem_code_active` 而不是 `redeem_code` 上：6 位数字只有 100 万种，
--     已经结束（完成 / 取消）的订单把码一直占着，迟早会把码位吃光。所以订单进入终态时
--     把 `redeem_code_active` 置空（MySQL 的唯一键不约束 NULL），历史码仍留在 `redeem_code`
--     里可查可追。这也是「生成时撞码就重试」能收敛的前提。

CREATE TABLE `order` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no`              VARCHAR(32)     NOT NULL COMMENT '对用户可读的订单号（唯一；格式见 ADR-0048：yyyyMMddHHmmss + 4 位随机）',
  `user_id`               BIGINT UNSIGNED NOT NULL COMMENT '下单人（逻辑引用 user.id）',
  `pet_id`                BIGINT UNSIGNED NOT NULL COMMENT '宠物（逻辑引用 pet.id）',
  `pet_name`              VARCHAR(64)     NOT NULL COMMENT '下单时的宠物昵称快照',
  `pet_species`           TINYINT         NOT NULL DEFAULT 0 COMMENT '下单时的物种快照：1 犬 / 2 猫；0 表示档案侧没取到',
  `provider_id`           BIGINT UNSIGNED NOT NULL COMMENT '服务者（逻辑引用 provider.id）',
  `service_id`            BIGINT UNSIGNED NOT NULL COMMENT '服务项（逻辑引用 provider_service.id）',
  `service_code`          VARCHAR(32)     NOT NULL COMMENT '下单时的目录项编码快照（券的适用范围与价格区间校验挂它）',
  `service_name`          VARCHAR(128)    NOT NULL COMMENT '下单时的服务项名称快照',
  `appointment_date`      DATE            NOT NULL COMMENT '预约日期',
  `start_time`            VARCHAR(5)      NOT NULL COMMENT '预约时段开始（HH:mm）',
  `end_time`              VARCHAR(5)      NOT NULL COMMENT '预约时段结束（HH:mm）',
  `total_amount`          DECIMAL(10,2)   NOT NULL COMMENT '服务总额（下单时的定价；DECIMAL 不用浮点，ADR-0011）',
  `coupon_id`             BIGINT UNSIGNED          DEFAULT NULL COMMENT '本单占用的券（逻辑引用 coupon.id）；不用券为空',
  `coupon_discount`       DECIMAL(10,2)   NOT NULL DEFAULT 0.00 COMMENT '券面额抵扣（展示用；不用券时 0.00）',
  `estimated_pay_amount`  DECIMAL(10,2)   NOT NULL COMMENT '预估实付 = 总额 − 券面额。**展示用计算值，不是收款事实**（ADR-0036：钱在门店直接付给服务者）',
  `status`                TINYINT         NOT NULL DEFAULT 0 COMMENT '0待接单 1已预约 2履约中 3已完成 4已取消（ADR-0038 第一节；文档的待支付/已支付/已退款已废止）',
  `redeem_code`           CHAR(6)         NOT NULL COMMENT '6 位核销码（ADR-0038 第二节）。到预约时间才在 C 端可见，服务者侧不下发',
  `redeem_code_active`    CHAR(6)                  DEFAULT NULL COMMENT '未结束订单的核销码，唯一键只约束它；进入终态置空',
  `remark`                VARCHAR(255)             DEFAULT NULL COMMENT '用户下单时给门店的备注',
  `accepted_at`           DATETIME                 DEFAULT NULL COMMENT '接单时间',
  `redeemed_at`           DATETIME                 DEFAULT NULL COMMENT '核销时间（履约确认，**与收款无关**，ADR-0036）',
  `reported_at`           DATETIME                 DEFAULT NULL COMMENT '报工完成时间',
  `report_remark`         VARCHAR(255)             DEFAULT NULL COMMENT '报工说明（展示给用户）',
  `cancel_request_status` TINYINT                  DEFAULT NULL COMMENT '1待门店处理 2已同意 3已拒绝；为空表示没有取消申请',
  `cancel_requested_at`   DATETIME                 DEFAULT NULL COMMENT '用户发起取消申请的时间',
  `cancel_reason`         VARCHAR(255)             DEFAULT NULL COMMENT '取消 / 申请取消的理由',
  `cancel_rejected_reason` VARCHAR(255)            DEFAULT NULL COMMENT '门店拒绝取消的理由（拒绝必填，ADR-0038 第一节）',
  `cancelled_at`          DATETIME                 DEFAULT NULL COMMENT '进入「已取消」的时间',
  `cancelled_by`          TINYINT                  DEFAULT NULL COMMENT '1用户 2服务者 3运营（ADR-0038 第一节）',
  `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '下单人（与 user_id 相同；写操作留痕 ADR-0011）',
  `updated_by`            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最后一次推进状态的账号（服务者侧就是操作人）',
  `trace_id`              VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`            TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_redeem_code_active` (`redeem_code_active`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_provider_status` (`provider_id`, `status`),
  KEY `idx_redeem_code` (`redeem_code`),
  KEY `idx_slot` (`provider_id`, `service_id`, `appointment_date`, `start_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单：预约 + 履约（到店付，无支付 / 退款状态）';

-- 「30 分钟未支付自动取消」**没有对应的定时任务**：没有支付就没有这个待办（ADR-0038 第一节）。
-- 预约时间过了没核销也不自动取消——自动取消会让用户白跑一趟店；改为给门店发超时提醒、
-- 超 24 小时标记异常进看板。那两件事属于提醒与考核切片（#114），本切片不做。
