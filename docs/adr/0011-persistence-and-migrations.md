# 持久层用 MyBatis-Plus，DDL 只由 Flyway 产生

数据访问统一用 **MyBatis-Plus**（`mybatis-plus-spring-boot3-starter`），数据库变更统一走 **Flyway**；软删除、审计字段、分页三件事由框架层统一处理，不允许各模块各写一套。这条对应地图上的 [#76](https://github.com/QIUZI-HONG/pet-health/issues/76)。

## Considered Options

- **Spring Data JPA / Hibernate**：CRUD 样板最少，但实体与表结构强绑定、复杂查询要退到 JPQL 或 native SQL；懒加载与 N+1 是长期隐性成本，运营后台的统计类查询几乎全都要绕开它。另外 `ddl-auto` 的存在本身就是风险——它诱惑人让框架改表结构，与「DDL 只由迁移脚本产生」直接冲突。
- **原生 MyBatis + XML**：SQL 最可控，但分页、软删除、审计字段填充、逻辑删除过滤都要每个模块自己实现一遍，12 个模块就是 12 套写法。
- **jOOQ**：类型安全的 SQL DSL 很好，但代码生成器要连库、上手门槛与本项目「让 AI 也能稳定写对」的目标不合。

## Consequences

**SQL 显式可读。** 复杂查询（看板统计、对账、考核计算）不需要「逃逸到 native SQL」这一步，模块边界也因此更容易检查——`grep` 一个 mapper 就知道它碰了哪些表，[ADR-0006](0006-modular-monolith.md) 的「禁止 join 其它模块的表」能落成人看得见的规矩，而不是靠自觉。

**代价：表结构改动要动两处。** 没有 `ddl-auto`，改字段必须同时改 Flyway 脚本与实体/映射。这是刻意的——DDL 的唯一来源是迁移脚本，框架不允许建表，也不允许改表。

**回滚方式（Flyway 社区版没有 `undo`）。** 每条 `V<n>__*.sql` 都配一条 `undo/U<n>__*.sql`，需要回滚时人工执行并同步 `flyway_schema_history`。规程写在 `server/README.md`。

**统一约定（12 个模块一律照此，不再单独拍）：**

| 项 | 约定 | 落地方式 |
| --- | --- | --- |
| 主键 | `BIGINT AUTO_INCREMENT`（交付文档 7.2 的写法） | JSON 里是数字。自增不会到 2^53，JS 精度安全；将来若改雪花号，契约里的 id 必须改成字符串——那是一次破坏性变更，届时单独立 ADR |
| 软删除 | `is_deleted TINYINT DEFAULT 0` | MyBatis-Plus `@TableLogic` 自动过滤；**手写 SQL 必须自己带 `is_deleted = 0`** |
| 审计字段 | `created_at` / `updated_at` / `created_by` / `updated_by` / `trace_id` | `MetaObjectHandler` 自动填充；`created_by=0` 表示系统写入（定时任务、AI 侧回流），非 0 一律是 `operator_id` |
| 分页 | `page` / `page_size`，默认 20、上限 100 | 拦截器保证**上限** 100（`setMaxLimit`）；**默认 20 由各接口显式传入**——契约里 `page_size` 的默认值就是 20，别指望框架兜底（MyBatis-Plus 的 `Page` 默认是 10，不传就会差一倍） |
| 时间 | `DATETIME`，库、应用、展示三处都是 `Asia/Shanghai` | 连接串 `serverTimezone`、Jackson `time-zone`、容器 `TZ` 一致 |
| 金额 / 小数 | `decimal`，**禁止浮点**；JSON 里序列化为字符串 | 交付文档 8.4 的订单示例就是 `"total_amount": "128.00"`——字符串才不会在 JS 里丢精度 |
| 命名 | 表 / 字段 snake_case，Java camelCase，JSON snake_case | Jackson 全局 `SNAKE_CASE`，与契约一致 |

**读写分离首期不做。** [#83](https://github.com/QIUZI-HONG/pet-health/issues/83) 按「500 并发」反推的日活 1–5 万，单库足够；主从会带来「下单后立刻查订单查不到」这类读一致性问题，而现在没有任何一条需求需要它。触发条件先写死：单表达千万级、或主库 QPS 持续打满，再谈读写分离。

**禁止字符串拼接 SQL**（交付文档 6.3）由这条自动满足：MyBatis 的 `${}` 是唯一的口子，代码评审与后续的静态检查盯这一条即可。
