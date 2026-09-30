-- 三道照片墙（切片 #107，决策见 ADR-0040 第四节）
--
-- 接宠检查 / 服务防护 / 取宠对比，**三者缺一不可**——「缺一道则系统拒绝报工」是交付文档 2.5
-- 的验收标准，所以这条约束必须能由服务端算出来，而不是靠前端数数组长度。
--
-- 为什么是两张表而不是订单上的三个 JSON 列（交付文档 7.2 的
-- `check_in_photo / protect_photo / check_out_photo`）：
--   - JSON 列没法给「同一张照片不能挂在两个订单上」加约束，而那是「照片归属订单」的落点；
--     这里 `order_photo` 的 `uk_file_id` 让重复挂载在数据库层面不可能发生（应用侧再作一次
--     40400 的判定，为的是给调用方一个明确的答复，而不是一个 50000）；
--   - 「三个槽位各至少一张」需要一个**可信的计数**（`order_photo_slot.photo_count`），
--       JSON 数组的长度在 SQL 里只能解析着数，且清空与备注两个动作会互相覆盖。
--
-- 槽位行与照片行分开的另一个原因：**备注不随照片走**。用户（门店）可以先把「皮肤有红点，
-- 已拍照留痕」写好、照片稍后传；也可能把照片清空但留着说明。把备注放在照片行上，
-- 清空就成了「把说明一起删掉」——那是两个动作。

CREATE TABLE `order_photo_slot` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT UNSIGNED NOT NULL COMMENT '订单（逻辑引用 order.id）',
  `slot`        TINYINT         NOT NULL COMMENT '1接宠检查 2服务防护 3取宠对比',
  `photo_count` INT             NOT NULL DEFAULT 0 COMMENT '该槽位的照片数（服务端计数的来源；报工硬校验看它）',
  `remark`      VARCHAR(255)             DEFAULT NULL COMMENT '该槽位的备注',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_slot` (`order_id`, `slot`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '三道照片墙的槽位（照片 + 备注，PUT 整体替换）';

-- 一个槽位的一张照片。`file_id` 指向 V5 的 `file_object`（`biz_type=care`，ADR-0020 的直传），
-- **唯一键保证一张照片只属于一个订单**：服务留痕照片不进用户的档案照片墙（ADR-0040 / ADR-0030），
-- 复用到别的订单更是不能——同一个 file_id 出现在两张单上，谁也说不清它到底是谁的服务证据。
CREATE TABLE `order_photo` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `slot_id`    BIGINT UNSIGNED NOT NULL COMMENT '所属槽位（逻辑引用 order_photo_slot.id）',
  `order_id`   BIGINT UNSIGNED NOT NULL COMMENT '冗余订单 id：按订单取照片时少一次 join（本模块内的冗余，不是跨模块）',
  `file_id`    BIGINT UNSIGNED NOT NULL COMMENT '文件（逻辑引用 file_object.id，biz_type=care，归属订单）',
  `sort_order` INT             NOT NULL DEFAULT 0 COMMENT '槽位内的展示顺序（按下标存）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`   VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted` TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_id` (`file_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_slot` (`slot_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '照片墙里的照片（一处一张，归属订单）';
