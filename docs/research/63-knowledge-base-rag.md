# 分层知识库与 RAG 的工程落地（issue #63）

调研时点：2026-09-27。技术栈约束：Java 17 + Spring Boot 3 + MySQL 8 + Redis，遵循 [ADR-0003](../adr/0003-lean-middleware.md)（去 Neo4j，保留向量检索）与 [ADR-0004](../adr/0004-text-and-image-only.md)。

本文是决策文档：每个结论都给出依据；无法从一手来源核实的部分集中在最后一节「不确定与待验证」，不混进结论。

---

## 0. 结论摘要

| 问题 | 结论 |
| --- | --- |
| 三层架构是什么 | **L1 结构化事实层**（疫苗/驱虫周期、剂量区间、禁忌、红线规则，MySQL 表 + 代码规则）、**L2 关系层**（症状—疾病—药物的审核过的边表，MySQL 关系表）、**L3 文本检索层**（条目正文 + 分块 + 向量 + 全文索引）。层间边界判据见 §1.2 |
| 向量库选型 | **Redis 8（Query Engine 已内置）+ FLAT 索引 + FLOAT32 + COSINE**，走 Jedis 或 Spring AI 1.1.x 的 `RedisVectorStore`。**不新增常驻服务**（仍是 4 个）。MySQL 侧不承载向量（8.x 无向量类型；9.x 的 `DISTANCE()` 是 HeatWave 专属） |
| 关键词检索放哪 | MySQL 8.4 的 `FULLTEXT ... WITH PARSER ngram`（官方明确支持中日韩）。**融合在应用层做 RRF**，不依赖单引擎的混合查询 |
| 嵌入模型 | 首选 API：阿里云百炼 `qwen3.7-text-embedding`（默认 1024 维，兼容 OpenAI `/embeddings`，0.0005 元/千 token）；备选自托管 `BAAI/bge-m3`（1024 维，可平滑切换）或 `Qwen3-Embedding-0.6B`（MRL 可裁到 1024 维） |
| 知识图谱值不值得做 | **做，但只做「小图」**：3~4 类关系（症状→疾病 `may_indicate`、疾病→药物 `treated_by`、药物→物种/年龄 `contraindicated_for`/`approved_for`、症状→红线 `escalates_to`），全部人工审核。它的价值是**安全门 + 可校对的排序依据 + 一致性校验**，**不是召回**。GraphRAG 式的图索引/社区摘要在「数千条 + 局部事实型提问」下没有可证明的收益（论文的增益场景是百万 token 级语料的全局综述题） |
| 人工校对卡在哪 | 卡在**发布闸门**：`review_status = approved` 之前，条目不进向量索引也进不了检索（检索侧同时带 `review_status` 过滤做双保险）；红线规则要求双人复核 |

---

## 1. 三层知识库架构

### 1.1 三层各存什么

**L1 结构化事实层（Deterministic Facts & Rules）**

存"有唯一正确答案、可判定"的知识，形态是**表 + 代码规则**，不走检索：

- 疫苗周期：疫苗品种 × 物种 × 起始周龄 × 剂次间隔 × 加强周期
- 驱虫周期：体内/体外 × 物种 × 月龄 × 频次 × 季节修正
- 体重—剂量区间表（只做"是否在常见区间内"的提示与"遵医嘱"话术，**不给个体化剂量**，见 §6.4）
- 禁忌/慎用表：药物 × 物种/品种/年龄阶段/妊娠/基础病
- 红线规则表：症状词/行为模式 → 立即就医（绿/黄/红分级里的红）

**L2 关系层（Curated Relations）**

存"条目之间显式且可审核的关系"，形态是节点表 + 边表（§5）。它是 L1 与 L3 之间的桥：

- 用于生成侧「可能原因」的**排序依据**（`may_indicate.weight`，人工维护，可解释）
- 用于生成前的**硬过滤**：候选建议里的药物，必须过 `contraindicated_for` 检查
- 用于**一致性校验**：任何一条边都必须挂 `evidence_entry_id`，没来源的边不允许上线

**L3 文本检索层（Retrieval）**

存"需要解释、需要引用原文"的知识，形态是条目 + 分块 + 向量 + 全文索引：

- 疾病/症状/行为/营养/护理/急救的科普正文、分诊话术、注意事项
- 每段正文带 `entry_id`，回答时以 `[K-1042]` 形式引用，前端渲染成「来源：<原始资料名>（版本/日期）+ 链接 + 校对兽医」

### 1.2 层与层的边界（放哪一层的判据）

按顺序问三个问题，命中即停止：

1. **答案是不是唯一的、可判定的？**（"幼犬第三针间隔几周""猫能不能用某成分"）→ **L1**。L1 不参与向量检索，直接结构化查询。
2. **这个问题是不是要靠"两个知识块之间的关系"才能回答？**（"呕吐可能对应哪些疾病""这个病常用什么药""这个药什么情况禁用"）→ **L2**（关系）+ **L3**（解释文本）联合。
3. **其余**（"为什么""怎么办""这种情况正常吗""有什么要注意的"）→ **L3** 混合检索。

反过来的约束同样重要，且是本方案降低幻觉的主要手段：

- **L3 的文本只能解释，不能作为可判定结论的唯一依据**。凡是"能不能/多久/几岁/禁不禁"这类判定，必须由 L1 给出；L3 命中但没有 L1/L2 支撑时，回答要降级为"资料不足，建议咨询兽医"。
- **药物相关的"建议行动"必须走 L1 禁忌过滤**，不允许 LLM 直接根据 L3 文本推荐药名，更不允许给出剂量。

### 1.3 与知识类目的映射

交付文档写「八大类」但列举了十项（品种/疫苗/驱虫/症状分诊/疾病/行为/营养/护理/急救/药物）。**这是文档自身的不一致，需在核对交付文档时确认**；架构上不受影响——类目是一张字典表 `kb_category`，加类目不改结构。十项按层落位如下：

