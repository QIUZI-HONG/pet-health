-- 标准服务目录（平台统一定义）——切片 #104 的目录部分，决策见 ADR-0034
--
-- 依据：交付文档 5.2（服务页分类清单：医院 / 洗护美容 / 训犬 / 寄养上门 / 食品用品 / 间接服务）、
-- 7.2 的 `merchant_service`（文档用词，「商家从标准目录勾选」）、F012「目录外服务须经平台审核」。
-- 文档没有给目录自身的表结构——F010 只说「服务目录由平台统一定义」，这两张表是按该要求补齐的。
--
-- 五处刻意的设计（前三条是 2026-09-29 项目所有者拍板的口径，见 ADR-0034）：
--   1. **两级结构**：分类 → 项目。编码规则「大类两字母 + 三位序号」：
--      HE 医院 / GR 洗护美容 / TR 训犬 / BD 寄养上门 / SP 食品用品 / IN 间接服务。
--      **编码一旦发布不可改、不可复用**——订单项（`order_item.service_code`）与券的适用范围
--      都挂在它上面；「下架」只改状态（`status=0`），绝不删行、绝不换码。
--   2. **价格区间随项目走，由运营维护**（`price_min` / `price_max`，存库不写死代码）：
--      交付文档 2.5 的验收是「商家定价 100% 区间校验」（文档用词），校验基准必须在平台侧。
--   3. **目录外服务不放开自由建项**：服务者提交提案 → 运营审核 → 通过后由平台入库成为正式项目
--      （对应文档 F012 与 2.5 的「目录外服务须经平台审核」）。**提案单与它的审核流水在 ph-provider**
--      （`catalog_item_proposal`，V23）：提案是服务者的申请动作，审核通过才由 ph-provider 经
--      ph-catalog 的接口（`CatalogItemApi`）建出这里的正式项目——依赖是单向的 provider → catalog，
--      没有环（ADR-0006 的接口通信）。
--   4. 停用不等于删除：`status=0` 只挡住「新的选品」，已上架的服务项不自动下架——
--      自动下架会打断已预约的订单，而本项目没有推送通道去通知用户（见 ADR-0034）。
--   5. 表名用 `service_*` 而不是文档的 `merchant_*`（文档用词）：领域术语以 CONTEXT.md 为准，
--      「商家 / merchant」（文档用词）是禁用词，代码标识符一律用 provider。

