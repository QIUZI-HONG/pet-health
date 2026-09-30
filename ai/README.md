# AI 服务

模型的调用、知识检索与提示词编排都在这里；后端不再直接调用大模型（[ADR-0009](../docs/adr/0009-ai-service-separate.md)）。

**完整设计见 [docs/design/ai-service.md](../docs/design/ai-service.md)** —— 职责边界、调用契约、配置清单、护栏、留痕都在那份文档里。本文件只说怎么跑起来。

## 跑起来

```bash
cd ai
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

cp .env.example .env        # 填入 AI_API_KEY；.env 不会进版本库
uvicorn app.main:app --reload --port 8000
```

验证：

```bash
# 探针：不需要令牌，只回一句话
curl -s localhost:8000/healthz
# {"status":"ok"}

# 能力矩阵：需要内部令牌（它描述我们的配置状态，不是给公网看的）
curl -s -H "X-Internal-Token: $INTERNAL_TOKEN" localhost:8000/internal/health
```

## 当前进度

**链路已齐（切片 #100 / #101 / #103 之后）**，供应商与实测能力见 [ADR-0017](../docs/adr/0017-model-provider-deepseek.md)，
知识库与引用口径见 [ADR-0033](../docs/adr/0033-knowledge-base-and-retrieval.md)：

| 能力 | 状态 |
| --- | --- |
| 文本分级 | ✅ 真调用，工具调用出结构化结果，失败一律降级 |
| 硬红线预检 | ✅ 判据在 `knowledge_red_flag`（ADR-0021），命中即判红且**不调模型**；运营增删走 `ph-ai` 的 `/api/v1/admin/ai/red-flags` |
| 知识检索（L1/L2/L3） | ✅ `app/knowledge.py`：L1 结构化查询 + L2 关系层（召回补齐与安全门）+ L3 MySQL ngram 关键词检索；命中条目进上下文并做引用校验 |
| 运营可调项 | ✅ 提示词（版本 + 灰度）、分级规则、护栏词表、降级开关入 DB（`app/ops.py`，带 TTL 直读）；读不到回落到代码基线 |
| 引用 `citations` | ✅ 只含 **vetted（兽医复核过）** 的条目；未复核条目命中时回 `unvetted_hits`，由 Java 侧拼「尚未经兽医复核」。**种子里没有 vetted，所以当前 citations 为空**——复核流程属待澄清事项（ADR-0033） |
| 图片 | ✅ 走 `ai_vision_model`（当前 flash；**pro 看不见图**）。没配视觉模型时明确降级并告知 |
| 语音 / 向量 | ❌ 该供应商没有这两个端点；向量层按 ADR-0022 挂起（`knowledge_chunk` 建了但不参与检索） |

连库的检查（ngram 中文子串、三层检索、种子与引用口径）在 `tests/test_knowledge_db.py`：
**需要 MySQL 且 V18/V19 已迁移**，连不上就跳过——它不会把「本地有库」变成跑测试的前提。

**发布门槛**（`pytest -m eval`）：真打模型跑 `tests/eval_set/`，准确率 ≥70%、
**红色召回率必须 100%**，逐条明细落 `tests/eval_set/report-<日期>.md`。缺 key 时它**失败而不是跳过**
——`-m eval` 是显式点名要跑门槛，静默通过等于门槛不存在。

`/internal/health` 会报出能力矩阵（`capabilities`），别靠猜。

## 怎么跑测试

```bash
.venv/bin/ruff check .
.venv/bin/python -m pytest                 # 常规：模型被桩掉，不依赖网络
AI_LIVE_TEST=1 .venv/bin/python -m pytest -m live   # 实弹：真打模型（需要 key 与网络）
.venv/bin/python -m pytest -m eval -s      # 发布门槛：真打模型跑评测集（ADR-0021）
```

## 两条不能忘的约束

- **`input.text` 必填**：宠物皮肤病纯图片分诊只有 33%，必须强制用户补症状文本。
- **结构化输出走 tool call**，不用 `json_object`——多模态输入下没有任何厂商支持 `json_schema` 强约束；
  且当前模型只能 `tool_choice: auto`（强制指定在思考模式下被拒），所以「模型没调工具」要按正常分支重试。

## 密钥

`AI_API_KEY` 只写在本地 `.env` 里，已被 `.gitignore` 忽略。**不要提交、不要贴进对话、不要写进任何文档。**