| 类目 | L1 结构化 | L2 关系 | L3 检索 | 权威来源（示例） |
| --- | --- | --- | --- | --- |
| 品种 | 体型/成年体重区间/易感病清单 | `breed predosposed_to disease` | 品种介绍、饲养要点 | 犬猫品种标准、兽医教科书 |
| 疫苗 | **核心表**：剂次与间隔 | `vaccine protects_against disease` | 疫苗原理、不良反应、接种禁忌说明 | WSAVA 2024 犬猫疫苗指南（有官方中文版） |
| 驱虫 | **核心表**：体内外频次与季节修正 | `antiparasitic targets parasite` | 寄生虫科普、感染途径、家庭防护 | CAPC / ESCCAP 指南 |
| 症状分诊 | **红线规则表** + 分级规则 | `symptom may_indicate disease`（带 weight 与 urgency） | 症状说明、家庭观察要点、就医时机 | 兽医急诊共识、合作兽医录入 |
| 疾病 | 传染性/人畜共患/病程表 | `disease differential_with disease` | 疾病全文 | 教科书、学会共识 |
| 行为 | — | `behavior related_to condition`（行为异常也可能指向疾病） | 行为学文章 | 兽医行为学会指南 |
| 营养 | 生命阶段能量/禁忌食材表（巧克力、木糖醇、百合等） | `food toxic_to species`（**安全门**） | 喂养建议 | 宠物毒物中心（如 ASPCA APCC）公开清单 |
| 护理 | 频次类表格（洗澡、驱虫后洗澡间隔等） | — | 护理流程 | 合作兽医录入 |
| 急救 | **急救步骤（顺序敏感，编号存表）** | `first_aid_for condition` | 急救说明与图示 | 兽医急救手册 |
| 药物 | **核心表**：禁忌/慎用/剂型/物种 | `drug contraindicated_for species/age_stage`、`disease treated_by drug` | 说明书原文摘录、不良反应 | 药品说明书、openFDA `animalandveterinary`（注意其免责声明） |

---

## 2. 知识条目的结构与必填标注字段

设计目标有两个，字段是围着它们定的：**(a) 检索可用**（能过滤、能排序、能引用）；**(b) 引用可展示、可追溯**（回答里的每条结论都能点回原始资料）。

### 2.1 主表 `kb_entry`

