# AI 服务设计方案

> ⚠️ **供应商已变更（2026-09-27）**：本文里的模型名（qwen3-vl-plus / paraformer / qwen3-vl-embedding）
> 是设计时的假设，实际接的是 DeepSeek：**文本与图片可用（图片走 flash，pro 看不见图）**，
> 语音转写与向量不可用。逐项实测记录与影响见 [ADR-0017](../adr/0017-model-provider-deepseek.md)。
> 本文其余部分（职责边界、调用契约、护栏、留痕）仍然有效。


> 状态：**已裁决**（2026-09-27）。决策见 [ADR-0009](../adr/0009-ai-service-separate.md)（独立部署）与 [ADR-0010](../adr/0010-ai-config-layering.md)（配置分层）。
> 上游输入：[61 多模态模型选型调研](../research/61-multimodal-model-selection.md)、[63 分层知识库与 RAG](../research/63-knowledge-base-rag.md)

## 1. 职责边界

| 归 Java（`ph-ai` 模块） | 归 Python（`ai/` 服务） |
| --- | --- |
| 面向业务的唯一 AI 入口 | 提示词组装与版本选择 |
| 鉴权、每日配额计数 | 硬红线匹配、意图路由 |
| 调用留痕落库 | 知识检索（L2 关系 + L3 向量/全文） |
| 降级决策与错误码映射 | 模型调用（tool call + schema 校验 + 修复重试） |
| 响应组装 | 引用校验 |
| 日预算熔断 | 多模态预处理（图片缩放、语音转写编排） |

**边界规则**：AI 服务直连 MySQL / Redis，但**只读 `knowledge_*` 系列表**；其余一切数据的读写都走 Java 接口。这是 ADR-0009 给「模块间不直连对方的表」开的唯一例外，写窄、只读。

## 2. 调用契约

内部接口，**不经过公网网关**（`/internal/**`，仅宿主机/容器网络可达）。
鉴权：`X-Internal-Token` 共享密钥。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/internal/consult` | 一次健康咨询的完整分级 |
| GET | `/healthz` | 存活探测：只回 `{"status":"ok"}`，不要令牌（探针不需要知道配置状态） |
| GET | `/internal/health` | 能力矩阵与配置状态，**要 `X-Internal-Token`** |

### 请求

```json
{
  "trace_id": "a1b2c3d4",
  "user_id": 1001,
  "pet": {
    "id": 2001, "species": 1, "breed": "柯基",
    "birth_date": "2023-05-01", "weight": 8.2,
    "chronic": [], "recent_records": []
  },
  "input": {
    "type": "text",
    "text": "我家狗今天吐了两次，精神不太好",
    "media_urls": []
  },
  "history": []
}
```

> **`input.text` 是必填项，不是可选项。** 61 号调研实测：宠物皮肤病**纯图片**零样本分诊只有 33%，补上症状文本才到 72–94%。所以产品上必须强制用户补一句症状描述——这条约束从接口层就卡住，C 端的交互设计（#65）也要按它来。

### 响应

```json
{
  "risk_level": 2,
  "possible_causes": ["饮食不当", "急性肠胃炎"],
  "action_suggestion": "禁食 4–6 小时后少量给水，观察 12 小时",
  "need_hospital": true,
  "care_tips": ["记录呕吐物性状与频次"],
  "citations": ["K-0042", "K-0117"],
  "degraded": false,
  "degrade_code": null,
  "degrade_detail": null,
  "model_name": "qwen3-vl-plus",
  "model_version": "2025-12-19",
  "prompt_version": "triage-v3",
  "latency_ms": 1840
}
```

`degraded=true` 时，AI 服务给一个机器可读的 `degrade_code`
（`image_not_supported` / `image_unavailable` / `model_unavailable` / `model_output_invalid`），
明细放 `degrade_detail`（可能含异常类名、上游原文、模型原始输出）；
Java 侧按码映射成给用户看的一句中文（那是 `contract/app.yaml` 里 C 端的 `degrade_reason`），
`degrade_code` 与 `degrade_detail` 只进留痕。**两个字段故意不同名**：同名双语义会让下一次改动改错地方。
（交付文档的 **60001/60002** 目前没有产出路径——降级一律 200 + `degraded`，见 `ErrorCode` 的注释。）

## 3. 处理链路

架构来自 63 号调研，兜底逻辑来自 61 号调研：

```
① 硬红线匹配（L1 词典，<100ms）
     命中 → 直接判红，跳过 ②③④，但仍走 ⑤ 生成措辞
② 意图路由
     症状 / 疾病 → L2 关系查询 + L3 混合检索
     疫苗 / 驱虫周期 → L1 结构化查询（不走检索）
③ 组装（宠物档案 + 检索结果 + 提示词模板）
     检索为空 → 不生成，直接返回降级话术
