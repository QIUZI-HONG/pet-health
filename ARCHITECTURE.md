# 架构说明

这份文档讲**「东西是怎么连起来的」**：运行时拓扑、后端模块边界、三条核心数据流、跨切面机制，
以及一份「想改某处该看哪个文件」的索引。

它**不重复**别的文档已经写清楚的东西——那些在下面这张表里指过去。

| 想了解 | 看 |
| --- | --- |
| 怎么把项目跑起来、环境变量、本机特有的坑 | [`README.md`](README.md) |
| 领域术语（**命名以它为准**） | [`CONTEXT.md`](CONTEXT.md) |
| 某个决策为什么这么定 | [`docs/adr/`](docs/adr/)（54 条） |
| 分页 / 脱敏 / 加密 / 迁移 / 越权口径这些实现级约定 | [`docs/conventions.md`](docs/conventions.md) |
| 后端跑法、迁移与回滚规程 | [`server/README.md`](server/README.md) |
| AI 服务的设计细节 | [`docs/design/ai-service.md`](docs/design/ai-service.md) |

---

## 1. 定位与形态

以 AI 为基座、24 小时监护宠物健康的平台：先让用户低成本自助解决问题，解决不了再匹配服务者完成交易。

**四个可执行单元，全部在一个单仓里**（ADR-0005）：

| 单元 | 技术 | 面向谁 | 状态 |
| --- | --- | --- | --- |
| C 端 `apps/c-web` | Vue 3 + TS + Vite + Pinia | 宠物主人 | 24 条路由 |
| 服务者后台 `apps/provider-web` | 同上（共用 `packages/ui`） | 服务者（门店/医院/技师） | 11 个模块 |
| 运营后台 `apps/admin-web` | 同上（共用 `packages/ui`） | 平台运营 | 11 个模块 |
| 后端 `server/` | Java 17 + Spring Boot 3 + MyBatis | 上面三个端 + AI 服务 | 模块化单体 |
| AI 服务 `ai/` | Python 3.14 + FastAPI | 只被后端调用 | 4 个内部能力 |

三端**全是 Web**——不做小程序、不做 APP（ADR-0001 对交付文档的刻意偏离之一）。

## 2. 运行时拓扑

```
 浏览器
   │  c-web :5173   provider-web :5174   admin-web :5175
   │  （dev 时 Vite 把 /api/v1/app|provider|admin 代理到后端）
   ▼
┌─────────────────────────────────────────────┐
│ server/  Spring Boot  :8080                 │
│  ph-boot（启动 + Flyway 迁移）                │
│  ├── 10 个领域模块（模块化单体，ADR-0006）      │
│  └── ph-ai ──HTTP──▶ ai/ FastAPI :8000       │
└──────┬──────────────────────────┬───────────┘
       │                          │
   MySQL :3307                Redis :6379
   （Flyway 管理 schema）      （限流 / 幂等 / 缓存 / 会话）
```

- **AI 服务可以不在**：后端对它的调用有超时与降级（见 §7.3），AI 挂了 C 端会看到降级话术而不是报错。
- AI 服务**不直连业务库**：对数据库只读 `knowledge_*` 表，其余数据一律走后端 HTTP（ADR-0009）。
- 生产部署编排在 `deploy/`（`docker-compose.dev.yml` 是本地中间件；`prometheus/alerts.yml` 是告警规则，
  **尚未在真实 Alertmanager 上加载过**——ADR-0029 里如实标注了）。

## 3. 仓库布局

```
pet-health/
├── contract/          OpenAPI 契约：前后端类型的唯一真源（172 个路径）
├── server/            后端（13 个 Maven 模块 + 1 个聚合 POM）
├── ai/                AI 服务（Python）
├── apps/              三个 Web 端（pnpm workspace）
├── packages/          三端共享：shared（请求层/字典/格式化/契约类型）· ui（组件/token）· config
├── docs/              ADR、约定、调研、验收报告、甲方交付文档、视觉稿
├── deploy/            本地中间件编排与告警规则
└── .github/workflows/ 三条流水线：server.yml · web.yml · ai.yml
```

**脚本放哪**（原先三端各有一份拷贝，现已收敛）：

- 跨端复用的构建脚本 → `packages/config/scripts/`，由各端 `package.json` 以相对路径调用
- 单模块自己的 → `server/scripts/`（回滚演练）、`packages/shared/scripts/`（契约生成类型）