```sql
CREATE TABLE kb_entry (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对外展示为 K-<id>',
  category_code     VARCHAR(32)  NOT NULL COMMENT 'kb_category.code',
  title             VARCHAR(200) NOT NULL,
  body              MEDIUMTEXT   NOT NULL COMMENT 'L3 正文，可含 Markdown',
  summary           VARCHAR(500) NOT NULL COMMENT '一句话摘要，用于召回后压缩与检索排序',
  species_scope     VARCHAR(64)  NOT NULL COMMENT '枚举多选: dog,cat,all',
  age_stage_scope   VARCHAR(64)  NOT NULL COMMENT '枚举多选: puppy_kitten,adult,senior,all',
  structured_payload JSON        NULL     COMMENT 'L1 载荷：周期表/禁忌/步骤等；NULL 表示该条目不承担判定',
  risk_hint         VARCHAR(8)   NULL     COMMENT 'green|yellow|red，仅症状分诊/急救类必填',
  confidence        VARCHAR(8)   NOT NULL COMMENT 'high|medium|low，由来源等级映射决定',
  source_type       VARCHAR(16)  NOT NULL COMMENT 'guideline|textbook|label|paper|web|vet_input',
  source_title      VARCHAR(200) NOT NULL,
  source_url        VARCHAR(500) NULL,
  source_version    VARCHAR(64)  NULL     COMMENT '如 "2024 版"、"第 5 版"',
  source_published_at DATE       NULL,
  reviewed_by       VARCHAR(64)  NULL,
  reviewed_credential VARCHAR(128) NULL   COMMENT '如 "执业兽医师，证号 XXXX"',
  reviewed_at       DATETIME     NULL,
  review_status     VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft|pending_review|approved|rejected|deprecated',
  embedding_model   VARCHAR(64)  NULL     COMMENT '入库时使用的嵌入模型+维度，换模型时用于重建',
  effective_from    DATE         NULL,
  effective_to      DATE         NULL     COMMENT '过期即 deprecated，不物理删除',
  version           INT          NOT NULL DEFAULT 1,
  is_deleted        TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL,
  updated_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cat_status (category_code, review_status),
  KEY idx_scope (species_scope, age_stage_scope),
  FULLTEXT KEY ft_title_body (title, summary, body) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.2 分块表 `kb_chunk`

```sql
CREATE TABLE kb_chunk (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  entry_id     BIGINT NOT NULL,
  seq          INT    NOT NULL,
  text         TEXT   NOT NULL COMMENT '入库前注入条目上下文（见 §3 上下文分块）',
  token_count  INT    NOT NULL,
  embedding_model VARCHAR(64) NOT NULL,
  vector_ref   VARCHAR(64) NOT NULL COMMENT 'Redis 键名 kb:chunk:<id>',
  review_status VARCHAR(16) NOT NULL COMMENT '冗余自 kb_entry，供检索前置过滤',
  is_deleted   TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_entry_seq (entry_id, seq),
  KEY idx_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.3 必填标注字段与「为什么必须填」

| 字段 | 必填 | 它支撑什么 |
| --- | --- | --- |
| `source_title` + `source_url` + `source_version` + `source_published_at` | **是** | 引用展示与"按文档核对"时的可追溯性。没有来源的条目不允许 `approved` |
| `confidence` | **是** | 生成侧的措辞强度与降级策略。映射规则建议：指南/学会共识 = high；教科书/专家共识 = high；说明书 = high（限说明书覆盖的内容）；单篇文献 = medium；公开科普/网络资料 = low（**low 不进可检索集合**，只作线索） |
| `species_scope` / `age_stage_scope` | **是** | 检索过滤与安全兜底。犬猫混答是宠物健康类产品最常见的错误来源；`age_stage_scope` 同时是"幼年不能吃 X"这类禁忌的过滤维度 |
| `reviewed_by` + `reviewed_credential` + `reviewed_at` | `approved` 时必填 | 产品要对用户负责：谁来担这个责任必须落在数据里 |
| `risk_hint` | 症状分诊/急救类必填 | 与红线规则表（L1）交叉校验：标红的条目必须能被红线规则命中 |
| `structured_payload` | L1 类目必填 | 让"周期类问题"走结构化查询而不是检索（交付文档的明确要求） |
| `embedding_model` | 有向量时必填 | 换模型必须全量重建；不记录就会出现新旧向量混用 |
| `effective_from/to` | 建议填 | 指南换代（如疫苗指南更新）时旧条目 `deprecated` 而不是删除，历史回答仍可追溯 |

`age_stage` 的阈值（幼年/成年/老年的分界、犬猫差异）**需要兽医定稿**，不要交给工程拍。可以先按犬 <1 岁 / 1~7 岁 / >7 岁、猫 <1 岁 / 1~10 岁 / >10 岁占位，并在 `kb_category` 旁注明待确认。

---

## 3. 入库管线：从原始资料到可检索条目

```
① 采集/录入        ② 规范化         ③ 结构化抽取(L1+L2)     ④ 分块(含上下文注入)   ⑤ 嵌入
（指南 PDF、说明书、  （术语词典对齐、   （LLM 辅助，产出待审      （按语义单元切，         （API 或本地模型，
  合作兽医录入、        去重、类目归类）    payload 与边候选）        注入条目级上下文）        按 model+dim 记录）
  openFDA 导出）          │                  │                      │                    │
                          │                  ▼                      ▼                    ▼
                          │            ┌───────────────────────────────────────────────────────┐
                          └──────────► │  ⑦ 发布闸门（人工校对，唯一卡点）                       │
                                       │  draft → pending_review → approved / rejected          │
                                       │  红线规则与药物禁忌：双人复核；其余：一名执业兽医        │
                                       └───────────────────────────────────────────────────────┘
                                                          │ approved
                                                          ▼
                                       ⑥ 建索引（FTS + 向量，同时冗余 review_status）
                                       ⑧ 版本与废弃（新版本生效 → 旧版本 deprecated）
```

**步骤要点**

1. **采集**：优先一手权威来源。已核实可获得的有 WSAVA《2024 犬猫疫苗接种指南》（官网提供简体中文版）、CAPC 与 ESCCAP 的寄生虫控制指南、openFDA `animalandveterinary` 不良事件接口（1,357,337 条记录，接口自带免责声明"Do not rely on openFDA to make decisions regarding medical care"，因此只能作为**不良反应线索**，不能作为剂量/适应证依据）。
2. **规范化**：建两张受控词表——物种/品种词典、症状与体征词典（`vomiting` / `呕吐` / `吐` 归一）。这是后面 L2 边能挂得准、检索过滤不跑偏的前提，也是**唯一必须在写代码前先做的事**。
3. **结构化抽取**：用 LLM 从原文抽取 L1 载荷与 L2 边候选，但**产出物一律是 `draft`**。这一步是效率工具，不是知识来源；权威来源是原文 + 兽医签字。参考证据：LLM 自动构建的知识图谱存在"伪噪声"与"信息不完整"两类系统性问题，会直接导致检索漂移与幻觉（arXiv:2603.14828），所以自动构建的结果必须过人工，且不允许 LLM 直接改已有边。
4. **分块**：按语义单元（一个小节/一条建议）切，不要按固定字数硬切。入库前把条目级上下文拼进分块文本（标题 + 适用物种 + 适用年龄 + 类目），这类"上下文注入"在 Anthropic 的对照实验中把 top-20 检索失败率从 5.7% 降到 3.7%，是性价比最高的一步。分块要保留 `entry_id`，引用才能回到条目。
5. **嵌入**：批量调用，按 `embedding_model` 记录模型与维度；失败重试要幂等（`entry_id + seq + model` 唯一）。
6. **索引**：向量写 Redis，全文进 MySQL ngram FTS，两侧都冗余 `review_status`。
7. **发布闸门（人工卡点）**：**这是唯一的人工卡点，也是本方案的核心质量保证**。`approved` 之前不建索引（或建了也被过滤掉）；`rejected` 保留记录说明原因，避免同一个错误资料被重复采。
8. **版本与废弃**：指南更新时新增版本条目，旧条目置 `deprecated` + `effective_to`；引用历史回答时仍能落到当时生效的版本。

**索引参数（可照抄）**

```ini
# MySQL 8.4 my.cnf：ngram_token_size 是只读参数，只能启动时设定（官方可设置范围 1-10，默认 2）
[mysqld]
ngram_token_size=2
```

```sql
-- 关键词索引（已在 §2.1 的表定义里）
FULLTEXT KEY ft_title_body (title, summary, body) WITH PARSER ngram;
```

```sql
-- Redis：一次性建索引（Redis 8 Query Engine），向量字段 + 过滤用 TAG 字段
FT.CREATE kb_chunk_idx ON HASH PREFIX 1 kb:chunk:
  SCHEMA
    embedding VECTOR FLAT 6 TYPE FLOAT32 DIM 1024 DISTANCE_METRIC COSINE
    category TAG
    species  TAG
    age_stage TAG
    review_status TAG
    entry_id NUMERIC
```

选 `FLAT` 而不是 `HNSW` 是官方给的口径：Redis 文档建议**小数据集（< 1M 向量）用 FLAT**（精确、无近似损失），> 1M 才用 HNSW 换性能。本项目数千条目、万级分块，远在 FLAT 的适用区间内。

注意 `ngram_token_size` 是只读参数，改了只能重启；改动后需要重建 ngram 全文索引（否则新旧索引的 token 粒度不一致）。停用词也要处理：ngram 解析器排除的是"**包含**停用词的 token"，而默认停用词表是英文的，官方明确建议 CJK 场景自定义停用词表——实现时把停用词表置空。

---

## 4. 检索策略

### 4.1 三条路径与路由器

```
用户提问
   │
   ├─ ① 红线规则匹配（L1，字符串/词典/正则在生成前先跑）──► 命中：直接红灯，跳过检索与"原因推断"
   │
   ├─ ② 意图路由
   │     ├─ "多久/间隔/几岁/何时/能不能" + 命中疫苗/驱虫词典 ──► L1 结构化查询（唯一权威）
   │     ├─ "能不能吃/吃了/误食" ────────────────────────────► L1 毒物表 + L1 禁忌 + L3 解释
   │     ├─ 症状类（呕吐/腹泻/跛行/咳嗽…） ─────────────────► L2 关系 + L3 混合检索
   │     └─ 其他 ───────────────────────────────────────────► L3 混合检索
   │
   └─ ③ 组装：L1 判定 + L2 排序 + L3 引用文本 → 生成 → 引用校验
```

**交付文档要求的「疫苗/驱虫周期走结构化查询，常见症状走检索」就是 ② 这一步**，实现上是「词典 + 意图分类」的路由，不是靠检索碰运气。结构化的那一支不进向量库、不受 LLM 影响，返回的是 L1 表里的确定性答案。

### 4.2 L3 混合检索：关键词 + 向量 + 应用层融合

| 环节 | 实现 | 依据 |
| --- | --- | --- |
| 关键词 | MySQL 8.4 `MATCH(title, summary, body) AGAINST(? IN BOOLEAN MODE)`，ngram 解析器 | 官方明确 ngram 解析器支持中日韩；布尔模式下检索词会转成 ngram **短语**检索（更严格，适合"犬瘟热"这类专名） |
| 语义 | Redis `FT.SEARCH` KNN（FLAT + COSINE），带 `species`/`age_stage`/`category`/`review_status` 过滤 | Redis 8 内置 Query Engine；Spring AI 1.1 的 `RedisVectorStore` 支持 KNN 与元数据过滤 |
| 融合 | **应用层 RRF**（Reciprocal Rank Fusion），`score = Σ 1/(60 + rank)` | RRF 用**排名**融合，天然避免"MySQL 相关性分数"与"余弦相似度"量纲不可比的问题；Redis 侧 `FT.HYBRID` 的默认参数也是 `CONSTANT=60`、`WINDOW=20` |
| 重排（可选） | 对 top-150 取 top-20 做重排（可用 API reranker 或 LLM 打分），**仅在评测显示有收益时开启** | Anthropic 对照实验：向量 35% → 向量+关键词 49% → 再加重排 67%（top-20 失败率 5.7%→3.7%→2.9%→1.9%）。关键词那一档收益明确且便宜，重排那一档要付延迟与成本 |

**为什么不用 Redis 的 `FT.HYBRID` 一把梭？** 一是本项目的中文关键词检索用 MySQL ngram 更稳（Redis 侧中文分词方案本次未能从一手文档核实，见 §8）；二是 ADR-0003 已经把全文检索映射到了 MySQL，保持一处即可。应用层 RRF 只有约 20 行代码，换来的是两个引擎各司其职。若后续确认 RediSearch 的中文分词可用，再考虑收敛（届时 Jedis 8 已原生支持 `FT.HYBRID`）。

**检索参数建议**：向量 topK=20，关键词 topK=20，融合后取 8~12 条进 prompt；`similarityThreshold` 先设 0.35 左右占位，用 issue #62 的评测集调；**召回为空或全部低于阈值时不允许编答案**，走降级模板。

### 4.3 向量库选型对比

| 候选 | 版本/形态 | 与 Java+Boot3 集成成本 | 与 ADR-0003 的一致性 | 结论 |
| --- | --- | --- | --- | --- |
| **Redis 8 Query Engine** | 8.10.2（2026-09-17）；查询引擎自 8.0 GA 起内置，"included in all binary distributions" | 低：Spring AI 1.1.8 `spring-ai-starter-vector-store-redis`（依赖 `spring-boot-starter` 3.5.15），或 Jedis 8（原生 `FT.HYBRID`） | 一致（Redis 本就在栈内，不新增服务） | **选定** |
| MySQL 9.x `VECTOR` | 9.x 创新版 | — | — | **不可用**：官方手册明确 "DISTANCE() is available only for users of MySQL HeatWave on OCI; it is not included in MySQL Commercial or Community distributions"；且 8.4 源码中根本没有 `MYSQL_TYPE_VECTOR` |
| pgvector | Postgres 扩展 | 中（引入第二种关系库） | 冲突（栈里没有 PG） | 排除 |
| Qdrant / Milvus / Weaviate | 独立服务 | 中高 | 冲突（ADR 就是要减服务） | 排除 |
| Elasticsearch kNN | 独立服务 | 中 | 冲突（ADR-0003 已删 ES） | 排除 |
| Spring AI `SimpleVectorStore` | 内存 + 文件 | 低 | 不一致：官方文档写明 "not designed for production use … only … for testing or demonstration purposes" | 仅测试用 |
| 自研内存暴力扫描 | 自己写 | 低但**要自己维护** | 一致 | 作为兜底基线：几千条目 × 1024 维 FLOAT32 的矩阵约 8~40 MB（按 2k~10k 分块换算），一次全量余弦扫描的成本远低于一次 LLM 调用。但既然 Redis 已在栈内，用它换掉这段自研代码更划算 |

**规模核算（算术推算，非实测）**：假设 3,000 条目、每条 1~3 个分块 → 3k~10k 向量；1024 维 FLOAT32 = 4 KB/向量 → 约 **12~40 MB**，Redis 内存毫无压力；FLAT 是暴力扫描，官方把它定位在 < 1M 向量的区间，本项目用量级低于该阈值两个数量级。

### 4.4 嵌入模型选型

| 候选 | 维度 | 上下文 | 中文能力（公开评测） | 获取方式 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **`qwen3.7-text-embedding`（阿里云百炼）** | 2560/2048/1536/1024（默认）/768/512/256 | 批 20 条、单批最大 128,000 token | Qwen 系，官方称 201 语种 | **OpenAI 兼容 `/embeddings`**，0.0005 元/千 token，有免费额度 | **首选** |
| `text-embedding-v4`（同一入口，Qwen3-Embedding 系） | 2048/1536/1024（默认）/768/512/256/128/64 | 批 10 条，单条 8192 token | 同上 | 0.0005 元/千 token | 备选（更便宜稳定，维度更省） |
| `Qwen3-Embedding-0.6B`（开源自托管） | ≤1024（MRL 可自定义） | 32k | MTEB 多语 64.33；C-MTEB 66.33（检索 71.03） | 需自建推理（0.6B） | 离线/合规场景备选，**1024 维与首选一致，可平滑切换** |
| `Qwen3-Embedding-8B` | 4096 | 32k | MTEB 多语 **70.58（2025-06-05 榜第一）**；C-MTEB 73.84（检索 78.21） | 需自建推理（8B，成本高） | 不推荐：本语料规模下收益不值这个算力 |
| `BAAI/bge-m3` | 1024 | 8192 | 100+ 语言，多语检索强；CE 场景常用 | 自托管（ONNX/CPU 可跑） | 备选：想完全离线时选它，维度仍为 1024 |
| OpenAI `text-embedding-3-*` | 1536/3072 | — | — | — | 本次**未能从一手来源核实当前价格**，且国内访问/合规成本更高，不作为候选 |

**选型理由**：与 ADR-0003 的"精简中间件"同向——不自建 GPU/模型服务，用 API；对 Java 栈的集成成本最低（OpenAI 兼容接口可直接套 Spring AI 的 OpenAI embedding client，只改 `base-url`）；中文语料、国内部署、开票与合规都顺。

**入库成本（算术推算）**：3,000 条目 × 平均 500 token ≈ 1.5M token ≈ **0.75 元**（按 0.0005 元/千 token）。查询侧每次只有 1 个短 query 的向量化成本，可忽略。所以嵌入成本不构成选型约束，**要把钱花在检索质量与校对人力上**。

**必须记住的纪律**：入库与查询必须同模型同维度；`embedding_model` 落库，换模型 = 全量重建（含 Redis 索引 drop/rebuild）。

### 4.5 生成侧：把"有来源"变成硬约束

幻觉抑制不能只靠 prompt 礼貌请求，要在链路上加校验：

1. **检索为空 → 不生成**，直接返回降级话术（"我目前的知识库没有足够依据，建议就医/咨询兽医"）。
2. **红线优先**：红线规则命中即输出红灯模板，**不给"可能原因"**（避免用户被推测性原因安抚而延误就医）。
3. **强制引用**：prompt 要求每个"可能原因/建议行动"后跟 `[K-xxxx]`；生成后做**引用校验**——剔除引用 ID 不在本次召回集合中的句子；若关键结论全部被剔除，返回降级话术。这是一道纯代码关卡，成本极低，收益直接。
4. **药物零剂量**：药物相关内容只允许来自 L1 的结构化字段，措辞固定为"遵医嘱，按体重由兽医核定剂量"。
5. **展示来源**：回答附带 `citations` 数组，前端渲染原始资料名 + 版本 + 链接 + 校对兽医。用户看到的"有来源"必须真能点开。
6. **审计落库**：每次问答记录 query / 召回 ID 列表 / 最终回答 / 引用 ID / 模型与耗时。这份流水就是 issue #62「评测集」的原料。

---

## 5. 知识图谱：关系表建模与收益评估

### 5.1 表结构

```sql
-- 节点：只做"薄节点"，实体详情仍在 kb_entry / L1 表里
CREATE TABLE kg_node (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  node_type   VARCHAR(24) NOT NULL COMMENT 'symptom|disease|drug|food|parasite|breed|condition',
  name        VARCHAR(120) NOT NULL COMMENT '规范化名称（走受控词典）',
  alias       VARCHAR(500) NULL     COMMENT '别名/同义词，逗号分隔，用于查询归一',
  entry_id    BIGINT      NULL     COMMENT '对应的 L3 条目（有解释文本时）',
  review_status VARCHAR(16) NOT NULL DEFAULT 'draft',
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_name (node_type, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 边：所有关系一张表，靠 relation 区分；每条边必须能追到证据条目
CREATE TABLE kg_edge (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  src_node_id   BIGINT       NOT NULL,
  dst_node_id   BIGINT       NOT NULL,
  relation      VARCHAR(32)  NOT NULL
      COMMENT 'may_indicate|treated_by|contraindicated_for|approved_for|escalates_to|differential_with|predisposed_to|toxic_to|targets|protects_against',
  species_scope VARCHAR(64)  NOT NULL DEFAULT 'all',
  age_stage_scope VARCHAR(64) NOT NULL DEFAULT 'all',
  weight        DECIMAL(4,3) NULL     COMMENT '仅 may_indicate：0~1，人工维护的原因排序权重',
  urgency       VARCHAR(8)   NULL     COMMENT '仅 may_indicate：green|yellow|red',
  note          VARCHAR(255) NULL     COMMENT '展示用的一句话解释，如"大型犬腹胀+干呕需警惕胃扭转"',
  evidence_entry_id BIGINT   NOT NULL COMMENT '来源条目，强制可追溯',
  review_status VARCHAR(16)  NOT NULL DEFAULT 'draft',
  is_deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_edge (src_node_id, dst_node_id, relation, species_scope, age_stage_scope),
  KEY idx_src_rel (src_node_id, relation, review_status),
  KEY idx_dst_rel (dst_node_id, relation, review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

建模约定：

- **一张边表 + `relation` 枚举**，不为每种关系建表。数千条规模下，一张表的行数在 1~3 万级别，任何 JOIN 都是毫秒级。
- **`evidence_entry_id` 强制 NOT NULL**：没来源的边不允许存在，这条约束比任何检索算法都更能压住幻觉。
- 边的 `species_scope` / `age_stage_scope` 与条目同构，过滤逻辑一致。
- 节点用 `alias` 吃同义词，查询前先过归一（"吐"/"呕吐"/"vomiting" → 同一节点）。
- `weight` 只给 `may_indicate`；它是**人工维护的可解释排序**，也是换掉"LLM 黑箱猜原因"的关键。

### 5.2 典型查询（不用递归 CTE）

```sql
-- 1) 症状 → 候选原因（按物种/年龄过滤 + 权重排序）
SELECT d.name, e.weight, e.urgency, e.note, kb.id AS entry_id, kb.title, kb.source_title
FROM kg_edge e
JOIN kg_node s ON s.id = e.src_node_id AND s.node_type='symptom'
JOIN kg_node d ON d.id = e.dst_node_id AND d.node_type='disease'
LEFT JOIN kb_entry kb ON kb.id = e.evidence_entry_id
WHERE s.name = ? AND e.relation='may_indicate' AND e.review_status='approved'
  AND (e.species_scope IN ('all', ?)) AND (e.age_stage_scope IN ('all', ?))
