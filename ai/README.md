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
curl -s localhost:8000/internal/health
# {"status":"ok"}
```

## 当前进度

**模型调用已打通（纯文本）**，供应商与实测能力见 [ADR-0017](../docs/adr/0017-model-provider-deepseek.md)：

| 能力 | 状态 |
| --- | --- |
| 文本分级 | ✅ 真调用，工具调用出结构化结果，失败一律降级 |
| 硬红线预检 | ⬜ 词典要入库（[#103](https://github.com/QIUZI-HONG/pet-health/issues/103)），现在靠提示词铁律 |
| 知识检索（L1/L2/L3） | ⬜ 未实现，所以 `citations` 恒为空——不编造条目 ID |
| 图片 / 语音 / 向量 | ❌ 当前供应商不具备（带图请求明确降级并告知） |

`/internal/health` 会报出能力矩阵（`capabilities`），别靠猜。

## 怎么跑测试

```bash
.venv/bin/ruff check .
.venv/bin/python -m pytest                 # 常规：模型被桩掉，不依赖网络
AI_LIVE_TEST=1 .venv/bin/python -m pytest -m live   # 实弹：真打模型（需要 key 与网络）
```

## 两条不能忘的约束

- **`input.text` 必填**：宠物皮肤病纯图片分诊只有 33%，必须强制用户补症状文本。
- **结构化输出走 tool call**，不用 `json_object`——多模态输入下没有任何厂商支持 `json_schema` 强约束；
  且当前模型只能 `tool_choice: auto`（强制指定在思考模式下被拒），所以「模型没调工具」要按正常分支重试。

## 密钥

`AI_API_KEY` 只写在本地 `.env` 里，已被 `.gitignore` 忽略。**不要提交、不要贴进对话、不要写进任何文档。**