## 4. 后端：模块与边界

### 4.1 模块与依赖方向

```
ph-common          统一响应 / 异常 / 追踪 / 字段加密 / 持久层约定 / 登录域（无内部依赖）
   ▲
ph-api             跨模块端口 + 全部接口 DTO（手写，与 contract/ 对齐）
   ▲
ph-catalog  ph-file                    标准目录与区间价 / 文件与对象存储
   ▲
ph-privilege                          券池 / 邀请 / 积分 / 权益引擎
   ▲
ph-record                             宠物 / 档案分项 / 打卡 / 评分 / 报告 / 专项照护
   ▲
ph-reminder                           提醒生成与批算 / 消息中心
   ▲
ph-account  ph-content                账号会话与合规 / 社区与内容审核
   ▲
ph-order    ph-ai                     订单状态机与三道照片墙 / AI 咨询留痕与配额
   ▲
ph-provider                           服务者入驻与资质 / 选品定价 / 考核
   ▲
ph-boot                               唯一的启动与装配模块，也是全部接口测试的所在
```

依赖是**单向无环**的，而且不是靠自觉：`ModuleBoundaryTest` 断言跨模块 import 只能落在对方的
`api` 或 `event` 包，基线为零。

### 4.2 分层：Controller 在哪、DTO 在哪

| 层 | 在哪 | 说明 |
| --- | --- | --- |
| Controller | **域模块的 `web/` 包**（如 `ph-order/.../order/web/AppOrderController.java`） | 42 个 `@RestController` |
| DTO | **`ph-api`**（如 `ph-api/.../api/order/`） | 手写，与 `contract/` 的 YAML 对齐；用于跨模块传递 |
| Service / Mapper | 各域模块的 `service/` `mapper/` | Mapper 无 XML，`BaseMapper` + 注解 SQL |
| 迁移脚本 | `ph-boot/src/main/resources/db/migration/` | Flyway；**每条都配 `db/undo/` 回滚脚本**（ADR-0011） |

> 别把 Controller 写进 `ph-api`、也别把 DTO 写进域模块——这两条由上面的结构护栏与惯例共同维持。

### 4.3 模块间怎么通信（两种机制，不要发明第三种）

**① 同步端口 `*Api`**：调用方声明接口，被调方实现。跨模块拿数据的默认方式。

| 端口 | 方向 | 干什么 |
| --- | --- | --- |
| `ProviderReportArchiveApi` | order → record | 报工回写健康档案 |
| `ProviderRatingApi` | order → provider | 评价回写门店评分 |
| `PetFactsApi` / `PetQueryApi` | → record | 读宠物快照 |
| `CouponApi` / `PointsApi` / `RightsApi` | → privilege | 券的校验/锁定/核销、积分发放、权益判定 |
| `CatalogItemApi` / `CatalogPricingApi` / `SymptomRuleApi` | → catalog | 目录项、区间价、症状推荐规则 |

**② AFTER_COMMIT 领域事件**：副作用（发积分、发券、写提醒）走事件，别拖长主事务。用的是 Spring
`ApplicationEventPublisher`，**没有独立消息中间件**（ADR-0003 精简中间件）。

| 事件 | 监听方 | 干什么 |
| --- | --- | --- |
| `OrderReviewedEvent` | `OrderReviewPointsListener` | 评价 → 发积分 |
| `CheckInSubmittedEvent` | `CheckInRewardListener` / `CheckInPointsListener` | 打卡 → 发券 / 发积分 |
| `CheckInRecordedEvent` | `CheckInRecordedListener`（ph-reminder） | 打卡 → 写消息中心 |

### 4.4 已知的结构性欠账：端口有「两个家」

跨模块端口目前散在两处，**这是已知问题、不是约定**：

- `ph-api` 里 **10 个**：`UserQueryApi` / `AiConsultStatsApi` / `CouponFactsApi` /
  `ProviderGrowthFactsApi` / `ProviderAccessApi` / `ProviderRatingApi` / `ProviderServiceQueryApi` /
  `PetFactsApi` / `ProviderReportArchiveApi` / `BusinessMessageApi`
- 域模块自己的 `api/` 包里另有 **16 个**：ph-catalog 4（另有两个值对象 `Price` / `PriceRange`）、
  ph-file 2、ph-privilege 4、ph-record 4、ph-order 1、ph-reminder 1

