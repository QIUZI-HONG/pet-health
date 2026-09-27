# 后端（Java 17 + Spring Boot 3）

按领域分 Maven 模块、单进程部署（[ADR-0006](../docs/adr/0006-modular-monolith.md)）。
模块清单与职责在根 [README](../README.md) 的目录结构一节。

## 怎么跑

```bash
# 1. 中间件（需要 Docker daemon）
docker compose -f ../deploy/docker-compose.dev.yml up -d    # MySQL 8（3307）+ Redis 8

# 2. 起后端（./mvnw 自带 Maven，不必本机装）
./mvnw -pl ph-boot -am spring-boot:run

# 3. 跑测试（会自己用 Testcontainers 起 MySQL/Redis，不需要先起中间件）
./mvnw -B verify
```

数据库结构由 **Flyway 在启动时自动迁移**，不需要手工执行 SQL。

## 数据库变更怎么写

DDL 的唯一来源是迁移脚本（[ADR-0011](../docs/adr/0011-persistence-and-migrations.md)），框架不允许建表或改表
（`ddl-auto` 这类开关一概不配）。

1. 在 `ph-boot/src/main/resources/db/migration/` 新增 `V<n>__<描述>.sql`，版本号顺延，**不要改已提交的脚本**
   （Flyway 会校验校验和，改了会让所有环境启动失败）；
2. 同时在 `ph-boot/src/main/resources/db/undo/` 加一条 `U<n>__<描述>.sql`；
3. 起一次应用，确认迁移成功。

### 回滚怎么做

Flyway 社区版没有 `undo` 命令，所以回滚是**人工三步**：

```sql
-- 1. 执行对应的 undo 脚本（server/ph-boot/src/main/resources/db/undo/U<n>__*.sql）
-- 2. 删掉历史记录
DELETE FROM flyway_schema_history WHERE version = '<n>';
-- 3. 重启应用，确认迁移状态
```

生产上执行前必须先备份：这些脚本按设计会丢数据。

## 几个约定（细节见 ADR-0011 / 0012 / 0013）

- **表**：`BIGINT AUTO_INCREMENT` 主键、`is_deleted` 逻辑删除、`created_at` / `updated_at` / `created_by` /
  `updated_by` / `trace_id` 五列齐全；不建物理外键。
- **写操作**：审计字段由 MyBatis-Plus 的 `MetaObjectHandler` 自动填，业务代码不要自己设；
  **手写 SQL 要自己带 `is_deleted = 0`**（逻辑删除插件不管手写 SQL）。
- **JSON**：字段名 snake_case、时间 `yyyy-MM-dd HH:mm:ss`、`BigDecimal` 序列化成字符串（配置在 `JacksonConfig`）。
- **时区**：代码里取当前时间一律用 `AppTime.now()`，不要用 `LocalDateTime.now()`——后者取 JVM 默认时区。
- **鉴权**：`JwtAuthenticationFilter` 判「谁在访问」，业务层判「这条数据是不是他的」（越权返回 40400）。
- **测试**：真实 MySQL/Redis + 真实 HTTP（[ADR-0014](../docs/adr/0014-backend-test-strategy.md)），
  测试基类 `IntegrationTestBase` 负责起容器与清数据。
