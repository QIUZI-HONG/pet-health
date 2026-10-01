# 知识条目的复核入口：两级状态 + 一个专用写口 + 复核人资质入库

上位决策是 [ADR-0040](0040-reports-kb-citations-realtime-photo-wall.md) 第二节
（知识条目带复核状态、**只有 `vetted` 能进 citations`、**「复核流程本身是人工流程（谁来复核、记录留在哪）
属待澄清事项，不许在代码里假造一个复核状态」**）、[ADR-0025](0025-scoring-kb-seed-budget-compliance-routing.md)
（引用来源与内容边界的硬要求）、[ADR-0033](0033-knowledge-base-and-retrieval.md)（三层知识库与检索）、
[ADR-0011](0011-persistence-and-migrations.md)（写操作留痕）。
**本 ADR 把 ADR-0040 那条「待澄清」补上，不推翻它。**

落地位置：`server/ph-ai/**`（`domain/AiOpsTables.KnowledgeEntry`、`mapper/AiOpsMappers.KnowledgeEntryMapper`、
`service/AiOpsService#listKnowledgeEntries` / `#reviewKnowledgeEntry`、`web/AiOpsController` 的两个端点）
+ DTO 在 `ph-api` 的 `com.pethealth.api.admin.AiOpsDtos` + 契约 `contract/admin.yaml` 的
`/ai/knowledge-entries**` + 集成测试 `ph-boot` 的 `com.pethealth.boot.ai.AiOpsAdminTest`。

## 问题：那条留白让「AI 引用」一直出不来

`knowledge_entry` 从 V18 起就有 `review_status`（两级：`pending_review` / `vetted`），种子 40 条**全是
`pending_review`**，而只有 `vetted` 能进 `citations`。可当时的实现里**没有任何写入口**——
运营要把一条内容复核通过，只能手改数据库。后果是：C 端的 AI 回答永远没有引用，
而现象看起来像「检索没接」（那是另一条已经接好的链路）。

ADR-0040 那句「不许在代码里假造一个复核状态」的**本意是禁止自动置位**（批量把种子刷成 vetted、
按某个规则自动判定通过），不是禁止记录一次真实的人工复核。这两件事的差别就是本 ADR 的边界。

## 决定

**一、复核状态仍是两级，不扩第三态。** `vet` → `vetted`；`reject` → 置回 `pending_review`。
「打回」不是一个新的状态，它是「还没复核」（V18 的列注释早就这么定了）。

**二、只有一个写入口，且必须填「谁 + 什么资质」。**
`POST /api/v1/admin/ai/knowledge-entries/{code}/review`，请求体 `{action, reviewer, credential}`：
`reviewer`（姓名）与 `credential`（如「执业兽医师，证号 XXXX」）**必填**，
通过时写入 `reviewed_by` / `reviewed_credential` / `reviewed_at`。

**三、其余运营接口碰不到这个字段。** 提示词 / 红线词 / 分级规则 / 护栏词 / 开关那五个接口
**没有** `review_status` 入参，将来也不该加——那正是 ADR-0040 要防的「把一句专业背书降格成一次开关操作」。
`AiOpsAdminTest` 的类注释与用例守着这条边界。

**四、写操作照常留痕**：`updated_by` / `trace_id` 由审计列自动填（ADR-0011）；
打回时清掉复核人三列，**谁打回的看审计列**（`knowledge_entry` 没有「打回人」这一列，也不新加——
两级模型下它的语义是「回到未复核」，不是「被否决的记录」）。

**五、列表只给复核要看的字段**：`body`（正文）与 `structured_payload`（L1 载荷）**不进 Java 侧的
实体与视图**——它们是 AI 服务的检索素材，运营复核看的是标题、摘要、来源与状态。

**六、清空必须用条件更新的显式 `set(…, null)`。** MyBatis-Plus 的 `updateById` **跳过 null 字段**，
用它来「清掉复核人」是一次静默失败：接口返回的视图是空的，库里的名字还在。
`AiOpsAdminTest#reviewRecordsWhoAndWhat` 有一条断言专门盯这个（先看视图、再看库）。

## Consequences

**能用的那部分**：运营在后台就能把一条内容复核通过，AI 引用从此有来源；
「谁背的书」落在数据里，出了内容问题能追到人；两级模型不膨胀，检索侧与 C 端话术都不用改。

**仍然是人工的事（写下来免得被当成自动化）**：
- **没有批量复核**：一次一条。40 条种子要过一遍就是 40 次人工判断——这是刻意的，
  「批量通过」等于把背书变成点按钮；
- **没有复核队列的分派与提醒**：谁该复核、多久内复核，仍是流程问题（甲方/运营侧）；
- **资质是自由文本**：谁有资格复核由流程保证，代码只负责把它记下来（要做「只有认证兽医能复核」
  得先有兽医账号体系，那是另一个切片）。

**对验收的影响**：AI 引用「为空」这件事，从「代码缺口」变成了**运营动作缺口**——
种子 40 条一条都没复核，引用就一条都不会有。演示前要么先过几条，要么在演示话术里说明。
（此前这条只写在第五轮报告 §7.3 的准备清单里，现在有了后台入口。）