后果是**新端口没有唯一去处**，`ModuleBoundary` 的两条放行规则（`api` 既在 `SHARED_MODULES` 又在
`OPEN_LAYERS`）恰好都放行，所以永远不会报警。命名也跟着分叉成
`*QueryApi` / `*FactsApi` / `*StatsApi` / `*AccessApi` / `*RatingApi` / `*ExportApi` / 裸 `*Api`。

**要动它**：先把归属定成一处（建议统一进 `ph-api`），再给 `ModuleBoundary` 加一条禁止回潮的规则。
这是一次纯结构调整，但面很大（**26 个端口**），所以留在下一轮而不是夹在本次优化里。

## 5. 三条核心数据流

### 5.1 AI 咨询（唯一会被模型看到的链路）

```
AiConsultView.vue
  └─ cApp.consultAi（packages/shared/src/api/cApp.ts）
     └─ POST /api/v1/app/pets/{petId}/ai-consults
        └─ AiConsultController#consult
           └─ AiConsultService#consult
              ├─ petApi.snapshotOwnedBy        取宠物快照（越权在这里拦）
              ├─ fileUrlApi.readUrls           把档案照片换成签名读地址
              └─ AiServiceClient#consult ──HTTP〔X-Internal-Token〕──▶ ai/app/main.py#consult
```

Python 侧的顺序**本身就是安全设计**（每一步的位置都不能换）：

| 步 | 做什么 | 备注 |
| --- | --- | --- |
| 1 | `ops.load()` | 读运营可调项：提示词 / 红线词 / 分级规则 / 护栏词 / 开关（ADR-0010） |
| 2 | **`red_flags.match()`** | **命中硬红线即返回急诊建议，`model_name=rule:red_flag`——根本不调模型** |
| 3 | `ops.escalate()` | 按规则算出风险下限 |
| 4 | `knowledge.search()` | 三层检索：L1 结构化目录 → L2 关系层（`knowledge_node`/`knowledge_edge`）→ L3 ngram 全文 |
| 5 | `prompts.build_user_prompt()` → `model_client.assess()` | 模型调用的唯一出口 |
| 6 | `_review_citations()` → `_apply_escalation()` | 复核引用来源、按规则上调等级 |
| 7 | **`guardrails.review()`** | 输出护栏：拦「确诊 / 处方 / 剂量 / 药名」 |

- **风险分级（绿/黄/红）在 Python 侧决定**，不在 Java。配置在库：
  `knowledge_red_flag` / `knowledge_grading_rule` / `knowledge_guard_term` / `knowledge_switch` /
  `knowledge_prompt_template`——所以改分级规则不用发版。
- **留痕**：`AiConsultService#save` 写 `ai_consult`（**没有独立会话/消息分表**）；转人工写
  `human_consult_request`。
- **降级**共 4+2 类：Python 侧 `image_not_supported` / `image_unavailable` / `model_unavailable` /
  `model_output_invalid`（另有 `knowledge_empty`），Java 侧 `ai_service_unreachable`。
  每条都有中文话术，**前端只看到话术、看不到错误码**（`userFacingReason`）。
- **配额与预算**：`countToday` 数 `ai_consult` 当日行数，**只计数不拦截**；日预算告警是
  `AiBudgetMonitor#checkDailyBudget`。真正的拦截口径见 ADR-0024。
- **检索为空不是异常**：`citations` 为空是因为种子知识库里还没有 `vetted` 条目，
  不是检索层没接（三层都已实现，ADR-0033）。

### 5.2 交易：下单 → 核销 → 报工 → 档案回流

```
AppOrderController#create
  └─ OrderService#create
     ├─ couponApi.check                 先复核券可用性
     ├─ slots.occupy → AppointmentSlotMapper#tryOccupy
     │     条件 UPDATE：WHERE booked_count < capacity（并发抢同一时段靠这个）
     ├─ insert service_order
     └─ couponApi.lock → CouponService#lock
           **CAS 式条件 UPDATE**：status=1 或 (status=2 且 locked_order_id=0) 时置 2
           ——不是乐观锁版本号、不是行锁、不是幂等键
```

订单状态机 `OrderStatus`：`0 待接单 / 1 已预约 / 2 履约中 / 3 已完成 / 4 已取消`。
每次流转都是**条件更新**（`markAccepted` / `markRedeemed` / `markReported`），影响 0 行就返回 40900。

**到店核销**：`ProviderOrderController#redeem` → `OrderService#redeem` → `markRedeemed` +
`couponApi.redeem`。

