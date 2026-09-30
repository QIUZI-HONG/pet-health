# 知识库结构与检索：关键词 + 关系层、引用口径的落地、运营配置入 DB

挡 [#100](https://github.com/QIUZI-HONG/pet-health/issues/100) / [#101](https://github.com/QIUZI-HONG/pet-health/issues/101) / [#103](https://github.com/QIUZI-HONG/pet-health/issues/103)。

上游：`docs/research/63-knowledge-base-rag.md`（三层架构与检索策略）、[ADR-0009](0009-ai-service-separate.md)（AI 服务只读 `knowledge_*`）、[ADR-0010](0010-ai-config-layering.md)（配置分层）、[ADR-0021](0021-risk-grading-and-red-flags.md)（红线与护栏）、[ADR-0022](0022-knowledge-retrieval-without-vector.md)（不做向量）、[ADR-0025](0025-scoring-kb-seed-budget-compliance-routing.md)（种子口径）、[ADR-0040](0040-reports-kb-citations-realtime-photo-wall.md) 第二节（**引用口径已由项目所有者拍板**）。

## 一、库表（迁移 V18 / V19 / V20，各带 U18 / U19 / U20 回滚）

| 表 | 层 | 装什么 |
| --- | --- | --- |
| `knowledge_category` | 字典 | 十类目（F024 / F025 的品种 / 疫苗 / 驱虫 / 症状分诊 / 疾病 / 行为 / 营养 / 护理 / 急救 / 药物）。文档写「八大类」却列了十项，按十项建（63 号调研 §1.3 记为待核对） |
| `knowledge_entry` | **L3 + L1** | 条目正文（`title` / `summary` / `body`）+ **结构化载荷 `structured_payload`**（疫苗周期、驱虫频次、毒物清单、急救步骤）。`code`（K-xxxx）是对外编号，跨环境稳定；`FULLTEXT ... WITH PARSER ngram` 建在 `(title, summary, body)` 上 |
| `knowledge_chunk` | L3（向量，挂起） | 分块表**建了但检索侧不读**，`embedding_model` 全部为空——ADR-0022 的「留接口不实现」在数据上可见 |
| `knowledge_node` / `knowledge_edge` | **L2 关系层** | 薄节点（symptom / disease / drug / food / species / breed / condition）+ 一张边表装全部关系；**每条边强制有 `evidence_entry_code`**（没来源的边不允许存在） |
| `knowledge_prompt_template` / `knowledge_grading_rule` / `knowledge_guard_term` / `knowledge_switch` | 运营配置 | 提示词（版本 + 灰度）、分级规则、护栏词表、运行时开关。**为什么在 `knowledge_*` 下**见第三节 |
| `knowledge_red_flag` | L1 | 红线规则（V6 已有，本期补的是运营增删接口） |

留痕扩展（同一批迁移）：`ai_consult` 增 `citations` / `unvetted_hits` / `grading_rule_hits` / `retrieval_check` 四列。没有它们，事后无法回答「这句话当时有依据吗、依据复核过吗、结论是模型判的还是规则抬的档」。

**没有 `breed` 之外的 L1 独立表**：疫苗/驱虫周期放在条目的 `structured_payload` 里，而不是各建一张表。理由：同一件事既要可判定（几针、间隔多久）又要能解释（为什么这样打），拆两张表会让引用模型出现两套编号。

## 二、检索算法：L1 查询 + L2 关系 + L3 ngram 关键词（**不做向量**）

实现在 `ai/app/knowledge.py`（Python，ADR-0009 的边界内），一次检索最多四次小查询：

1. **L1 结构化查询**：问题命中疫苗/驱虫/误食类触发词时，按 `category_code + structured_payload IS NOT NULL` 查条目，**不走全文索引**（交付文档的分工：周期类问题走结构化查询）。命中项排最前——它是「唯一权威」，L3 文本只能解释。
2. **L2 关系层**（两件事）：
   - **召回补齐**：症状节点命中后（走 `name` + `alias` 受控词典归一，如「不吃东西」→ 食欲下降），
     ① 把该症状自己的说明条目拉进来，② 沿 `may_indicate` 边把疾病条目拉进来（按人工维护的 `weight` 排序，边上的 `note` 作为可审核的一句话理由进上下文）；
   - **安全门**：`contraindicated_for` / `toxic_to` 命中的条目**整条剔除**（「猫用对乙酰氨基酚」这类推荐，向量相似度拦不住，一张审核过的边表可以）。
     判据与输出护栏共用（`guardrails.is_recommended_mention`）：**只认「推荐」语境**，「不要给猫用 X」这类警告保留；药物自己的说明条目用更严的判据（要求出现正向推荐线索），否则最有价值的安全提醒会被自己删掉。
3. **L3 关键词**：`MATCH(title, summary, body) AGAINST ('"词1" "词2"' IN BOOLEAN MODE)`，ngram 解析器下短语 = **中文子串匹配**（#63 实测可用）。检索词 = 受控词典命中（长词优先）+ 二字窗口兜底（中文没有词边界）；布尔语法字符被洗掉（用户输入 `-呕吐` 不该变成「排除呕吐」）。
4. **排序在 Python 做，可解释**：`0.5 × 词覆盖度 + 0.5 × (MySQL relevance / 本批最大值)`，L1 优先、L2 次之。为什么两个信号都要：只用覆盖度会把「词出现在正文而不是标题」的条目压下去（实测「今天呕吐了两次」把最贴题的 K-0010 排到第 6）；只用 relevance 则无法解释也无法在测试里复现。
5. **进上下文的文本再过两道关**：安全门（上面的 2）→ 输出护栏（`guardrails.review`）。命中后的取舍**分两种**：越界段落整段改写；**标题或摘要被改写则整条剔除**（主张越界＝不能当上下文）。数量上限 `knowledge_top_k`（默认 5）、单条正文截断 `knowledge_body_chars`。

**为什么不用向量**：[ADR-0022](0022-knowledge-retrieval-without-vector.md) 已定——当前供应商没有 `/embeddings` 端点（[ADR-0017](0017-model-provider-deepseek.md) 实测 404），#63 论证了「语料不到 5000 条时向量增益无法证明」。**重开条件写在 ADR-0022 里**（语料 >5000 条，或出现三个记录在案的召回缺口实例）；缺口实例的观测口就是 `retrieval_check=empty` 的留痕。

## 三、哪些东西入 DB、哪些留代码（ADR-0010 三层的判据）

| 类别 | 例子 | 落点 | 判据（怎么分） |
| --- | --- | --- | --- |
| 技术参数 | 连接串、超时、缓存秒数、模型名、`knowledge_top_k` / `max_terms` / `body_chars` | `ai/app/config.py` + `.env` | 改错一次**全线**受影响，且只能靠重启生效 |
| 业务可调项 | 提示词（带版本与灰度）、红线词、分级规则、护栏词表、降级开关 | 库 + 运营接口（`/api/v1/admin/ai/**`） | 现场**最频繁**改动、改错只影响一小类输入、要求**即时生效**（Python 侧带 TTL 直读） |
| 代码常量 | 剂量正则、引用校验、检索融合与排序、开关的语义 | 代码 | 改错的后果是「该拦的没拦」而运营无从验证；且它必须与测试同版本 |

**命名上的代价如实写在这里**：这四张运营配置表落在 `knowledge_*` 下（提示词严格说不算「知识」），
因为 ADR-0009 只允许 AI 服务读 `knowledge_*`，而它们**必须在每次咨询时被 Python 直读**——
走 Java 接口意味着 Java 调 Python、Python 再回调 Java 取配置，多一跳，还把配置可用性绑在另一个进程上。
ADR-0021 为红线词表做过同样的取舍并写明理由，这里沿用：**例外没有扩大，只是「AI 服务直读的配置与知识」都住在 `knowledge_*` 下**。

**回落是硬要求**：库里读不到（迁移没跑、库抖动、运营清空）时，提示词/护栏词表回落到代码基线、分级规则不生效、开关一律按关闭算，并记 `ops.available=False`——
**绝不让咨询 500**（与红线的 `available` 口径一致：服务在跑但配置没生效，必须能被看见）。

## 四、引用口径的落地（ADR-0040 第二节的裁决 → 代码）

- `review_status` 只有两级：`pending_review` / `vetted`。**种子 40 条全部 `pending_review`、`reviewed_by` 为空**——`vetted` 只能由兽医复核产出，工程不许盖章（有测试钉着「种子里 vetted 数为 0」）。
- 检索**两种状态都取**（只取 vetted 会让检索层在种子阶段空转），但：**只有 `vetted` 能进 `citations`**；未复核条目被用到时，Python 回 `unvetted_hits`，Java 把它拼进免责声明——用户看到「本次建议参考了尚未经兽医复核的平台资料」。
- **`citations` 只带真被引用的条目**：编号出现在最终文案里（模型自报的 `citations` 参数也要过召回集校验），且必须是 vetted。召回集 ≠ 引用集。
- **「基于知识库」这句话的解禁条件**是「至少一条 vetted 引用」：`AiConsultService` 按 `citations` 是否为空选免责声明（五分支：降级 / 红线短路 / 有已复核引用 / 只用未复核 / 无来源）。没有 vetted 引用时一律不说「知识库」。
- 引用校验（#101 第三条）：引用编号不在召回集里且没有别的有效编号 → **整条结论剔除**并记 `citation:<编号>`；一条引用都没有的结论默认保留但记 `citation:unbacked:n`（观测口），严格口径下才降级（见下）。
- 写操作留痕：运营的每次改动落 `created_by` / `updated_by` / `trace_id`（ADR-0011），并保留**旧版本行**——`citations` 与 `prompt_version` 要能回溯到当时那一版。
- 运营接口**没有改 `review_status` 的入口**：复核是人工流程，给它开后门等于假造复核状态。

## 五、与票面验收标准的偏离（都是刻意的，逐条说明）

| 票面原文 | 实际做法 | 理由 |
| --- | --- | --- |
| #100「向量检索走 Redis 8 Query Engine」 | 留接口不实现 | ADR-0022（供应商无 `/embeddings`） |
| #101「检索为空时不生成，返回降级话术」 | **默认照常回答**（无来源口径），严格模式由开关 `retrieval_strict` 控制 | 语料只有几十条、关键词召回率低，默认开会让绝大多数咨询变成固定话术——与 ADR-0025 对「不熔断」的取向冲突（那里也是「不把功能降级成固定话术」）。开关已实现，随时可切成票面口径 |
| #101「关键结论全被剔除则降级」 | 同上（严格开关打开时降级，回 `degrade_code=knowledge_empty`） | 同上；「模型没按格式标编号」不该直接让用户拿不到结论 |
| #101「违规推荐硬过滤」 | `contraindicated_for` / `toxic_to` 边 + 「推荐 vs 警告」判据 | 若按「提到禁忌词就剔除」，K-0070（讲人用药为什么不能给猫吃）会被自己拦掉 |

新增一个降级码 `knowledge_empty`（严格模式下用）：它属于 ADR-0026 的降级码空间，**用户可见文案仍由 Java 侧映射**，Python 只回机器可读的码。

## 六、待澄清（需要甲方 / 兽医 / 产品拍板，工程不替它们决定）

1. **兽医复核流程本身**：谁复核、复核记录留在哪、复核一个人还是两人（63 号调研 §8 第 8 条把「校对人力」列为**唯一进度瓶颈**）。这决定 `vetted` 从哪来——**在此之前，`citations` 会一直是空的，C 端一直不能说「基于知识库」。**
2. **种子内容的医学复核**：这 40 条是工程按公开共识起草的（ADR-0025 第二节），每条的 `source_title` 可追溯，但**没有一条经兽医签字**。风险最高的是「高频模糊症状」（呕吐 / 腹泻 / 精神不振 / 食欲下降）的条目与 `risk_hint`。
3. **`age_stage` 阈值**（犬 <1 / 1–7 / >7 岁，猫 <1 / 1–10 / >10 岁）仍是占位值，它同时影响红线过滤、分级规则与检索过滤（ADR-0021 已记过一次）。
4. **抽检要不要给运营看问题原文**：`ai_consult.question_enc` 是病历口径的密文（ADR-0013），解密展示属权限与合规问题，未定。当前抽检接口只给结构化事实，不给原文。
5. **运营接口的契约与权限**：`contract/admin.yaml` 把「AI 运营」列为**仍未定义**，而本切片的文件边界不允许改它——这批接口暂时没有契约条目，需要由契约的负责人补（同时补 `contract/app.yaml` 的 `AiConsultView.citations`）。运营与超管在 token 里也还没区分（#60 / ADR-0035），所以「谁能关降级开关」目前是「所有 admin 域身份」。
6. **`retrieval_strict` 的默认值**：若产品要求严格按 #101 的字面口径（空召回即不生成），把它改成默认开即可——但要先有足够的语料与一次召回率评估（评测集目前只覆盖分级）。
7. **前端展示**：`AiConsultCitation` 的界面形态（来源列表怎么放、要不要标「已复核」徽标）属 #118；本切片只保证接口给全了字段。

## Consequences

- **C 端的「来源」这一栏现在是诚实但空的状态**：种子没复核 → `citations` 空 → 界面维持「不做来源承诺」的措辞，同时如实提示「参考了尚未经兽医复核的资料」。这不是没做完，而是口径的必然结果：**来源的可用性取决于复核人力，不取决于代码**。
- 留痕多了四列，运营抽检与事后归因（按 `prompt_version` 看分级分布、看降级率、看空召回率）才成立。
- 检索失败、配置读不到都走「降级但不报错」：用户拿到的永远是一段格式正常的回答，**失败只在留痕与日志里可见**——所以 `retrieval_check` / `red_flag_check` / `guard_hits` 这三个字段是排查的入口，改动它们要慎重。
- 运维要知道两件事：运营改完最多滞后一个 TTL（默认 60 秒）生效；回滚 V18/V19/V20 会同时丢掉知识条目与运营的全部改动（U18/U19/U20 里各写了代价）。
