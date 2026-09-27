-- 账号域：用户表
--
-- 与交付文档 7.2 的差异（都有依据，不是随手改的）：
--   1. 手机号不存明文：`phone_enc`（AES-256-GCM 密文）+ `phone_hash`（HMAC，唯一索引与等值查询用）——ADR-0013。
--      文档只写了「手机号字段级加密」的要求，没给做法，这条是补齐。
--   2. 去掉 `openid_wx` / `unionid`：ADR-0001 定了三个端全是 Web，没有微信登录，这两列没有来源。
--   3. 补审计列 `created_by` / `updated_by` / `trace_id` 与 `is_deleted`——ADR-0011 的统一约定。
--   4. `active_pet_id` 是新增列：多宠家庭记住「当前在看谁」，跨浏览器一致（切片 #94）。
--
-- 不建物理外键：模块之间（此处是 account ← record）本就不该有强耦合，
-- 且外键会拖累软删除与将来的数据迁移。引用完整性靠代码与唯一索引保证（ADR-0011）。

CREATE TABLE `user` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `phone_enc`      VARCHAR(255)    NOT NULL COMMENT '手机号密文（v1: 前缀 + base64(iv||ciphertext||tag)）',
  `phone_hash`     CHAR(64)        NOT NULL COMMENT '手机号 HMAC-SHA256 十六进制，等值查询与唯一约束用',
  `password_hash`  VARCHAR(100)    NOT NULL COMMENT 'bcrypt 哈希，绝不返回给前端',
  `nickname`       VARCHAR(64)     NOT NULL DEFAULT '宠物主人',
  `avatar`         VARCHAR(512)             DEFAULT NULL,
  `gender`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0未知1男2女',
  `active_pet_id`  BIGINT UNSIGNED          DEFAULT NULL COMMENT '当前选中的宠物，没有则为 NULL',
  `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常2禁用',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id，0 表示系统写入',
  `updated_by`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`       VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '1 已删除（账号注销才物理删除）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone_hash` (`phone_hash`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';