ORDER BY e.weight DESC
LIMIT 5;

-- 2) 两跳：症状 → 疾病 → 药物，并在同一查询里做禁忌硬过滤
SELECT d.name AS disease, dr.name AS drug, c.note AS contraindication
FROM kg_edge s2d
JOIN kg_node d  ON d.id = s2d.dst_node_id AND d.node_type='disease'
JOIN kg_edge d2r ON d2r.src_node_id = d.id AND d2r.relation='treated_by' AND d2r.review_status='approved'
JOIN kg_node dr ON dr.id = d2r.dst_node_id AND dr.node_type='drug'
LEFT JOIN kg_edge c ON c.src_node_id = dr.id AND c.relation='contraindicated_for'
                    AND c.dst_node_id = (SELECT id FROM kg_node WHERE node_type='species' AND name = ?)
WHERE s2d.src_node_id = ? AND s2d.relation='may_indicate' AND s2d.review_status='approved';
```

MySQL 8.4 支持递归 CTE（`WITH RECURSIVE`），但**本项目不需要**：固定两跳用两次 JOIN 更简单、更可预测，也更容易写测试。只有将来需要"任意深度"时才引入递归，并且必须加深度上限。

### 5.3 它相对纯检索带来什么实际收益（坦白版）

**收益不明显的部分（不要吹）**

- **召回率提升有限**。语料只有数千条时，混合检索本身已经能把相关条目捞回来；图的"多跳扩展召回"在百万级语料上才有意义。
- **GraphRAG 式的图索引/社区摘要不适用**。Microsoft 的 GraphRAG 论文报告的增益场景是"**百万 token 级语料上的全局综述型问题**"（"What are the main themes in the dataset?"），靠的是 LLM 建图 + 社区摘要。我们的问题是**局部事实型**（"我家狗呕吐可能是什么原因"），语料规模小两个数量级，且 §5.3 引用的后续研究指出 LLM 自动构图会引入伪噪声与信息缺口，反而造成检索漂移与幻觉。**照搬 GraphRAG 是典型的过度设计。**
- 因此：**如果工期紧，L2 可以推迟**——L1 的禁忌/红线表 + L3 混合检索能覆盖大头的用户价值。这是本方案允许的唯一"可延后项"。

**收益明确的部分（值得做的理由）**

1. **安全门**：`contraindicated_for` / `approved_for` / `toxic_to` 是**生成前的硬过滤**。猎犬用伊维菌素、猫用对乙酰氨基酚、犬巧克力/木糖醇这类"推荐一次就是事故"的场景，靠向量相似度拦不住，靠一张审核过的边表可以。这是本方案里 KG 的第一价值。
2. **可校对的排序**：`may_indicate.weight` 把"可能原因"的排序从 LLM 的即兴发挥变成**可被兽医审阅、可被评测集回归**的数据。出问题时改的是数据，不是 prompt。
3. **一致性校验的抓手**：边表让"知识库自洽"变成可以写 SQL 的检查——例如"每条 `may_indicate` 边必须有 `evidence_entry_id` 且该条目 `approved`"、"`treated_by` 指向的药物必须在 L1 有禁忌记录"、"标 `escalates_to` 的症状必须能被红线规则命中"。这些检查在纯文本语料上根本无法做。
4. **解释链**：给用户的一句话理由（"大型犬腹胀伴干呕需警惕胃扭转，属急症"）可以来自边上的 `note`，来源与措辞都是审核过的。

**规模上限提醒**：这套设计在**数千节点、数万边**内完全够用；如果知识量涨到十万级以上，或出现"需要任意跳数推理"的需求，再评估是否引入专用图数据库——那时 ADR-0003 已经保证我们有一张结构良好的边表，迁移成本可控。

---

## 6. 端到端示例：用户问「我家狗呕吐」

**Step 0 · 档案注入**：当前选中宠物 = 犬 / 3 岁 / 12 kg / 已接种（WSAVA 核心疫苗，最近一次 11 个月前）/ 上次体内驱虫 2 个月前 / 近 24h 呕吐 3 次（用户在提问里补充）。档案是本平台的差异化资产（记录 + AI 绑在一起），一定进 prompt。

**Step 1 · 红线规则（L1，最先跑，跳过检索）**
命中任一条 → 直接红灯模板，不给"可能原因"：

- 呕吐物带血/咖啡渣样、频繁干呕且腹部膨隆（GDV 风险，大型/深胸犬）
- 误食已知毒物（巧克力、木糖醇、百合、鼠药、人用药）
- 抽搐、意识异常、呼吸困难、大量出血、持续呕吐 >24h 或无法进水

本例未命中，继续。

**Step 2 · 意图路由**：命中症状词典（呕吐）→ L2 关系查询 + L3 混合检索；同时发现档案里"2 个月前驱虫"，触发一条 L1 结构化比对（驱虫药相关呕吐的常见时间窗），结果作为一条候选原因线索（带来源）。

**Step 3 · 并行取证（三路，一次请求内并行）**

- **L3 关键词**：`MATCH(title,summary,body) AGAINST('+呕吐' IN BOOLEAN MODE)` + 过滤 `category IN ('症状分诊','疾病','急救') AND review_status='approved' AND species_scope IN ('dog','all')` → 20 条
- **L3 向量**：`FT.SEARCH kb_chunk_idx "(@species:{dog|all} @age_stage:{adult|all} @review_status:{approved})=>[KNN 20 @embedding $q AS score]"` → 20 条
- **L2 关系**：`symptom=呕吐 → may_indicate → disease`（过滤 dog/adult，按 weight 排序）→ top 5，附 `note` 与 `urgency`
- **L1 结构化**：疫苗/驱虫时间表比对 + 毒物表反查（用户若补充"可能吃了什么"）

**Step 4 · 融合**：两路检索结果做 RRF（k=60），按 `entry_id` 去重取 top 10；与 L2 的候选原因做**合并而非替代**——候选原因排序以 L2 的 `weight` 为准，解释文本与引用来自 L3。

**Step 5 · 生成（受约束）**

- 输入：L1 判定（本例为空）+ L2 候选原因（带权重与 urgency）+ L3 top 10 带 `entry_id` 与来源
- 要求：每个原因后跟 `[K-xxxx]`；建议行动只能来自召回内容；药物只写"遵医嘱"；无法确定时明说
- 校验：剔除引用不在召回集合内的句子

**Step 6 · 输出（示意）**

```json
{
  "risk_level": "yellow",
  "possible_causes": [
    { "cause": "饮食不当 / 突然换粮", "basis": ["K-1042"], "confidence": "medium" },
    { "cause": "急性胃肠炎", "basis": ["K-1187", "K-0903"], "confidence": "medium" },
    { "cause": "体内驱虫药不良反应（2 个月前用药，时间关联弱）", "basis": ["K-0771"], "confidence": "low" }
  ],
  "action_suggestion": "暂停喂食 6~8 小时并保证饮水；恢复进食时少量多餐、喂易消化食物；观察呕吐频次与精神状态。",
  "need_hospital": false,
  "care_tips": ["记录呕吐次数与性状", "不要自行喂人用药"],
  "citations": [
    { "entry_id": "K-1042", "title": "犬呕吐的常见原因与家庭观察", "source_type": "vet_input",
      "source_title": "合作兽医审核稿", "source_version": "v2", "reviewed_by": "…", "url": null },
    { "entry_id": "K-1187", "title": "急性胃肠炎", "source_type": "textbook",
      "source_title": "…兽医内科学（第 X 版）", "source_version": "第 X 版", "url": null }
  ],
  "fallback_used": false
}
```

**Step 7 · 审计**：写入 `ai_qa_log`（query / 召回 entry_id 列表 / 两路排名 / 最终回答 / 引用集合 / 模型 / 各段耗时），供评测集与线上问题复盘。

**这条链路对幻觉的作用**：L1 挡住"该不该马上就医"的判定；L2 决定"可能是什么"的排序且每条有来源；L3 只提供解释文本；引用校验拦掉编造的来源；无证据时降级。**每一层都在特定位置把编造空间关掉**，而不是靠一句"请不要幻觉"。

---

## 7. 与既有 ADR 的一致性

| ADR | 本文的关系 |
| --- | --- |
| ADR-0003 精简中间件 | **一致**。向量检索能力用 Redis 8 内置的 Query Engine 实现，不新增常驻服务（仍是 MySQL + Redis + 应用 + 反向代理量级的 4 个），也不是 ES 或 pgvector；全文检索沿用 ADR 的"ES → MySQL 全文索引"映射，并落到可用的 `ngram` 解析器上 |
| ADR-0004 只做文字 + 图片 | **一致**。语音转写后进文本链路，检索侧无需改动；图片不进 L3 文本索引，图片判断走多模态链路（属 issue #61），但**图片结论若要给"可能原因"，同样走本文的 L2/L3 取证与引用** |
| ADR-0001 / ADR-0002 | 无关 |

**对交付文档的偏离（需在核对时说明）**：文档要求 Elasticsearch + Neo4j 图数据库；本方案按 ADR-0003 用 MySQL 全文索引 + 关系表替代。**能力保留、形态改变**：向量检索在 Redis，全文检索在 MySQL，图能力是一张边表。

---

## 8. 不确定与待验证（务必与结论区分开）

1. **RediSearch 的中文分词能力未从一手文档核实**。本次未能取到 `FT.CREATE` 的 `LANGUAGE`/分词说明（redis.io 抓取多次超时）。因此关键词检索**推荐放在 MySQL ngram**（该能力有官方明确文档）。若后续确认 RediSearch 可直接处理中文，可简化成单引擎 `FT.HYBRID`。
2. **MySQL ngram 在本项目查询模式下的召回与延迟未实测**。文档层面已核实行为（默认 bigram、布尔模式转短语、停用词"包含即排除"），但"宠物症状口语 query"的实际召回需要跑评测集。
3. **Redis 官方 Docker 镜像的模块**：已核实镜像构建脚本在 amd64/arm64 上传 `BUILD_WITH_MODULES=yes`（因此查询引擎应在镜像内），但**部署时仍需实测 `FT.CREATE` 是否可用**（一次 `redis-cli` 即可），不要只信推断。
4. **Spring AI 1.1.8 + Spring Boot 3.5.15 的实际组合未编译验证**。POM 层面显示 `spring-ai-starter-vector-store-redis:1.1.8` 依赖 `spring-boot-starter:3.5.15`，与"Boot 3"约束相符；Spring AI 2.0.x 已转向 Boot 4（升级说明中出现 Boot 4.1.x），**所以本项目应锁 1.1.x 线**。这条要在第一个 Sprint 里验证。
5. **性能数字均为推算而非实测**：向量内存占用、全量扫描耗时、端到端 P95。本机没有 JDK/Node 环境，无法做基准；文档里给出的都是可复算的算术，不代表实测值。
6. **OpenAI 嵌入模型的当前价格与维度未能从一手来源核实**（platform.openai.com 与 openai.com 均拒绝抓取），故未列入候选对比表。
7. **MySQL 8.4 的生命周期**：已核实 8.4.x 补丁线仍在发（最新 tag `mysql-cluster-8.4.11`），但 EOL 日期未能从 Oracle 一手页面核实（dev.mysql.com 拒绝抓取），不做断言。
8. **兽医校对人力是最大的流程风险，不是技术风险**。`reviewed_by` 一旦成为必填字段，就必须有人签字；本方案对"谁签字、多久签一次、红线规则谁复核"没有答案，需要产品侧确认。**这也是本方案唯一的进度瓶颈**。
9. **类目数量**：交付文档写"八大类"但列举十项，需与文档核对（本方案按十项建 `kb_category` 字典，不影响结构）。
10. **本文未覆盖**：AI 分诊绿/黄/红的具体判定阈值与提示词工程（属 issue #62）、多模态模型选型（issue #61）、数据模型全貌中的表清单归并（issue #64）。

---

## 9. 参考来源（均在 2026-09-27 访问）

**Redis**

1. Redis 8.0 GA 发布说明（v8.0.0，2025-05-02；Query Engine / JSON / TimeSeries / Bloom 内置，"included in all binary distributions"；vector set beta；Redis Stack 弃用）— https://raw.githubusercontent.com/redis/redis/8.0/00-RELEASENOTES
2. Redis 8.2 发布说明（新增 SVS-VAMANA 向量索引类型，支持向量压缩）— https://raw.githubusercontent.com/redis/redis/8.2/00-RELEASENOTES
3. Redis 8.4 发布说明（8.4 GA v8.4.0，2025-11-18；`FT.HYBRID` 混合检索与融合打分；已知限制）— https://raw.githubusercontent.com/redis/redis/8.4/00-RELEASENOTES
4. Redis 向量检索文档（索引类型 FLAT/HNSW/SVS-VAMANA；`TYPE`/`DIM`/`DISTANCE_METRIC`；**FLAT 适用 < 1M 向量，HNSW 适用 > 1M**）— https://redis.io/docs/latest/develop/ai/search-and-query/vectors/
5. `FT.HYBRID` 命令文档（`since: 8.4.0`；RRF 默认 `WINDOW=20`、`CONSTANT=60`；LINEAR 带 `ALPHA`/`BETA`；`FILTER`/`POLICY` 语义）— https://redis.io/docs/latest/commands/ft.hybrid/
6. Redis 官方发布列表（最新 8.10.2，2026-09-17；维护线 8.10/8.8/8.6/8.4/8.2）— https://api.github.com/repos/redis/redis/releases
7. 官方 Docker 镜像构建（版本分支 8.2.10 的 Dockerfile：amd64/arm64 上 `BUILD_WITH_MODULES=yes`）— https://github.com/docker-library/redis/blob/8.2.10/debian/Dockerfile
8. Redis 源码顶层 Makefile（`BUILD_WITH_MODULES=yes` 才构建 `modules/`）— https://github.com/redis/redis/blob/8.4/Makefile
9. Jedis 原生 `FT.HYBRID` 支持（`redis.clients.jedis.search.hybrid.*`；Jedis 8.0.1 / 8.0.0 发布于 2026-08）— https://github.com/redis/jedis/tree/master/src/main/java/redis/clients/jedis/search/hybrid 、 https://api.github.com/repos/redis/jedis/releases

**MySQL**

10. MySQL 8.4 手册 14.9.8 ngram 全文解析器（支持中日韩；默认 token size 2、范围 1–10、只读参数；布尔模式转 ngram 短语检索；停用词"包含即排除"；min/max word length 选项不适用）— https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/fulltext-search-ngram.html
11. MySQL 8.4 手册 14.9.1 自然语言全文检索（relevance 的定义方式；未使用 BM25 表述）— https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/fulltext-natural-language.html
12. MySQL 8.4 手册 递归 CTE（`WITH RECURSIVE` 可用）— https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/with.html
13. MySQL 9.4 Reference Manual（PDF）向量函数章节原文："Note `DISTANCE()` is available only for users of MySQL HeatWave on OCI; it is not included in MySQL Commercial or Community distributions."；另含 `STRING_TO_VECTOR`/`VECTOR_TO_STRING`/`VECTOR_DIM` 与 `option_tracker_usage:Vector … (MySQL HeatWave only)` — https://downloads.mysql.com/docs/refman-9.4-en.pdf
14. MySQL 8.4 源码 `include/field_types.h` 无 `VECTOR` 类型；`trunk` 分支含 `MYSQL_TYPE_VECTOR = 242` — https://github.com/mysql/mysql-server/blob/8.4/include/field_types.h 、 https://github.com/mysql/mysql-server/blob/trunk/include/field_types.h
15. MySQL 8.4/9.x 版本标签（`mysql-cluster-8.4.11`、`mysql-cluster-9.7.2`、`mysql-cluster-26.7.0`）— https://api.github.com/repos/mysql/mysql-server/tags

**Spring AI / Jedis**

16. Spring AI 1.1.8 `RedisVectorStore` 文档（Redis Stack 前置、Jedis `JedisPooled`、KNN/范围检索、COSINE/L2/IP、HNSW/FLAT、元数据过滤、`initialize-schema` 变更）— https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/redis.html
17. `spring-ai-starter-vector-store-redis:1.1.8` POM（依赖 `spring-boot-starter:3.5.15`）— https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-vector-store-redis/1.1.8/spring-ai-starter-vector-store-redis-1.1.8.pom
18. Spring AI 向量数据库页（`SimpleVectorStore`："not designed for production use and should only be used for testing or demonstration purposes"；支持列表含 Redis）— https://docs.spring.io/spring-ai/reference/api/vectordbs.html
19. Spring AI 2.0 升级说明（提及与 Spring Boot 4.1.x 的兼容性）— https://docs.spring.io/spring-ai/reference/upgrade-notes.html
20. Spring AI 发布列表（2.0.1 于 2026-08-21；1.1.8 / 1.0.9 于 2026-06-12）— https://api.github.com/repos/spring-projects/spring-ai/releases

**嵌入模型**

21. Qwen3-Embedding 模型卡（0.6B/4B/8B：维度 ≤1024 / 2560 / 4096，32k 上下文，MRL 支持自定义维度，instruction-aware；MTEB 多语 64.33 / 69.45 / **70.58（2025-06-05 榜首）**；C-MTEB 66.33 / 72.27 / 73.84，检索分 71.03 / 77.03 / 78.21；论文 arXiv:2506.05176）— https://huggingface.co/Qwen/Qwen3-Embedding-0.6B
22. BGE-M3 模型卡（1024 维、8192 token、100+ 语言）— https://huggingface.co/BAAI/bge-m3
23. 阿里云百炼「向量化（Embedding）」文档（`qwen3.7-text-embedding`：维度 2560/2048/1536/1024 默认/768/512/256，批 20 条、单批 128,000 token，0.0005 元/千 token，100 万 token 免费额度，201 语种；`text-embedding-v4`：维度含 1024 默认，批 10 条、单条 8192 token，0.0005 元/千 token；**OpenAI 兼容 `/compatible-mode/v1/embeddings`**）— https://help.aliyun.com/zh/model-studio/embedding

**检索与图谱的方法证据**

24. Anthropic《Contextual Retrieval》（top-20 检索失败率：上下文嵌入 5.7%→3.7%（-35%）；+上下文 BM25 →2.9%（-49%）；+重排 →1.9%（-67%）；重排用 top-150 取 top-20）— https://www.anthropic.com/news/contextual-retrieval
25. Microsoft GraphRAG 论文（增益场景为**百万 token 级语料的全局综述型问题**）— https://arxiv.org/abs/2404.16130
26. 《Toward Robust GraphRAG》（LLM 构建的知识图谱存在伪噪声与信息缺口，导致检索漂移与幻觉；主张在检索侧约束而非依赖图谱修复）— https://arxiv.org/abs/2603.14828

**宠物领域一手资料（入库素材来源）**

27. WSAVA《2024 犬猫疫苗接种指南》（官网提供官方简体中文版与犬猫接种表）— https://wsava.org/global-guidelines/vaccination-guidelines/
28. CAPC 寄生虫控制指南 — https://www.capcvet.org/guidelines/
29. ESCCAP 指南（GL1 犬猫蠕虫控制等）— https://www.esccap.org/guidelines/
30. openFDA 兽药不良事件接口（1,357,337 条记录，VEDDRA 术语；接口自带免责声明 "Do not rely on openFDA to make decisions regarding medical care"）— https://api.fda.gov/animalandveterinary/event.json
