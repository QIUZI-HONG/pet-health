# 宠物 AI 健康管理平台

以 AI 为基座、24 小时监护宠物健康的平台：先让用户低成本自助解决问题，解决不了再匹配服务者完成交易。

三个端**全部是 Web**——C 端（宠物主人）、服务者后台、运营后台。不做微信小程序，不做移动 APP（见 [ADR-0001](docs/adr/0001-all-web-clients.md)）。

## 现在处于什么阶段

**C 端、服务者后台、运营后台三端的功能面已经铺满**（找店 / 看店 / 按项目找服务 / AI 找服务 / 社区 / 交易与增长 / 后台 22 个模块），**两个后台的登录入口也已补上**（契约 auth 路径 + 两端登录页 + 按登录域签发令牌）；服务者侧的拍照报工与订单→档案回流、C 端知识库页也已落地。剩余缺口是**券与积分「有机制没种子」那一段**（配置入口已建，数值待运营填）与 **F022 的区域保护**（缺录入入口）。 路线图与逐项裁决见[地图 #52](https://github.com/QIUZI-HONG/pet-health/issues/52)；逐项完成状态见 [`docs/testing/acceptance-2026-09-30-round4.md`](docs/testing/acceptance-2026-09-30-round4.md)。

每一段都有跑在真实 MySQL/Redis 上的接口测试（共 437 个后端用例）：

- **账号与档案**：注册 / 登录 / 退出、多宠与回收站、档案分项与时间轴、健康评分与周报月报、专项照护（#94 #97 #102 #115 #116）
- **AI 咨询**：文字 + 图片、硬红线短路（不调模型）、输出护栏、三层知识检索与引用、四类降级、预算告警（#98 #100 #101 #103）
- **服务供给**：标准目录与区间价、服务者入驻与资质、选品定价上架、目录外提案（#104 #75 #78 #87）；C 端的找店 / 看店 / 按项目找服务 / AI 找服务（按症状推荐项目，规则版，不调模型）
- **交易**：号源、下单（锁券）、接单、到店核销、三道照片墙与报工、取消审批（#77 #107 #109）
- **增长**：券池（模板 / 贡献 / 发放 / 匹配 / 核销）、邀请与阶梯、积分与任务、权益引擎、考核与月度批算（#110 – #114）
- **社区与运营**：经验卡片与问答（机审 + 人工复审，C 端界面 + 运营审核页都齐）、服务者审核、目录维护、券池运营、考核规则、用户与数据看板（#117 #118）
- **两个后台的 22 个模块**：服务者侧 11 个（概览/订单/核销/券/考核/客户/看板/营销/结算/选品定价/门店设置）、运营侧 11 个（服务者审核/目录/券池/补贴/用户/内容审核/看板/考核规则/系统配置 + **AI 运营** + **积分与邀请配置**）

还没做的（2026-09-30 第四轮「实现轮」之后按当前代码复核）：

- **首批增长配置的数值要运营确认一次**（`V39__first_batch_growth_config.sql` 已入库）：券面额与邀请阶梯照交付文档取了（20 元洗护券、1/3/5/10/15 档），**没有文档依据的那几项是暂定值**（30 分兑 10 元券、60 分兑 20 元洗护券、月度 100/300 分两档、被邀请人 20 分）——它们直接决定补贴成本，请在运营后台「积分与邀请配置」核一遍（改完立即生效）
- **被邀请人拿的是积分而不是券**：交付文档 BPM-2 说「双方各得 20 元券」，而被邀请人侧只有积分路径（发券要新增代码，不是改配置）
- **考核的拉新与券两项未接线**（`ProviderGrowthFactsApi`）：口径未定（「服务者的拉新入口」这个业务物件还不存在），所以总分目前只由过程分与已接线的项构成，明细里注明「未接线」
- **区域保护**（F022 的另一半）：口径已定（审核期软约束 + 阈值可配），但 `provider.region_code` 目前**无人写入**——缺的是录入入口这条业务输入，不是代码
- **提醒规则与照护阈值的运营入口**：`reminder_rule` / `care_mode_rule` 两张表在库里、改数据即生效，但**契约里没有 admin 路径**，所以运营后台没有页面（要做得先定契约与后端接口）
- **评价晒单是「第一版」**：评分 + 一句话、一单一评、只有完成的订单能评，评分会回写门店（`provider.rating` 不再是恒 5.0）。**没有**图片评价、追评、服务者回复与审核；评价列表需要登录（契约写明了这一层，放开给游客只需在免登录路由表加一条）
- **打卡得券的规则只有种子**：连续 7 天 → 1 张洗护券（`check_in_reward_rule`），改它目前要动数据库，运营页面留给下一版；另有一处已知弱点：刻意 undo/redo 可以每轮各拿一张（根治要一张达成台账，见第四轮报告 ⑪-3）
- **知识库内容量**：类目与种子在库，靠运营边建边补（F025 的正文量）
- **支付链路**：按 ADR-0002 钱到店付，平台不经手资金，**待甲方澄清后单开一票**

开工前先读五样：

1. **[CONTEXT.md](CONTEXT.md)** —— 领域术语表。**命名以它为准**；「商家」「商户」「店铺」「merchant」是禁用词，统一说「服务者」。
2. **[docs/adr/](docs/adr/)** —— 已定的架构决策（当前 52 条）。优先看 ADR-0001 ~ 0004，那四条是对外部交付文档的刻意偏离。
3. **[ARCHITECTURE.md](ARCHITECTURE.md)** —— 东西是怎么连起来的：运行时拓扑、后端模块边界、**三条核心数据流**（AI 咨询 / 交易 / 增长）、跨切面机制，以及「想改某处该看哪个文件」的索引。
4. **[地图 #52](https://github.com/QIUZI-HONG/pet-health/issues/52)** —— 哪些决策已定、哪些还没定、下一步该做什么。
5. **[docs/conventions.md](docs/conventions.md)** —— 实现级约定（分页 / 脱敏 / 加密 / 迁移 / 越权口径 / 命名 / 删除幂等），以及每条约定落在哪个 ADR。

`docs/` 里其余东西哪些是规则、哪些是甲方输入、哪些只是归档，见 **[docs/README.md](docs/README.md)** 那张地图。

## 目录结构

```
pet-health/
├── CONTEXT.md              领域术语表（命名以它为准）
├── ARCHITECTURE.md         架构说明：拓扑 / 模块边界 / 三条数据流 / 关键文件索引
├── docs/
│   ├── README.md           文档地图：哪个是规则、哪个是甲方输入、哪个只是归档
│   ├── adr/                架构决策记录（当前 52 条）
│   ├── agents/             工程技能配置（issue tracker / 领域文档规则 / triage 标签）
│   ├── design/             AI 层的完整方案（ai-service.md）
│   ├── conventions.md      项目级约定：分页 / 脱敏 / 加密 / 命名 / 金额精度…
│   ├── research/           调研产物
│   ├── reference/          甲方交付的文档，原样归档
│   ├── prior-rounds/       已作废轮次的存档（那一轮的地图 / spec / 实现票）
│   ├── testing/            历次验收 / 深测报告（结论与缺陷清单都在这里）
│   │   └── screenshots-*/  GUI 实测截图归档（不入库，见 .gitignore）
│   └── assets/mockups/     视觉稿原始 PNG（原链接 2026-12-26 过期）
├── contract/               接口契约（OpenAPI），前后端类型的唯一源头
│                              app.yaml（C 端）/ provider.yaml / admin.yaml / open.yaml（文件直传）
│                              / common.yaml（信封与分页），四个域共 172 个路径（**全部有实现**）
├── server/                 后端：Java 17 + Spring Boot 3（跑法与迁移规程见 server/README.md）
│   ├── pom.xml             父 POM
│   ├── ph-common/          统一响应 / 异常 / 追踪 / 加密 / 持久层约定 / 登录域
│   ├── ph-api/             接口 DTO（手写，与契约对齐）
│   ├── ph-account/         账号 / 登录会话 / 合规文档 / 注销
│   ├── ph-record/          宠物 / 档案分项 / 打卡 / 评分 / 报告 / 专项照护
│   ├── ph-reminder/        提醒生成与批算 / 消息中心
│   ├── ph-ai/              AI 咨询留痕与配额 / 运营可调项（提示词·红线·分级·护栏·开关）
│   ├── ph-file/            文件与对象存储：上传凭证 / 直传 / 缩略图（ADR-0020）
│   ├── ph-catalog/         标准服务目录 / 区间价 / 目录项提案
│   ├── ph-provider/        服务者入驻与资质 / 选品定价上架 / 考核
│   ├── ph-order/           订单状态机 / 号源 / 核销 / 三道照片墙
│   ├── ph-privilege/       券池 / 邀请 / 积分 / 权益引擎
│   ├── ph-content/         社区（经验卡片·问答）/ 内容审核
│   └── ph-boot/            启动模块 + 迁移脚本（db/migration，回滚脚本在 db/undo）+ 接口测试
├── ai/                     AI 服务：Python + FastAPI（ADR-0009）
│   ├── app/config.py       全部可配项（配置分层见 ADR-0010）
│   ├── app/models.py       与 Java 的内部契约
│   ├── app/prompts.py      提示词与工具定义（代码基线，库里那份优先）
│   ├── app/red_flags.py    硬红线词表（命中即红，不调模型）
│   ├── app/knowledge.py    三层知识检索（事实 / 关系 / 检索层，ADR-0022/0033）
│   ├── app/guardrails.py   输出护栏（确诊 / 处方 / 剂量 / 药名）
│   ├── app/ttl_cache.py    三处读库配置的共用 TTL 缓存
│   ├── app/model_client.py 模型调用的唯一出口
│   └── app/main.py         入口（/internal/consult 是唯一业务路由）
├── apps/                   三个 Web 端（pnpm workspace）
│   ├── c-web/              C 端（24 条路由：找店 / 看店 / 按项目找服务 / AI 找服务 / 社区 / 知识库）
│   ├── provider-web/       服务者后台（11 个模块全部实现、全部可达；含登录页、选品定价与三道照片墙报工）
│   └── admin-web/          运营后台（11 个模块全部实现、全部可达；含登录页、AI 运营与积分/邀请配置）
├── packages/               三端共享
│   ├── shared/             请求层 / 字典 / 金额与日期格式化 / 契约生成的 TS 类型（自带 vitest）
│   ├── ui/                 设计 token + 后台共用外壳与四态 + 交互 composable
│   └── config/             tsconfig 基线 + **三端共用的构建检查脚本**（色值、首屏体积预算）
└── deploy/                 编排
    ├── docker-compose.dev.yml  本地中间件（MySQL 3307 / Redis）
    ├── prometheus/alerts.yml   告警规则
    └── data/                   容器数据卷（本地数据，不入库）
```

## 技术栈

| 层 | 选型 | 备注 |
| --- | --- | --- |
| 后端 | **Java 17 + Spring Boot 3.5.15**，模块化单体（13 个 Maven 模块） | 模块边界由测试强制，见 [ADR-0006](docs/adr/0006-modular-monolith.md) |
| 持久层 | **MyBatis-Plus 3.5.17** + **Flyway** | Mapper 无 XML（注解 SQL）；迁移每条都配 `db/undo/` 回滚脚本 |
| 数据库 / 缓存 | MySQL 8.4 + Redis 8 | 只这两样，不引消息队列与 ES（[ADR-0003](docs/adr/0003-lean-middleware.md)） |
| 前端 | **Vue 3.5 + TypeScript 5.9 + Vite 7 + Pinia + vue-router** | 三端全是 Web（[ADR-0001](docs/adr/0001-all-web-clients.md)） |
| 包管理 | **pnpm 12 workspace** | 注意 `allowBuilds` 写在 `pnpm-workspace.yaml`，不在 `package.json` |
| 契约 | **OpenAPI YAML + openapi-typescript** | 契约是唯一真源，前端类型由它生成 |
| 测试 | **JUnit 5 + Testcontainers**（后端真库）/ **Vitest 5 + jsdom**（前端）/**pytest + ruff**（AI） | 后端接口测试跑在真实 MySQL/Redis 上 |
| AI 服务 | **Python 3.14 + FastAPI + httpx** | 模型调用的唯一边界（[ADR-0009](docs/adr/0009-ai-service-separate.md)） |
| 部署 | Docker Compose + Nginx | `deploy/` 下有本地中间件与告警规则 |

## 本地怎么跑

中间件上 Docker，应用本地跑——数据库版本与正式环境一致，同时保留热重载。

```bash
# 1. 中间件（需要 Docker daemon 先启动）
docker compose -f deploy/docker-compose.dev.yml up -d     # MySQL 8 + Redis 8

# 2. AI 服务（需要 Python 3.11+）—— 详见 ai/README.md
cd ai && python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env        # 填入 AI_API_KEY；.env 不会进版本库
uvicorn app.main:app --reload --port 8000

# 3. 后端（需要 JDK 17；./mvnw 自带 Maven，不必本机安装）
cd server && cp .env.example .env    # 至少填 JWT_SECRET / FIELD_ENC_KEY / FIELD_HMAC_KEY，见文件内的生成命令
./mvnw -pl ph-boot -am spring-boot:run

# 4. 前端
pnpm install
pnpm --filter c-web dev
```

跑后端测试（**需要 Docker**：测试自己用 Testcontainers 起 MySQL/Redis，不依赖上面那两个容器）：

```bash
cd server && ./mvnw -B verify
```

**MySQL 端口是 3307，不是 3306。** 这台机器上装了原生的 Windows MySQL 服务，3306 被它占着。用 3307 也顺带把项目数据与本机其它库隔开，避免「拿生产库开发」这类事故。原因写在 `deploy/docker-compose.dev.yml` 的注释里。

### 环境变量

| 位置 | 模板 | 说明 |
| --- | --- | --- |
| AI 服务 | [`ai/.env.example`](ai/.env.example) | 复制成 `ai/.env` 再填真实值；`.env` 已被 gitignore |
| 后端 | [`server/.env.example`](server/.env.example) | 复制成 `server/.env` 再填；起后端时自动读（`spring.config.import`） |

后端的 `JWT_SECRET`、`FIELD_ENC_KEY`、`FIELD_HMAC_KEY` **没有默认值，缺了启动即失败**——仓库里不放任何密钥值，
免得「部署时忘了配」一直没人发现。生成方式在 `server/.env.example` 里（`openssl rand -base64 32`）。
测试不需要配：集成测试自带测试专用密钥。

后端会读这些（括号内是本地默认值）：

- `AI_SERVICE_BASE_URL`（`http://127.0.0.1:8000`）
- `AI_SERVICE_TOKEN`（`dev-internal-token`）——**必须与 `ai/.env` 的 `INTERNAL_TOKEN` 一致**
- `AI_TIMEOUT_MS`（`20000`；实测模型偶发 20 秒，配 8 秒等于常态化降级，见 ADR-0017）、`AI_FREE_QUOTA_PER_DAY`（`3`）、`AI_DAILY_BUDGET_CNY`（`50`）
- `APP_BASE_URL`（`http://127.0.0.1:8080`）——**后端对 AI 服务可达的地址**：档案照片的签名读地址是相对路径，
  AI 服务要把图取回来再内联给模型（模型供应商拉不到我们的内网），所以出站前会补成这个前缀。
  **部署时必须填成 AI 服务能访问到的地址**，否则带图咨询会走「读不到图」的降级。
- `RATE_LIMIT_AUTH`（`30`，认证接口每分钟次数）、`RATE_LIMIT_DEFAULT`（`600`，其余接口）、
  `RATE_LIMIT_ENABLED`（`true`）——接口限流的口径见 [ADR-0028](docs/adr/0028-security-baseline.md)
- `IDEMPOTENCY_ENABLED`（`true`）、`IDEMPOTENCY_TTL`（`10m`）——写接口幂等键；
  可选能力，不带 `Idempotency-Key` 头的请求行为完全不变
- `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD`、`REDIS_HOST` / `REDIS_PORT`（默认值与 `deploy/docker-compose.dev.yml` 一致）
- `ASSESSMENT_SUPER_ADMIN_USER_IDS`（考核规则与单项分覆盖的白名单）、
  `CONSOLE_ADMIN_USER_IDS`（**能进运营后台的账号 id 名单**）——两个都是 **fail-closed：不配 = 没人能做**。
  服务者后台不看名单：任何启用中的账号都能登（BPM-4 的第一步就是提交入驻申请）。

**怎么把自己加进运营后台**（容器在跑、库已迁移之后）：

```bash
# 1. 先在 C 端注册一个账号（运营后台与 C 端共用同一批账号，ADR-0035），然后查它的 id：
docker compose -f deploy/docker-compose.dev.yml exec mysql \
  mysql -uroot -pdevroot -e "SELECT id, nickname FROM pet_health.user ORDER BY id DESC LIMIT 5;"
# 2. 把上面那个 id 写进 server/.env（多个用逗号分隔），重启后端：
#    CONSOLE_ADMIN_USER_IDS=1
```

不配也能起后端，但**运营后台的 11 个模块一个都进不去**（登录返回 40300，文案会告诉你去配名单）；
启动日志里也会有一行 WARN 提醒这件事。

**密钥纪律**：`AI_API_KEY` 这类真实密钥只写在本地 `.env` 里（已 gitignore）——不提交、不贴进对话、不写进任何文档。

### 环境状态

工具链已装好，**构建已实测跑通**（日期见下面各条）：

| 工具 | 版本 |
| --- | --- |
| JDK | OpenJDK 17.0.20 |
| Maven | 3.9.12（仓库自带 `./mvnw`，会自动下载同版本，CI 也走它） |
| Node | 22.22.1 |
| pnpm | 12.6.0 |
| Python | 3.14.4（AI 服务用；CI 锁同版本，避免"本地能跑 CI 挂"） |

已验证（数字为 **2026-09-30** 重跑）：

- `cd server && ./mvnw -B verify` —— **13 个 Maven 模块**（另有 1 个聚合 POM）全部编译通过；
  **437 个测试 / 79 个测试类全绿**（ph-common 12 个纯单元测试 + ph-ai 4 个 + ph-boot 421 个跑在
  Testcontainers 起的真实 MySQL 8.4 / Redis 8 上的接口与契约测试，见 ADR-0014）。
  其中几组值得点名：**四道结构护栏**（模块边界「跨模块 import 只能落在对方的 `api` 或 `event` 包」、**端口接线**
  「声明了的 `*Api` 必须有 `src/main` 里的实现，测试桩不算」、**术语纪律**「禁用词零命中，引用交付文档原文
  须带『文档用词』标记」、**调度与事务**「`@Scheduled` 与 `@Transactional` 不得同处一个类，否则自调用绕过代理」
  ——四道都自带反空转前提与「规则真的会响」的用例）、**C 端浏览**
  （`ProviderBrowseTest`：匿名可浏览、可见性口径、脱敏、40400）、**目录浏览**
  （`CatalogBrowseTest`：只给启用项、按项目找店、等级优先排序）与 **AI 找服务**
  （`ServiceRecommendationTest`：规则版闭环、降级、不落 AI 留痕）。
  （统计口径：先清 `target/surefire-reports/` 再跑，否则历史报告会把数字抬高。）
- `pnpm -r test && pnpm -r build` —— **490 个前端测试**通过（54 个文件：`packages/shared` 16 +
  C 端 297 + 服务者后台 96 + 运营后台 81）；
  三个 Web 端构建通过，构建会先跑硬编码色值检查、`vue-tsc` 类型检查与首屏体积预算（ADR-0015）。
  **两个后台此前没有测试基建**，现在与 C 端同一套（jsdom + 真路由表 + 会话种法）；
  请求层的深测（令牌怎么带、40101 静默刷新、会话失效广播）**住在它被测的包 `packages/shared` 里**，
  不再寄居在 C 端。两道构建检查的实现在 `packages/config/scripts/`，三端共用一份、各端只声明自己的预算
- `cd ai && .venv/bin/ruff check . && .venv/bin/pytest` —— 静态检查通过；
  **201 通过 + 2 xfailed + 7 skipped**（xfail 是两个已标记的安全缺口，见测试报告 14.2；
  skipped 是需要真库的连库用例；另有 `live` / `eval` 各 1 条默认不跑）；
  健康检查、内部鉴权（无 token 返回 401）、契约校验（缺 `text` 返回 422）均已实测
- **2026-09-27 Docker 端到端实测**：
  - MySQL 8.4.11 容器 healthy（库 `pet_health` 已建，utf8mb4 / `+08:00`）
  - 后端 `spring-boot:run` 启动成功、`/actuator/health` 返回 `UP`；前端 dev server 起得来，且 `/api/v1/app/**` 的代理**确实打到后端**（两边返回同一个 Spring 404）；AI 服务 `/internal/health` 正常
  - **Redis 8 实测通过**：`redis:8-alpine` 里带 RediSearch（Query Engine）/ vectorset / bf / ReJSON，版本 8.10.2；用 Python 客户端建 `FLAT + COSINE` 索引并跑通 KNN 往返
  - **一条要记住的结论**：RediSearch 的中文分词**不支持子串检索**（查「犬瘟」命中不了「犬瘟热」），所以关键词检索走 MySQL ngram——两条路径都实测过，依据见 [#63](https://github.com/QIUZI-HONG/pet-health/issues/63)
  - **三条 CI 流水线全部实测通过**（首次推送时触发）：[Server](https://github.com/QIUZI-HONG/pet-health/actions) · [Web](https://github.com/QIUZI-HONG/pet-health/actions) · [AI Service](https://github.com/QIUZI-HONG/pet-health/actions)。其中 AI 那条确认了 Python 3.14 在 GitHub runner 上可用。

### 本机特有的两件事

**1. GitHub 的 `github.com:443` 在本机被阻断（SNI 层），`api.github.com` 与 `ssh.github.com:443` 可用。** 所以 `git push` 走 SSH over 443。本仓库的 `.git/config` 已配好（**只在本机生效，不进提交**）：

```
core.sshCommand = /mnt/c/Windows/System32/OpenSSH/ssh.exe -i C:/Users/22724/.ssh/id_ed25519_github -o IdentitiesOnly=yes -p 443
remote.origin.url = ssh://git@ssh.github.com:443/QIUZI-HONG/pet-health.git
```

用的是 Windows 侧那把 `id_ed25519_github`，**没有把私钥复制进 WSL**。换机器或被重置时，照上面两行重配即可。

**2. Docker 镜像源**已配在 Docker Desktop 的 `daemon.json`（`docker.1ms.run` → `hub.rat.dev` → `docker.m.daocloud.io`，按实测速度排序），直接 `docker pull` 即可。

### pnpm 12 的一个坑

pnpm 12 起默认**不执行依赖的构建脚本**，而且放行清单写在 **`pnpm-workspace.yaml` 的 `allowBuilds`** 里——放进 `package.json` 的 `pnpm` 字段（v10 的老写法）**不生效**，只会静默忽略。

esbuild 需要执行构建脚本才能装原生二进制，不放行则 `pnpm install` 报 `Ignored build scripts: esbuild`。已在 `pnpm-workspace.yaml` 里放行，不需要手动 `pnpm approve-builds`。

## 约定

- 领域命名遵循 `CONTEXT.md`；代码标识符用 `provider`，不用 `merchant`
- 接口先写 `contract/` 的 YAML，再生成两端代码；前端不手写接口类型
- 模块间只能走接口，不能 join 对方的表
- 金额用 `decimal(10,2)`（与交付文档的 DDL 示例一致），禁止浮点
- 所有写操作留 `operator_id` 与 `trace_id`
