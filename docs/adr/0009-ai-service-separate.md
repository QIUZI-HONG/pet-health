# AI 能力独立部署为一个 Python 服务

模型的调用、知识检索与提示词编排放在一个独立的 Python（FastAPI）服务里；Java 后端不再直接调用大模型。

## Considered Options

- **Java 服务内直接调**：不新增进程，部署仍是一个单元；但多模态预处理（图片缩放、语音转写编排）、检索融合与提示词迭代都得在 Java 里写，迭代速度慢。
- **Java 内调 + 预留拆出接口**：折中，但接口设计与实现分离时容易变形。

## Consequences

**常驻服务从 3 个变成 4 个**（MySQL / Redis / 后端 / AI 服务），方向与 [ADR-0003](0003-lean-middleware.md)「精简中间件」相反。取舍的理由是：ADR-0003 精简的是**我们不写代码的中间件**（图数据库、消息队列、任务调度），而 AI 服务是**我们要写代码的业务核心**——它带来的迭代速度抵得上多一个进程的运维成本。

**Java 侧不再需要 Spring AI。** 模型调用与向量检索都挪到 Python，`ph-ai` 退化为「HTTP 客户端 + 配额计数 + 调用留痕」。这同时**推翻了 `docs/research/63-knowledge-base-rag.md` 里「用 Spring AI 1.1.x 的 RedisVectorStore」这一条**——向量客户端改为 Python 侧的 `redis-py` / `redisvl`，那条 POM 层面的版本锁定问题随之消失。

**知识域的表成为跨语言共享的读模型。** AI 服务直连 MySQL / Redis，但**只读** `knowledge_*` 系列表；其余一切数据的读写都走 Java 接口。这是本决策最大的架构代价：它给 [ADR-0006](0006-modular-monolith.md) 的「模块间不直连对方的表」开了一个明确的例外，所以这个例外必须写窄、写清楚——只限知识域、只读。

新增一条硬要求：**traceId 贯穿 Java → Python → 模型调用**，否则线上「AI 答错了」无法归因。
