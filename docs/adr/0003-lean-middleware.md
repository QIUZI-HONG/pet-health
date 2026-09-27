# 精简中间件，但保留 RAG 所需的向量检索

交付文档第六章指定的中间件是 MySQL + Redis + Elasticsearch + Neo4j + RabbitMQ + XXL-JOB，全部 Docker Compose 拉起。现收敛为 Java 17 + Spring Boot 3 + MySQL 8 + Redis，加一个向量检索能力（ES kNN 或 pgvector）。

逐项替换：

| 文档指定 | 替代 |
| --- | --- |
| Neo4j（知识图谱） | 关系表建模边与节点，**能力保留**，只是不引入图数据库 |
| RabbitMQ | Redis 队列 / Spring 事件 |
| XXL-JOB | Spring Scheduler |
| Elasticsearch（全文搜索） | MySQL 全文索引 |

**向量检索不在此列**：RAG 的分层知识库检索依赖它，去掉等于砍掉 AI 分诊的准确性基础，所以保留。

## Considered Options

全按文档选型：与文档一致，但单机要常驻七个服务，内存占用与运维面都不适合单人开发。

## Consequences

单机常驻服务从七个降到四个左右。代价是与交付文档出现偏离，后续任何"按文档核对"的场合都需要先说明这条。
