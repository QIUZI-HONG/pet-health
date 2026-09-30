# 知识库建设方案（三层架构 · 知识清单 · RAG 与图谱 · 分诊分级）

> 一期验收标准第三项「知识库建设方案（可落地设计 + 基础实现）」的交付物。
>
> **本文是方案与现状的合并说明**：设计部分给甲方评审，实现部分逐条对应到本仓库的库表、代码与
> 迁移版本，便于核对「设计里写了、代码里有没有」。
>
> 前置阅读：[`docs/research/63-knowledge-base-rag.md`](../research/63-knowledge-base-rag.md)
> （分层与 RAG 的调研与取舍）、[`docs/design/ai-service.md`](ai-service.md)（AI 服务的调用链路与护栏）、
> ADR-0022（不做向量）、ADR-0033（知识库结构与检索）、ADR-0009（AI 对库只读 `knowledge_*`）、
> ADR-0010（配置三层：环境变量 / 入库可调 / 代码常量）。

---

## 一、结论摘要

| 项 | 状态 | 落点 |
| --- | --- | --- |
| 三层架构（L1 事实 / L2 关系 / L3 文本） | **已实现** | `ai/app/knowledge.py`；`knowledge_entry` / `knowledge_node` / `knowledge_edge` |
| 八大类知识清单 | **已实现为十类**（文档自身不一致，见 §2.1） | `knowledge_category` 十行种子（V18） |
| 检索：结构化 + 全文 + 关系，不引入向量库 | **已实现** | ADR-0022；MySQL `FULLTEXT ... ngram`（V18:85） |
| 知识图谱：节点表 + 边表，不引入图数据库 | **已实现** | `knowledge_node` / `knowledge_edge`（V19）；ADR-0003 已删 Neo4j |
| 分诊风险分级（红/黄/绿） | **已实现**（硬红线短路 + 分级规则抬档 + 护栏） | `ai/app/red_flags.py`、`ai/app/ops.py`、`ai/app/guardrails.py` |
| 知识条目**内容** | **只有种子、尚未复核**（40 条全 `pending_review`） | V18 种子；这是当前最大的进度瓶颈（ADR-0033 §六） |

一句话：**骨架、检索、分级、护栏都在跑，缺的是「有人把条目复核一遍」这件事本身**。
在没有 `vetted` 条目的情况下，对外文案不得声称「基于知识库」——口径见 ADR-0028。

---

## 二、三层知识库架构

### 2.1 分层的判据（放哪一层不是偏好，是能不能被机器使用）

| 层 | 存什么 | 判据 | 表 |
| --- | --- | --- | --- |
| **L1 结构化事实** | 疫苗/驱虫周期、毒物清单、急救步骤、硬红线词 | 能写成**字段或键值**、答案**唯一且可校验** | `knowledge_entry.structured_payload` + `knowledge_red_flag` |
| **L2 关系层** | 症状→疾病、药→禁忌、食物→毒性 | 价值在**两个东西之间的关系**上（含权重与紧急度） | `knowledge_node` + `knowledge_edge` |
| **L3 文本层** | 疾病/行为/营养/护理等叙述性知识 | 需要**读一段话**才能回答，答案允许有上下文 | `knowledge_entry`（正文 + ngram 全文索引） |

判据一句话：**能回答「是/否/多少」进 L1；回答「A 与 B 什么关系」进 L2；回答「为什么、怎么办」进 L3**。

### 2.2 八大类 → 十类的处理（交付文档自身不一致）

交付文档标题写「八大类」，正文列了**十项**：品种、疫苗、驱虫、症状分诊、疾病、行为、营养、
护理、急救、药物。**我们按十项建**（ADR-0033 §一），因为按八项删掉两项等于替甲方做减法，
而多两个类目不影响任何结构。

十类与三层的映射（`knowledge_category.layer_hint` 字段）：

| 类目 | code | 主要落层 | 种子条数 | 说明 |
| --- | --- | --- | --- | --- |
| 品种 | `breed` | L3 | 3 | 品种易感与照护差异 |
| 疫苗 | `vaccine` | **L1** | 4 | 周期与到期判据（结构化） |
| 驱虫 | `antiparasitic` | **L1** | 4 | 同理 |
| 症状分诊 | `triage` | L3 + L2 | 8 | 分诊正文；症状本体在 L2 |
| 疾病 | `disease` | L3 | 6 | |
| 行为 | `behavior` | L3 | 3 | |
| 营养 | `nutrition` | L3 | 4 | |
| 护理 | `care` | L3 | 3 | |
| 急救 | `first_aid` | **L1** | 3 | 步骤型，答案唯一 |
| 药物 | `drug` | L2 为主 | 2 | 主要价值在「禁忌」这类边上；正文只做背景 |