CREATE TABLE `service_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32)     NOT NULL COMMENT '分类编码，大写英文：HOSPITAL / GROOMING / TRAINING / BOARDING / SUPPLIES / INDIRECT',
  `name`        VARCHAR(64)     NOT NULL COMMENT '分类名（医院 / 洗护美容 / 训犬 / 寄养上门 / 食品用品 / 间接服务）',
  `item_code_prefix` VARCHAR(8)  NOT NULL COMMENT '该项目所属分类的目录项编码前缀（HE / GR / TR / BD / SP / IN）——编码不可改，所以前缀与分类绑定',
  `icon`        VARCHAR(64)              DEFAULT NULL COMMENT '图标标识（不是图形本身——配色与形状以视觉稿为准，ADR-0008）',
  `description` VARCHAR(255)             DEFAULT NULL,
  `sort_order`  INT             NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用后不再出现在服务者选品与 C 端浏览里）',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作者 id（平台运营），0 表示系统写入',
  `updated_by`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`    VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '最后一次写入的链路 ID',
  `is_deleted`  TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  UNIQUE KEY `uk_item_code_prefix` (`item_code_prefix`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标准服务目录分类（一级）';

CREATE TABLE `service_item` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)     NOT NULL COMMENT '项目编码，形如 HE-001（交付文档 7.2 的 order_item.service_code）。发布后不可改、不可复用',
  `category_code`    VARCHAR(32)     NOT NULL COMMENT '所属分类编码（逻辑引用 service_category.code，不建物理外键）',
  `name`             VARCHAR(128)    NOT NULL,
  `price_min`        DECIMAL(10,2)   NOT NULL COMMENT '价格区间下限（元）——服务者定价不得低于它',
  `price_max`        DECIMAL(10,2)   NOT NULL COMMENT '价格区间上限（元）——服务者定价不得高于它',
  `price_unit`       VARCHAR(16)     NOT NULL DEFAULT '次' COMMENT '计价单位：次 / 只 / 天 / 课时 / 件 / 年',
  `duration_minutes` INT                      DEFAULT NULL COMMENT '参考时长（分钟），排班与展示用；不确定则为 NULL',
  `applicable_pets`  TINYINT         NOT NULL DEFAULT 3 COMMENT '1犬2猫3犬猫',
  `description`      VARCHAR(512)             DEFAULT NULL,
  `source`           TINYINT         NOT NULL DEFAULT 1 COMMENT '1平台自建2服务者提案审核通过后入库（F012 的目录外服务）',
  `sort_order`       INT             NOT NULL DEFAULT 0,
  `status`           TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用0停用（停用只挡新的选品，不自动下架存量）',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `trace_id`         VARCHAR(64)     NOT NULL DEFAULT '',
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_category_status_sort` (`category_code`, `status`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标准服务目录项（二级，含价格区间）';

-- 初始目录：分类与项目来自交付文档 5.2 的分类清单。
--
-- **价格区间是初始估计值，不是定价依据**（项目所有者 2026-09-29 的口径）：以该项目的市场参考价 P
-- 为基准取 [0.7P, 1.3P]（±30%），由运营在运营后台按实际供需调整——区间存库、不写死在代码里，
-- 改区间不需要发版。运营改了区间只影响**后续**定价与改价：已上架的服务项不自动下架
-- （打断已预约订单的代价更大，见 ADR-0034）。
INSERT INTO `service_category` (`code`, `item_code_prefix`, `name`, `icon`, `description`, `sort_order`) VALUES
  ('HOSPITAL', 'HE', '医院',     'hospital', '疫苗 / 体检 / 驱虫 / 绝育 / 诊疗', 1),
  ('GROOMING', 'GR', '洗护美容', 'grooming', '洗护 / 美容造型 / 药浴 SPA',       2),
  ('TRAINING', 'TR', '训犬',     'training', '行为训练 / 行为矫正 / 咨询',       3),
  ('BOARDING', 'BD', '寄养上门', 'boarding', '寄养 / 上门喂养',                  4),
  ('SUPPLIES',   'SP', '食品用品', 'goods',    '粮 / 零食 / 营养品 / 用品',         5),
  ('INDIRECT', 'IN', '间接服务', 'indirect', '摄影 / 殡葬 / 保险 / 托运等',       6);

-- 区间 = 市场参考价 ±30%（全部取整到分）。参考价一栏写在注释里，便于日后复核这版种子值的来源。
INSERT INTO `service_item` (`code`, `category_code`, `name`, `price_min`, `price_max`, `price_unit`, `duration_minutes`, `applicable_pets`, `sort_order`) VALUES
  -- 医院：疫苗 / 体检 / 驱虫 / 绝育 / 诊疗 / 检查（文档 5.2 医院行）
  ('HE-001', 'HOSPITAL', '猫三联疫苗',             84.00,   156.00, '次',   20,  2, 1),   -- P=120
  ('HE-002', 'HOSPITAL', '狂犬疫苗',               70.00,   130.00, '次',   20,  3, 2),   -- P=100
  ('HE-003', 'HOSPITAL', '犬联苗（四/六/八联）',    140.00,  260.00, '次',   20,  1, 3),   -- P=200
  ('HE-004', 'HOSPITAL', '基础体检',               112.00,  208.00, '次',   30,  3, 4),   -- P=160
  ('HE-005', 'HOSPITAL', '标准体检',               280.00,  520.00, '次',   60,  3, 5),   -- P=400
  ('HE-006', 'HOSPITAL', '深度体检',               700.00, 1300.00, '次',  120,  3, 6),   -- P=1000
  ('HE-007', 'HOSPITAL', '术前检查',               280.00,  520.00, '次',   60,  3, 7),   -- P=400
  ('HE-008', 'HOSPITAL', '幼宠体检',               140.00,  260.00, '次',   30,  3, 8),   -- P=200
  ('HE-009', 'HOSPITAL', '老年宠体检',             350.00,  650.00, '次',   60,  3, 9),   -- P=500
  ('HE-010', 'HOSPITAL', '体内外驱虫',             105.00,  195.00, '次',   15,  3, 10),  -- P=150
  ('HE-011', 'HOSPITAL', '绝育手术',               840.00, 1560.00, '次', NULL,  3, 11),  -- P=1200
  ('HE-012', 'HOSPITAL', '常见病诊疗',             140.00,  260.00, '次',   30,  3, 12),  -- P=200
  ('HE-013', 'HOSPITAL', '专项检查（影像/化验）',   350.00,  650.00, '次',   60,  3, 13),  -- P=500
  -- 洗护美容
  ('GR-001', 'GROOMING', '基础洗护（小型犬）',      84.00,  156.00, '次',   90,  1, 1),   -- P=120
  ('GR-002', 'GROOMING', '基础洗护（中型犬）',     126.00,  234.00, '次',  120,  1, 2),   -- P=180
  ('GR-003', 'GROOMING', '基础洗护（大型犬）',     196.00,  364.00, '次',  150,  1, 3),   -- P=280
  ('GR-004', 'GROOMING', '基础洗护（猫）',         112.00,  208.00, '次',   90,  2, 4),   -- P=160
  ('GR-005', 'GROOMING', '美容造型',               280.00,  520.00, '次',  150,  3, 5),   -- P=400
  ('GR-006', 'GROOMING', '药浴 / SPA',             196.00,  364.00, '次',  120,  3, 6),   -- P=280
  ('GR-007', 'GROOMING', '开结',                   105.00,  195.00, '次',   60,  3, 7),   -- P=150
  ('GR-008', 'GROOMING', '刷牙',                    42.00,   78.00, '次',   20,  3, 8),   -- P=60
  ('GR-009', 'GROOMING', '剪指甲',                  28.00,   52.00, '次',   15,  3, 9),   -- P=40
  ('GR-010', 'GROOMING', '挤肛门腺',                35.00,   65.00, '次',   15,  3, 10),  -- P=50
  -- 训犬
  ('TR-001', 'TRAINING', '基础行为训练',           420.00,  780.00, '课时', 60,  1, 1),   -- P=600
  ('TR-002', 'TRAINING', '行为矫正',               700.00, 1300.00, '课时', 60,  1, 2),   -- P=1000
  ('TR-003', 'TRAINING', '营养咨询',               140.00,  260.00, '次',   30,  3, 3),   -- P=200
  ('TR-004', 'TRAINING', '宠物接送',                56.00,  104.00, '次', NULL,  1, 4),   -- P=80
  ('TR-005', 'TRAINING', '训练期寄存',             140.00,  260.00, '天', NULL,  1, 5),   -- P=200
  -- 寄养 / 上门
  ('BD-001', 'BOARDING', '犬寄养',                  84.00,  156.00, '天', NULL,  1, 1),   -- P=120
  ('BD-002', 'BOARDING', '猫寄养',                  77.00,  143.00, '天', NULL,  2, 2),   -- P=110
  ('BD-003', 'BOARDING', '豪华寄养',               245.00,  455.00, '天', NULL,  3, 3),   -- P=350
  ('BD-004', 'BOARDING', '上门喂养',                63.00,  117.00, '次',   60,  3, 4),   -- P=90
  -- 食品用品
  ('SP-001', 'SUPPLIES',   '主粮',                   210.00,  390.00, '件', NULL,  3, 1),   -- P=300
  ('SP-002', 'SUPPLIES',   '零食',                    70.00,  130.00, '件', NULL,  3, 2),   -- P=100
  ('SP-003', 'SUPPLIES',   '营养品',                 126.00,  234.00, '件', NULL,  3, 3),   -- P=180
  ('SP-004', 'SUPPLIES',   '玩具',                    42.00,   78.00, '件', NULL,  3, 4),   -- P=60
  ('SP-005', 'SUPPLIES',   '牵引 / 胸背',             84.00,  156.00, '件', NULL,  1, 5),   -- P=120
  ('SP-006', 'SUPPLIES',   '试吃装',                  14.00,   26.00, '件', NULL,  3, 6),   -- P=20
  -- 间接服务
  ('IN-001', 'INDIRECT', '宠物摄影',               420.00,  780.00, '次', NULL,  3, 1),   -- P=600
  ('IN-002', 'INDIRECT', '宠物殡葬',              1050.00, 1950.00, '次', NULL,  3, 2),   -- P=1500
  ('IN-003', 'INDIRECT', '宠物保险',               560.00, 1040.00, '年', NULL,  3, 3),   -- P=800
  ('IN-004', 'INDIRECT', '宠物托运',               350.00,  650.00, '次', NULL,  3, 4),   -- P=500
  ('IN-005', 'INDIRECT', '宠物友好场所',            42.00,   78.00, '次', NULL,  3, 5),   -- P=60
  ('IN-006', 'INDIRECT', '智能硬件',               420.00,  780.00, '件', NULL,  3, 6);   -- P=600
