-- 合规文档（切片 #74）：隐私政策 / 用户协议 / AI 免责声明
--
-- **正文是占位**，不是法律文本——ADR-0025 定的口径：工程交付功能与组件，
-- 文本内容不自拟（工程写一份看起来像法律文本的东西，比空着更危险）。
-- `is_placeholder` 就是这件事的机器可读记号：前端据此显示「待法务定稿」的提示，
-- 而不是把占位文字当成生效条款展示给用户。
--
-- 做成表而不是写在代码里：条款会随法务意见与备案要求变，改一次发一次版不合理；
-- 而且改了要有版本与生效时间可查（`version` + `effective_from`）。

CREATE TABLE `compliance_document` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`            VARCHAR(32)     NOT NULL COMMENT 'privacy_policy / user_agreement / ai_disclaimer',
  `title`           VARCHAR(128)    NOT NULL,
  `body`            MEDIUMTEXT      NOT NULL COMMENT '正文；占位期是说明文字，不是条款',
  `version`         VARCHAR(32)     NOT NULL DEFAULT 'v0-placeholder',
  `effective_from`  DATE                     DEFAULT NULL COMMENT '生效日期；占位期为空',
  `is_placeholder`  TINYINT         NOT NULL DEFAULT 1 COMMENT '1=待法务定稿。**前端据此提示，不要当生效条款展示**',
  `remark`          VARCHAR(256)             DEFAULT NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`        VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '合规文档（占位文本，待法务定稿）';

INSERT INTO `compliance_document` (`code`, `title`, `body`, `remark`) VALUES
  ('privacy_policy', '隐私政策',
   '【占位文本 · 待法务定稿】本页将说明：收集哪些信息（账号、宠物档案、健康记录、上传的照片）、为什么收集、保存多久、与谁共享（仅限你选择的服务者与法定情形）、你有哪些权利（查看、更正、导出、注销）。当前版本未经法律审阅，不作为生效条款。',
   '待法务/甲方定稿；定稿后把 is_placeholder 置 0 并填 version 与 effective_from'),
  ('user_agreement', '用户协议',
   '【占位文本 · 待法务定稿】本页将说明：平台提供什么（AI 健康咨询与就医紧迫程度提示、服务者匹配）、不提供什么（不做诊断、不开处方、不经手资金）、账号规则与责任边界。当前版本未经法律审阅，不作为生效条款。',
   '待法务/甲方定稿'),
  ('ai_disclaimer', 'AI 免责声明',
   '本平台的 AI 健康咨询给出的是**就医紧迫程度提示**，不是诊断结论：它基于宠物的健康档案与公开知识库，无法替代兽医的面诊与检查。出现红色风险提示时请立即送医；任何用药与处置请由兽医决定。',
   '这份是**可以先用**的：它是产品对自身能力边界的声明，不涉及对外法律责任分配；上线前仍建议一并过法务');