> 种子共 **40 条**（V18:151-327），**全部 `pending_review`**。
> 「有内容」与「可对外引用」是两件事：检索侧只把 `vetted` 的条目当引用来源。

### 2.3 条目的必填标注字段（为什么必须填）

| 字段 | 含义 | 缺了会怎样 |
| --- | --- | --- |
| `source_title`（必填）/ `source_url` / `source_version` | 出处 | 引用了说不出来源的知识，等于没引用（ADR-0033 §四） |
| `species_scope` | 犬 / 猫 / 通用 | 把猫的剂量类建议给到狗 |
| `age_stage_scope` | 幼 / 成 / 老 | 幼宠与老年宠的阈值不同（分级规则就靠它） |
| `risk_hint` | 该条最轻的风险档 | 回答比知识本身更轻，误导用户拖延就医 |
| `review_status` / `reviewed_by` / `reviewed_credential` | 两级复核 | 无法审计「谁批准了这条知识」 |
| `effective_from` / `effective_to` | 有效窗口 | 过期的知识仍被引用 |

---

## 三、检索设计（RAG）

### 3.1 三条路径与路由器

```
用户输入
  ├─ 硬红线预检（knowledge_red_flag，纯字符串 + 变体）  → 命中即短路：risk=3，不调模型
  └─ 未命中 → 三层检索
       L1  意图词命中 → 结构化事实查询（structured_payload）
       L3  全文布尔查询（MATCH ... AGAINST，ngram 解析器）
       L2  由 L1/L3 的结果反查关系边（召回补齐 + 安全门）
       合并（层优先 L1 > L2 > L3，覆盖度与相关度各 0.5 权重）→ 过滤（禁忌、时效、物种）
```

实现：`ai/app/knowledge.py` 的 `search()`（调度）、`_retrieve_l1/l2/l3()`、`_merge()`、`_filter()`；
硬红线在 `ai/app/main.py` 的请求入口处**先于**检索与模型执行。

### 3.2 为什么不做向量（ADR-0022）

- 中文医学短文本上，**ngram 全文 + 结构化事实**在种子体量（数十~数千条）下的召回已够用，
  而向量要额外引入嵌入模型、向量存储与一套同步机制；
- 向量检索**不可解释**：我们要求每条引用可回溯到条目与出处，布尔检索的命中词本身就是解释；
- `knowledge_chunk`（分块表 + `embedding_model` / `vector_ref` 列）**建了但检索侧不读**：
  留出接口，等条目体量与评测集都上来再评估（ADR-0022 的复议条件写在那里）。

### 3.3 把「有来源」变成硬约束

- 模型被要求用 `[K-xxxx]` 形式引用条目编号（工具 schema 里约束，`ai/app/prompts.py`）；
- 生成后做**引用校验**：`strip_invalid_refs()` 只保留真实存在的编号，句子级复核把
  「引用不成立」的整句剔除（`main.py` 的 `_review_citations()`）；
- 护栏层（`ai/app/guardrails.py`）拦药名与越界表述（确诊 / 处方 / 剂量），词表入库可运营维护。

---

## 四、知识图谱的存储与用法

**不引入图数据库**（ADR-0003 已删 Neo4j）：两张表 + 三种查询就够，理由与收益评估见
`docs/research/63-knowledge-base-rag.md` §5。

```sql
knowledge_node(code, node_type, name, alias JSON, entry_code, review_status, enabled)
knowledge_edge(src_code, dst_code, relation, species_scope, age_stage_scope,
               weight, urgency, note, evidence_entry_code NOT NULL)
```

- `relation` 共十种：`may_indicate` / `treated_by` / `contraindicated_for` / `approved_for` /
  `escalates_to` / `differential_with` / `predisposed_to` / `toxic_to` / `targets` / `protects_against`；
- **每条边必须有 `evidence_entry_code`**（NOT NULL）：图谱里的关系不是「大家这么觉得」，
  而是某条知识条目里写的——这是可追溯性的底线；
- 三种用法（按收益排序）：
  1. **安全门**：`contraindicated_for` / `toxic_to` 命中即**整条剔除**（宁可少答，不可错答）；
  2. **召回补齐**：症状节点经 `may_indicate` 边补出疾病条目；
  3. **可校对排序**：`weight` 由人工维护，参与排序，运营改它就能调结果。
- **不做多跳遍历**（没有递归 CTE）：典型查询是「一跳 + 按权重排序」，多跳的收益在当前体量下
  还没有证据。

