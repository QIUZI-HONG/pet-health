-- 文件与对象存储（切片 #95，决策见 ADR-0020）
--
-- 一张表管两类对象：**原图**与其派生物（缩略图、局部特写）。派生物用 role + original_id 指向原图，
-- 不另立表——它们的生命周期与原图完全绑定，分开存只会多一处「删了原图忘了删缩略图」。
--
-- 状态机的三个取值是「取凭证 → 直传 → 落定」这条链路的产物：
--   0 待传  已签发上传地址，等浏览器送字节（有 expires_at，过期的由定时任务清）
--   1 已传  字节已落存储，可以读
--   2 已废弃 用户取消或凭证过期
--
-- 为什么需要「待传」这一行而不是把凭证做成纯无状态签名：对象存储的**回调验签**必须能对上一个
-- 预先存在的意图（否则拿到一个合法签名就能凭空造文件记录）。local 驱动直传即落定，cos 驱动
-- 靠回调落定，两者共用这张表。

CREATE TABLE `file_object` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_user_id`  BIGINT UNSIGNED NOT NULL COMMENT '归属用户（逻辑引用 user.id），读图按它鉴权',
  `pet_id`         BIGINT UNSIGNED          DEFAULT NULL COMMENT '关联宠物；证件类文件可不挂宠物',
  `biz_type`       VARCHAR(32)     NOT NULL COMMENT '业务场景：checkin 打卡 / epidemic 防疫 / profile 证件 / ai_consult 咨询 / care 护理照片',
  `role`           VARCHAR(16)     NOT NULL DEFAULT 'original' COMMENT 'original 原图 / closeup 局部特写 / thumb 缩略图',
  `original_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '派生物指向的原图 id；原图为 NULL',
  `mime`           VARCHAR(64)     NOT NULL COMMENT '仅 image/jpeg、image/png——**魔数判定后写入，不信请求头**',
  `size_bytes`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '字节数；待传时为声明的值，落定后以实际为准',
  `sha256`         CHAR(64)                 DEFAULT NULL COMMENT '内容摘要：查重与完整性核对',
  `width`          INT                      DEFAULT NULL COMMENT '图像宽度（落定时解析）',
  `height`         INT                      DEFAULT NULL COMMENT '图像高度（落定时解析）',
  `storage_driver` VARCHAR(16)     NOT NULL COMMENT 'local / cos；换驱动时历史行仍指着各自的对象键',
  `storage_key`    VARCHAR(255)    NOT NULL COMMENT '驱动内的对象键，全局唯一',
  `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0待传 1已传 2已废弃',
  `expires_at`     DATETIME                 DEFAULT NULL COMMENT '待传凭证的过期时间；落定后置 NULL',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_key` (`storage_key`),
  KEY `idx_owner_time` (`owner_user_id`, `created_at`),
  KEY `idx_pet_biz` (`pet_id`, `biz_type`),
  KEY `idx_pending` (`status`, `expires_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件对象（原图与派生物）';
