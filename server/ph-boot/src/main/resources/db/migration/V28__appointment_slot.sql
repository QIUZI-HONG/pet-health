-- 号源（切片 #109，决策见 ADR-0038 第一节）
--
-- 号源是「服务项在特定日期与时段上可被预约的**容量单位**」（CONTEXT.md 的号源条目）。
--
-- 三条刻意的设计：
--   1. **按时段一行**：唯一键 `(provider_id, service_id, slot_date, start_time)` 就是「一个时段」。
--      占用与释放都是**原子条件更新**（`booked_count < capacity` / `booked_count > 0`），
--      不靠先查后写。ADR-0038 要求「并发抢同一时段，后到者得到 40900」——
--      在本表上就是 `UPDATE ... WHERE booked_count < capacity` 影响 0 行。
--   2. **行是懒建的**：时段网格由营业时间算出来（见 ADR-0048），第一次被预约时才落一行，
--      所以「30 天后的空时段」不会提前占满整张表。没落行的时段按 `capacity` 默认值展示。
--   3. **`booked_count` 含履约中与已完成的订单**：服务做完了也不释放号源（那段营业时间
--      已经被用掉），只有取消才 `-1`（ADR-0038 / 契约里写死的口径）。
--
-- `capacity` 默认 1：ADR-0038 只写了「按时段容量占用」，没写一个时段能约几个、由谁给
-- （契约里也标着待澄清）。这里先落最小可用形态——**每时段默认 1**，运营可按行调大。
-- 口径与取舍见 ADR-0048 的「待澄清」。

CREATE TABLE `appointment_slot` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_id`  BIGINT UNSIGNED NOT NULL COMMENT '服务者（逻辑引用 provider.id）',
  `service_id`   BIGINT UNSIGNED NOT NULL COMMENT '服务项（逻辑引用 provider_service.id）：号源挂在服务项上',
  `slot_date`    DATE            NOT NULL COMMENT '日期',
  `start_time`   VARCHAR(5)      NOT NULL COMMENT '时段开始（HH:mm）',
  `end_time`     VARCHAR(5)      NOT NULL COMMENT '时段结束（HH:mm）',
  `capacity`     INT             NOT NULL DEFAULT 1 COMMENT '该时段的容量（默认 1；运营可按行调大）',
  `booked_count` INT             NOT NULL DEFAULT 0 COMMENT '已占用数；含履约中与已完成（ADR-0038），取消才释放',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`     VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_slot` (`provider_id`, `service_id`, `slot_date`, `start_time`),
  KEY `idx_provider_date` (`provider_id`, `slot_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '号源：服务项按时段的可预约容量';