**三道照片墙**（ADR-0040）：`1 接宠检查 / 2 服务防护 / 3 取宠对比`，照片落 `order_photo_slot` /
`order_photo`。硬约束在**后端**——

```
OrderService#report
  └─ OrderPhotoWallService#missingSlots(orderId)
       非空 → 抛 40900（前端禁用按钮只是提示，绕过前端也没用）
```

**报工 → 健康档案**走同步端口，不是事件（这条要有结果，报工得确认档案写成功）：

```
OrderService#report → archiveReports.recordServiceReport
  → ProviderReportArchiveApi（order → record）
  → ArchiveSectionService#recordServiceReport → 写 archive_record（source=3＝服务者报工）
```

**评价 → 门店评分**：`OrderReviewService#create` 写 `order_review` →
`recalculateProviderRating` → `ProviderRatingAdapter#updateRating` → 只改 `provider.rating` 一列。

### 5.3 增长：券池 / 邀请 / 积分 / 权益

**券的生命周期**：

```
模板        AdminCouponController#create        → coupon_template
贡献        ProviderCouponController#commit     → CouponContributionService#commit → coupon_contribution
发放        CouponService#issue
              ├─ templateMapper.selectForUpdate   （行锁）
              └─ pickContribution                （SELECT … FOR UPDATE，按 id 升序取首条有余量的）
核销        CouponService#redeem
过期释放    CouponExpiryJob#sweep
结算口径    CouponQuota：issued = redeemed + reserved + expired；CouponMapper#statOfContribution
```

> **与交付文档的差异**：文档 BPM-3 写「系统自动匹配最优可用券」，**实现是用户手选**——
> C 端 `OrderCreateView` 拉券后按 `appliesToProvider` 过滤，服务端用 `check` 复核。
> 要变成自动匹配得新写一段选优算法（后端目前没有这个能力）。

**邀请**：`InviteService#ensureInviteCode` 生成 8 位一人一码；**绑定时机是注册那一刻**
（`AccountService#attributeInvite` → `InviteService#attribute`）；阶梯表 `invite_ladder_tier`
五档（1/3/5/10/15），**种子里的 `reward_type` 全是 NULL**（奖励物待运营配）。

> 被邀请人实得走的是**积分**路径（`pointsApi.award(INVITE_INVITEE, …)`），且 `V38` 种子给的
> `points=0`——即机制在、默认不发。交付文档 BPM-2 说的是「双方各得 20 元券」，
> 要照文档做需要给被邀请人侧新增一条发券路径（代码改动，不是改配置）。

**积分**：行为分值与频次在 `point_behavior`，**日上限在 `PointsService#firstBlockingReason` 强制**
（`DAILY_LIMIT`，默认 20）；任务中心按今日/本周 `point_record` 次数算。

**权益引擎**：`rights_code` 四个码（ADR-0045）——

| 码 | 判定 | 当前消费点 |
| --- | --- | --- |
| `community.post` | `RightsEngine#evaluate` | **有**：`PostingAccess#check` 控制社区发帖 |
| `ai.unlimited` | 同上 | **暂无代码消费点** |
| `quota.ai.bonus` | 同上 | **暂无代码消费点** |
| `report.full` | 同上 | 仅作常量下发 |

**月度考核**：`AssessmentCalculator#evaluate` 总分 = Σ(参与项分 × 权重) / Σ(权重)，
权重 40/40/20 配在 `assessment_rule`。**拉新与券两项未接线**（`ProviderGrowthFactsApi` 在 `ph-api`
声明了、`ph-privilege` 没有实现，只有测试里有等价桩），所以那两项 `participated=false`，
明细里如实标注「未接线」。

## 6. 契约怎么流转

```
contract/*.yaml  ──gen-api-types.mjs──▶  packages/shared/src/api/*.d.ts
   （唯一真源）        (openapi-typescript)   （生成物入库，禁止手改）
                                │
                                ├──▶ apps/*/src/api/*.ts   视图层再包一层，派生自生成的类型
                                └──▶ server 的 DTO **手写**，但必须与 YAML 对齐
```

- **前端不手写接口类型**：三个应用的 `api/*.ts` 全部由生成的 `Schemas` 派生；里面出现的
  `interface` 都是视图模型（如 `OrderStatusView`），不是接口契约。
