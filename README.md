# 宠物 AI 健康管理平台

以 AI 为基座、24 小时监护宠物健康的平台：先让用户低成本自助解决问题，解决不了再匹配服务者完成交易。

三个端**全部是 Web**——C 端（宠物主人）、服务者后台、运营后台。不做微信小程序，不做移动 APP（见 [ADR-0001](docs/adr/0001-all-web-clients.md)）。

## 现在处于什么阶段

**规划阶段收尾，第一个垂直切片已经跑通。** 交付路线在[地图 #52](https://github.com/QIUZI-HONG/pet-health/issues/52) 上逐张裁决；切片 [#94 账号与宠物档案](https://github.com/QIUZI-HONG/pet-health/issues/94) 已实现并有跑在真实 MySQL/Redis 上的接口测试——它是第一块打通「HTTP → 领域 → 数据库」的完整切片，顺带把持久层、鉴权、加密、测试脚手架立住了。

切片落地时定的四条决策（[#76](https://github.com/QIUZI-HONG/pet-health/issues/76) 持久层、[#81](https://github.com/QIUZI-HONG/pet-health/issues/81) 会话与加密、[#82](https://github.com/QIUZI-HONG/pet-health/issues/82) 测试方式）记在 ADR-0011 ~ 0014。

开工前先读四样：

1. **[CONTEXT.md](CONTEXT.md)** —— 领域术语表。**命名以它为准**；「商家」「商户」「店铺」「merchant」是禁用词，统一说「服务者」。
2. **[docs/adr/](docs/adr/)** —— 已定的架构决策（当前 24 条）。优先看 ADR-0001 ~ 0004，那四条是对外部交付文档的刻意偏离。
3. **[地图 #52](https://github.com/QIUZI-HONG/pet-health/issues/52)** —— 哪些决策已定、哪些还没定、下一步该做什么。
4. **[docs/conventions.md](docs/conventions.md)** —— 实现级约定（分页 / 脱敏 / 加密 / 迁移 / 越权口径），以及每条约定落在哪个 ADR。

## 目录结构

```
pet-health/
├── CONTEXT.md              领域术语表（命名以它为准）
├── docs/
│   ├── adr/                架构决策记录（当前 24 条）
│   ├── agents/             工程技能配置（issue tracker / 领域文档规则 / triage 标签）
│   ├── design/             AI 层的完整方案（ai-service.md）
│   ├── conventions.md      项目级约定：分页 / 脱敏 / 缓存 / 重试 / 金额精度…
│   ├── research/           调研产物
│   ├── reference/          甲方交付的文档，原样归档
│   ├── prior-rounds/       已作废轮次的存档（那一轮的地图 / spec / 实现票）
│   └── assets/mockups/     视觉稿原始 PNG（原链接 2026-12-26 过期）
├── contract/               接口契约（OpenAPI），前后端类型的唯一源头
│                              app.yaml 已定义账号与宠物档案（切片 #94）
├── server/                 后端：Java 17 + Spring Boot 3（跑法与迁移规程见 server/README.md）
│   ├── pom.xml             父 POM
│   ├── ph-common/          统一响应 / 异常 / 追踪 / 加密 / 持久层约定
│   ├── ph-api/             接口 DTO（手写，与契约对齐）
│   ├── ph-account/         账号 / 登录会话 / RBAC / 数据域
│   ├── ph-provider/        服务者 / 资质 / 门店
│   ├── ph-catalog/         服务项 / 号源 / 档期
│   ├── ph-order/           订单 / 状态机 / 核销 / 对账
│   ├── ph-record/          宠物 / 档案 / 评分 / 打卡（宠物已实现）
│   ├── ph-file/            文件与对象存储：上传凭证 / 直传 / 缩略图（切片 #95，ADR-0020）
│   ├── ph-ai/              AI 服务的客户端 / 配额 / 熔断 / 留痕
│   ├── ph-reminder/        提醒 / 消息中心
│   ├── ph-privilege/       券池 / 邀请 / 积分
│   ├── ph-content/         社区 / 审核
│   └── ph-boot/            启动模块 + 迁移脚本（db/migration，回滚脚本在 db/undo）+ 接口测试
├── ai/                     AI 服务：Python + FastAPI（ADR-0009）
│   ├── app/config.py       全部可配项（配置分层见 ADR-0010）
│   ├── app/models.py       与 Java 的内部契约
│   ├── app/prompts.py      提示词与工具定义（待 #103 入库）
│   ├── app/model_client.py 模型调用的唯一出口
│   └── app/main.py         入口（文本 + 图片分级已接真模型）
├── apps/                   三个 Web 端（pnpm workspace）
│   ├── c-web/              C 端
│   ├── provider-web/       服务者后台
│   └── admin-web/          运营后台
├── packages/               三端共享
│   ├── shared/             请求封装 / 鉴权 / 字典 / 契约生成的 TS 类型
│   ├── ui/                 设计 token + 共享组件
│   └── config/             tsconfig 基线（被三个应用 extends）
└── deploy/                 编排
    └── docker-compose.dev.yml
```

## 本地怎么跑

中间件上 Docker，应用本地跑——数据库版本与正式环境一致，同时保留热重载。

```bash
# 1. 中间件（需要 Docker daemon 先启动）
docker compose -f deploy/docker-compose.dev.yml up -d     # MySQL 8 + Redis 8

# 2. AI 服务（需要 Python 3.11+）—— 详见 ai/README.md
cd ai && python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env        # 填入 DASHSCOPE_API_KEY；.env 不会进版本库
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
- `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD`、`REDIS_HOST` / `REDIS_PORT`（默认值与 `deploy/docker-compose.dev.yml` 一致）

**密钥纪律**：`AI_API_KEY` 这类真实密钥只写在本地 `.env` 里（已 gitignore）——不提交、不贴进对话、不写进任何文档。

### 环境状态

工具链已装好，**构建已实测跑通**（2026-09-27）：

| 工具 | 版本 |
| --- | --- |
| JDK | OpenJDK 17.0.20 |
| Maven | 3.9.12（仓库自带 `./mvnw`，会自动下载同版本，CI 也走它） |
| Node | 22.22.1 |
| pnpm | 12.6.0 |
| Python | 3.14.4（AI 服务用；CI 锁同版本，避免"本地能跑 CI 挂"） |

已验证：

- `cd server && ./mvnw -B verify` —— **13 个 Maven 模块**（另有 1 个聚合 POM）全部编译通过；**93 个测试全绿**
  （12 个纯单元测试 + 39 个跑在 Testcontainers 起的真实 MySQL 8.4 / Redis 8 上的接口测试，见 ADR-0014）
- `pnpm -r test && pnpm -r build` —— C 端 14 个组件测试（导航与四态组件）通过；三个 Web 端构建通过，
  其中 C 端的构建会先跑硬编码色值检查（ADR-0015）
- `pnpm install && pnpm -r build` —— 三个 Web 端全部构建通过（vite 7.3.6）
- `cd ai && ruff check . && uvicorn app.main:app` —— 静态检查通过；健康检查、内部鉴权（无 token 返回 401）、契约校验（缺 `text` 返回 422）均已实测
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
