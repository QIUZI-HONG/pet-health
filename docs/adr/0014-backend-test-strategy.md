# 后端测试打在真实 MySQL / Redis 上，用 Testcontainers 起容器

后端功能测试默认是**集成测试**：Testcontainers 起真实 MySQL 8.4 与 Redis 8，接口层测试走真实 HTTP（`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`）。内存库与 Mock 数据访问层都不用在这一层。这条覆盖地图 [#82](https://github.com/QIUZI-HONG/pet-health/issues/82) 里「测试怎么跑」的部分；兼容性矩阵、性能压测、通过标准的修正版仍留在 #82。

## Considered Options

- **H2 / HSQLDB 内存库**：起得快，但本项目明确要用 MySQL 的特性——`utf8mb4_0900_ai_ci` 的排序、`ngram` 中文检索（[#63](https://github.com/QIUZI-HONG/pet-health/issues/63)）、JSON 列类型、flyway 的 MySQL 方言。用 H2 跑通的 DDL 不代表能在 MySQL 上跑通，测试会变成「另一套环境的假绿灯」。
- **嵌入式 MariaDB（`ch.vorburger.mariaDB4j`）**：不需要 Docker，但版本与 MySQL 8.4 有偏差，且生产就是 Docker 部署，没必要为了省一层再做一套差异。
- **Mock 掉 Mapper / 只做单元测试**：最快，但验收标准要求的是「跑在真实数据库上的 HTTP 层测试」，且软删除、唯一索引冲突、时区这些恰恰是 Mock 测不出来的。
- **Testcontainers**（本决策）：与 `deploy/docker-compose.dev.yml` 用的是同一批镜像（`mysql:8.4` / `redis:8-alpine`），本地与 CI 行为一致。

## Consequences

**测试必须有 Docker。** 本机 Docker 已可用，GitHub runner 也自带，所以这不是新增门槛；但 `./mvnw verify` 从此不能在纯裸机环境跑。

**容器单例复用。** 一个 JVM 里 MySQL / Redis 容器各起一次、被所有测试类共享（静态初始化 + Ryuk 兜底回收），否则每个测试类起一遍容器会让整个测试套慢到没人愿意跑。**表数据按测试方法清理**，不靠回滚——真实提交路径要一起测（事务回滚会掩盖提交后的行为）。

**测试数据不共享、不复用固定 id。** 每个测试自己造自己用的数据，避免「测试顺序影响结果」。

**分层：**

| 层 | 工具 | 覆盖什么 |
| --- | --- | --- |
| 单元 | JUnit 5 + AssertJ，不起 Spring | 纯逻辑：加密 / 脱敏 / 状态机迁移 / 金额与日期换算 |
| 集成（默认） | Testcontainers + Spring 上下文 | 领域逻辑 + 真实 SQL + 迁移脚本 |
| 接口 | `@SpringBootTest(RANDOM_PORT)` + 真实 HTTP | 契约、鉴权、错误码、越权、软删除可见性 |

**覆盖率**沿用 `docs/conventions.md` 的 ≥ 60%，但**不把它当验收目标**——AC 里写明的场景必须有用例，比覆盖率数字重要。

**与 AI 服务的关系：** `ai/` 有自己的 pytest（不连这两个容器）。Java 侧的 AI 客户端测试用 mock server，不打真实模型——模型的评测归 [#62](https://github.com/QIUZI-HONG/pet-health/issues/62) 的评测集管。