④ 生成 —— 走 tool call，不用 json_object
     多模态输入下没有任何厂商支持 json_schema 强约束
⑤ 校验与兜底
     schema 校验失败 → 带错误信息重试一次
     二次失败     → 风险等级拔高 + need_hospital=true + 记指标
     引用校验     → 剔除引用 ID 不在本次召回集内的句子
     关键结论全被剔除 → 降级话术
```

## 4. 配置项清单

这是本次规划的核心：每项配置在哪、谁能改、怎么生效。

### Java 侧（环境变量 / `application.yml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `AI_SERVICE_BASE_URL` | `http://127.0.0.1:8000` | AI 服务地址 |
| `AI_SERVICE_TOKEN` | — | 内部共享密钥，**不入库不入仓** |
| `AI_TIMEOUT_MS` | `8000` | 单次调用超时。交付文档说 10 秒后前端提示重试，这里留 2 秒余量做降级 |
| `AI_DAILY_BUDGET_CNY` | `50` | 日预算上限，超限熔断 |
| `AI_FREE_QUOTA_PER_DAY` | `3` | 免费用户每日次数（交付文档 F006） |

### Python 侧（环境变量，见 `ai/.env.example`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | — | 阿里云百炼密钥，**只进本地 .env** |
| `AI_MODEL_CHAT` | `qwen3-vl-plus` | 风险分级主模型 |
| `AI_MODEL_FAST` | `qwen3-vl-flash` | 常规问答 |
| `AI_MODEL_ASR` | `paraformer-v2` | 语音转写 |
| `AI_MODEL_EMBED` | `qwen3.7-text-embedding` | 向量嵌入 |
| `AI_MAX_REPAIR_RETRY` | `1` | schema 校验失败后的重试次数 |
| `MYSQL_*` / `REDIS_*` | — | 只读知识域连接 |

### 入库 + 运营后台可改（对应交付文档 B8）

| 项 | 说明 |
| --- | --- |
| 提示词模板 | 带版本号，支持灰度比例 |
| 硬红线词词典 | 命中即红灯，不经模型 |
| 风险分级规则 | 绿/黄/红的判定与边界 |
| 降级开关 | 运营一键切到纯规则通道 |
| 灰度比例 | 提示词版本的分流比例 |

## 5. 护栏

**每日配额**：Redis 原子计数 `ai:quota:{userId}:{yyyyMMdd}`，TTL 到次日 0 点自然过期。超限引导升级（不走错误码，走产品引导）。

**日预算熔断**：`ai:budget:{yyyyMMdd}` 累计估算成本（按 61 号调研的单价 × token 用量）。超 `AI_DAILY_BUDGET_CNY` → 熔断 → 只走规则通道 + 告警。61 号调研测算成本不是瓶颈（10 万用户 DAU 30% 约 3,159 元/月），但代码死循环或刷接口能把预算烧穿——**这是唯一的财务安全网**。

**降级链路**：

| 触发 | 行为 | 错误码 |
| --- | --- | --- |
| 调用超时 | 返回规则通道结论 / 「已提交，稍后通知」 | 60001 |
| 调用失败 | 「AI 暂时不可用，已记录」 | 60002 |
| 检索为空 | 不生成，返回「知识库没有足够依据，建议就医」 | — |
| 两次 schema 失败 | 风险等级拔高 + 强制就医建议 | — |
| 硬红线命中 | 直接红灯，跳过模型 | — |
| 运营关闭降级开关 | 全量走规则通道 | — |

**对用户的承诺**：以上每一条都不向用户报错。规则通道使「AI 不可用」不等于「功能不可用」。

## 6. 留痕

- **业务表** `ai_consultation`：`user_id` / `pet_id` / `input_type` / `risk_level` / `result` / `model_name` / **`model_version`** / **`prompt_version`** / `latency_ms` / `degraded`
- **两个通道的结论分开记**（规则通道命中什么、模型通道给什么、最终取谁）——没有这个，badcase 无法归因
- **traceId 贯穿** Java → Python → 模型调用
- Python 侧结构化日志

## 7. 这份方案还没解决的

- **转人工咨询的形态**——[#71](https://github.com/QIUZI-HONG/pet-health/issues/71)：ADR-0002 之后没有线上支付，「39 元/次」不成立
- **风险分级的具体标准与评测集**——[#62](https://github.com/QIUZI-HONG/pet-health/issues/62)
- **知识库三层的内容生产**——63 号调研给了架构，内容是另一回事
- **合规口径**：61 号调研提到，我们本来就要做的 JSON 修复重试、字段兜底、风险等级拔高，**是否构成需要备案的「实质性修改」**——该结论来自二手转载，需法务确认，且会反向约束 #62 的评测设计