种子：30 个节点（species 2 / symptom 10 / disease 7 / drug 3 / food 5 / condition 1 / breed 2）、27 条边（V19）。

---

## 五、AI 分诊风险分级规则

### 5.1 三档与取值

`1 绿`（居家观察）/ `2 黄`（尽快就医）/ `3 红`（立即急诊）。红色**必须**带就医建议与免责声明
（交付文档 9.5 的边界测试项）。

### 5.2 五道闸，顺序不能换

| 顺序 | 闸 | 规则 | 落点 |
| --- | --- | --- | --- |
| 1 | **硬红线短路** | 命中即 `risk=3`，**不调模型、不检索** | `knowledge_red_flag`（15 条种子，运营可增改）+ `ai/app/red_flags.py` |
| 2 | 模型分级 | 工具 schema 里的 `risk_level` enum，模型按提示词判 | `knowledge_prompt_template`（入库可改）+ `ai/app/prompts.py` |
| 3 | **分级规则抬档** | 命中规则则 `risk = max(模型值, min_level)`——**只抬不降** | `knowledge_grading_rule`（4 条种子）+ `ops.escalate()` |
| 4 | 降级路径的档位 | 图片类降黄（2）、模型输出不可用拔红（3） | `main.py` 的 `_DEGRADE_SPECS` |
| 5 | 输出护栏 | 药名 / 确诊 / 处方 / 剂量一律拦；红色强制带免责声明 | `knowledge_guard_term`（51 条）+ `ai/app/guardrails.py` |

### 5.3 四条已落库的分级规则（种子）

| code | 条件 | 下限 |
| --- | --- | --- |
| GR-001 | 幼宠呕吐或腹泻 | 至少黄 |
| GR-002 | 老年宠精神或食欲变化 | 至少黄 |
| GR-003 | 猫排尿异常 | **红** |
| GR-004 | 误食人用药物 | **红** |

规则表结构：`match_terms`（JSON 词表）+ `min_level` + `species_scope` + `age_stage_scope` + `advice`，
运营后台可直接改（`AiOpsController`）。

### 5.4 验收口径

- **红色召回 100%**：评测集 `ai/tests/eval_set/starter.yaml` 19 条，其中 10 条 `must_not_miss`
  （中暑 / 中毒 / 窒息 / 持续呕吐等），运行 `pytest -m eval` 作为发布门槛；
- **准确率 ≥ 70%**（0–6 月）：同上评测集；
- 降级可用：模型不可用时走规则兜底（`force_rule_only` 开关），回答里如实写「规则模式」。

---

## 六、入库管线与运营闭环

```
外部资料 → 结构化录入（按 §2.3 的必填字段） → pending_review
   → 兽医 / 专业人员复核（填 reviewed_by + reviewed_credential） → vetted
   → 生效（effective_from） → 检索可引用
   → 到期 / 有新证据 → 新版本（version 递增，旧版 archived）而不是原地改
```

- 入库是**数据作业**，不是代码改动：新增知识只插 `knowledge_entry` 行，不改代码、不发版；
- 运营可调项（提示词 / 红线词 / 分级规则 / 护栏词表 / 降级开关）走 ADR-0010 的第二层：
  **入库 + 运营后台**，改完即时生效；
- **AI 对库只读**（ADR-0009）：`ai/` 只 SELECT `knowledge_*`，其余数据一律经 Java 接口。
  `ai/app/db.py` 是唯一的只读出口。

---

## 七、当前缺口与下一步（如实）

| 缺口 | 影响 | 下一步 |
| --- | --- | --- |
| **40 条种子全 `pending_review`、0 条 `vetted`** | 引用可能为空；对外不得声称「基于知识库」 | **唯一进度瓶颈**：请专业方复核（ADR-0033 §六） |
| 类目数量与交付文档「八大类」表述不一致 | 评审时需对齐口径 | 已按十项实现，若甲方确认八项，删两个类目即可（结构不变） |
| 图谱边只有 27 条 | 召回补齐的覆盖有限 | 随条目复核同步补齐；每条边都要有证据条目 |
| `knowledge_chunk` 与向量检索未启用 | 大体量下的语义召回拿不到 | 触发条件写在 ADR-0022：条目数或评测表现不达标时复议 |
| 评测集 19 条偏小 | 70% 准确率的置信区间很宽 | 扩到 100 条标准症状集（交付文档 9.5 的要求） |
| 兽医复核流程（谁看、多久、驳回后怎么办）未定 | 复核动作无法形成制度 | ADR-0033 §六待澄清第 1 条，需要甲方指定责任人 |