- **CI 会查同步**：`web.yml` 跑 `gen:api:check`。改了 YAML 忘了重新生成 → 流水线红。
  所以**契约与生成物必须同一次提交**。
- 协作约定见 ADR-0047。

## 7. 跨切面机制

### 7.1 三个独立登录域

`LoginDomain`（枚举在 `ph-common/.../security/LoginDomain.java`）与接口前缀绑定：

| 域 | 前缀 | 谁用 |
| --- | --- | --- |
| `APP` | `/api/v1/app/` | C 端 |
| `PROVIDER` | `/api/v1/provider/` | 服务者后台 |
| `ADMIN` | `/api/v1/admin/` | 运营后台 |

令牌**不跨域通用**（ADR-0012）。账号本身是同一批（ADR-0035：任何人注册后提交入驻申请即成为服务者）；
运营后台额外有一道**账号白名单** `CONSOLE_ADMIN_USER_IDS`，**fail-closed：不配名单＝没人能进**。

### 7.2 安全

- **字段加密**：手机号等敏感字段 AES-256（ADR-0013）
- **限流**：Redis 滑动窗口，认证接口与其余接口两档（`RATE_LIMIT_AUTH` / `RATE_LIMIT_DEFAULT`）
- **幂等**：写接口可选带 `Idempotency-Key`，同键重放首次响应；不带该头的请求行为完全不变（ADR-0028）
- **越权**：所有资源访问校验归属，跨模块取数也走带归属校验的端口（如 `snapshotOwnedBy`）
- **审计**：所有写操作留 `operator_id` 与 `trace_id`；表继承 `BaseEntity`（ADR-0011）

### 7.3 可观测与降级

- 每行日志带 `traceId`（MDC），每个 `/api/` 请求留一行访问日志——「按 traceId 追一次请求」
  真的可用（ADR-0029）
- 指标走独立管理端口 `9090` 的 `/actuator/prometheus`；AI 咨询按 outcome 与 risk 分标签
- **AI 不可用不阻断业务**：超时（`AI_TIMEOUT_MS`，默认 20s）或连接失败 → 降级话术 + 转人工队列

### 7.4 迁移与回滚

Flyway 管 schema，**每条迁移都配一份 `db/undo/` 回滚脚本**；`server/scripts/undo-drill.sh`
是回滚演练（首次演练就抓出过一条在真实数据上跑不动的迁移）。

## 8. 配置的是三层（ADR-0010）

| 层 | 放哪 | 例子 | 谁改 |
| --- | --- | --- | --- |
| 技术参数 | 环境变量（`.env.example` 是模板） | 数据库地址、超时、限流阈值、密钥 | 部署的人 |
| **业务可调项** | **入库 + 运营后台** | 提示词、红线词、分级规则、护栏词、降级开关、积分规则、券模板 | 运营，**改完立即生效、不用发版** |
| 代码常量 | 代码里 | 枚举、状态码、结构性常量 | 开发 |

这条决定了「改一句话要不要发版」：**运营能改的一律不要写进代码**。

## 9. 定时任务

十个 `@Scheduled`（都不得与 `@Transactional` 同处一个类——`ScheduledTransactionBoundaryTest` 强制）：

| 类 | cron | 干什么 |
| --- | --- | --- |
| `ReminderScheduler#runDaily` | `0 0 8 * * *` | 生成当日提醒 |
| `AppointmentReminderScheduler#scheduled` | `0 0 * * * *` | 预约提醒 |
| `HealthReportScheduler#generateWeekly` | `0 10 8 * * MON` | 周报 |
| `HealthReportScheduler#generateMonthly` | `0 20 8 1 * *` | 月报 |
| `AssessmentMonthlyJob#scheduled` | `0 0 4 1 * *` | 月度考核批算 |
| `MonthlyLadderScheduler#scheduled` | `0 0 9 1 * *` | 月度阶梯奖励 |
| `RightsExpiryJob#scheduled` | `0 40 0 * * *` | 权益过期 |
| `CouponExpiryScheduler#scheduled` | `0 10 1 * * *` | 券过期与释放 |
| `QualificationExpiryScheduler#scheduled` | `0 30 3 * * *` | 资质过期 |
| `InviteSettlementJob#scheduled` | `0 5 * * * *` | 邀请结算 |
| `AiBudgetMonitor#checkDailyBudget` | `0 5 * * * *` | AI 日预算告警 |

## 10. 已知边界

写在这里，免得后来者以为「没写就是没有」。

**未接线 / 缺业务输入**：考核的拉新与券两项（`ProviderGrowthFactsApi` 无实现）、
区域保护的 `provider.region_code` 无人写入、提醒规则与照护阈值没有 admin 契约路径、
券的自动最优匹配。

**刻意的简化**（都有 ADR）：不引向量层（ADR-0022，理由与实测见 #63）、不做小程序与 APP（ADR-0001）、
不从平台经手资金（ADR-0002，到店付）、不引消息中间件与 ELK（ADR-0003）、
只有文字与图片两种输入（ADR-0004）。

**测试基建的现状**：`packages/shared` **有自己的 vitest**（`vitest.config.ts` + `test` 脚本），
http 行为测试已迁回本包，CI 的 `pnpm -r test` 覆盖得到；（这句话 2026-09-30 之前写的是「没有」，
那时确实没有，迁回后没同步——本次修掉。）
三端与 `packages/*` 仍然都没有 eslint/prettier（只有 `.editorconfig`）。

## 11. 关键文件索引

「想改 X，先看 Y」：

| 想改 | 先看 |
| --- | --- |
| 接口路径 / 字段 / 请求响应形状 | `contract/*.yaml`（改完必须重生成类型） |
| 统一响应信封、错误码 | `server/ph-common/.../` 的响应与 `ErrorCode`；`GlobalExceptionHandler` |
| 登录与令牌 | `ph-common/.../security/LoginDomain.java`、`CurrentUser.java` |
| 某个域的业务 | `server/ph-<域>/src/main/java/com/pethealth/<域>/service/` |
| 某个域的 HTTP 入口 | `server/ph-<域>/src/main/java/com/pethealth/<域>/web/` |
| 跨模块能拿到什么 | `server/ph-api/src/main/java/com/pethealth/api/` + 各域 `api/` 包 |
| 库表结构 / 加字段 | `ph-boot/src/main/resources/db/migration/` **和** `db/undo/`（两个都要写） |
| 分级规则 / 红线词 / 护栏词 | 库里的 `knowledge_*` 表（运营后台「AI 运营」页可改），代码基线的对应物在 `ai/app/` |
| 提示词 | `ai/app/prompts.py`（**库里那份优先**） |
| 模型调用 | `ai/app/model_client.py`（唯一出口） |
| 三条链路的测试怎么写 | `server/ph-boot/src/test/java/com/pethealth/boot/<域>/`（跑在 Testcontainers 的真库上） |
| 结构护栏（改架构时会拦你） | `server/ph-boot/src/test/java/com/pethealth/boot/architecture/` |
| 前端请求层与错误处理 | `packages/shared/src/http/` |
| 前端组件与四态 | `packages/ui/src/`（C 端自有一套，见 ADR-0015） |
| 设计 token（颜色/间距） | `packages/ui/src/tokens.css` 与 `packages/ui/src/console.css` |
| C 端的页面与路由 | `apps/c-web/src/router/index.ts`（24 条）、`src/views/` |

## 12. 文档地图

| 文件 | 性质 |
| --- | --- |
| `README.md` / `ARCHITECTURE.md`（本文） | **权威**：跑法 + 架构 |
| `CONTEXT.md` | **权威**：术语，命名唯一裁判 |
| `AGENTS.md` | **权威**：给 AI 的开工说明 |
| `docs/adr/0001–0052` | **权威**：决策唯一来源；0001–0010 是奠基性的，先读 |
| `docs/conventions.md` | **权威**：实现级约定 |
| `contract/README.md`、`server/README.md`、`ai/README.md` | **权威**：各模块自己的说明 |
| `docs/design/ai-service.md` | **权威**：AI 层完整方案 |
| `docs/reference/` | **输入**：甲方交付文档。是验收基准，但**不是最高权威**——偏离它的地方在 ADR-0001~0004 |
| `docs/research/` | 参考：模型选型与 RAG 调研（结论已进 ADR） |
| `docs/assets/mockups/` | 参考：视觉稿与取色依据（ADR-0008） |
| `docs/testing/` | **归档**：历次验收与深测报告。`round5` 是当前有效的功能清单（它自述取代第四轮），另有 2026-09-30 的变更评估与 `defect-remediation-plan-*.md`（缺陷与排期，对应 issue #127）；round4 及更早已是历史 |
| `docs/prior-rounds/` | **归档**：已作废轮次的地图/spec/实现票 |
