# AI 服务

模型的调用、知识检索与提示词编排都在这里；后端不再直接调用大模型（[ADR-0009](../docs/adr/0009-ai-service-separate.md)）。

**完整设计见 [docs/design/ai-service.md](../docs/design/ai-service.md)** —— 职责边界、调用契约、配置清单、护栏、留痕都在那份文档里。本文件只说怎么跑起来。

## 跑起来

```bash
cd ai
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

cp .env.example .env        # 填入 DASHSCOPE_API_KEY 等真实值；.env 不会进版本库
uvicorn app.main:app --reload --port 8000
```

验证：

```bash
curl -s localhost:8000/internal/health
# {"status":"ok"}
```

## 当前进度

**骨架。** 接口契约与配置已就位，处理链路（硬红线 → 意图路由 → 检索 → 生成 → 校验）尚未实现，`/internal/consult` 返回保守的降级结果并标记 `degraded: true`。

实现顺序见地图 [#52](https://github.com/QIUZI-HONG/pet-health/issues/52)：知识库结构（[#63](https://github.com/QIUZI-HONG/pet-health/issues/63) 已给架构）与风险分级标准（[#62](https://github.com/QIUZI-HONG/pet-health/issues/62)）都得先定，链路才有东西可跑。

## 两条不能忘的约束

- **`input.text` 必填**：宠物皮肤病纯图片分诊只有 33%，必须强制用户补症状文本。
- **结构化输出走 tool call**，不用 `json_object`——多模态输入下没有任何厂商支持 `json_schema` 强约束。

## 密钥

`DASHSCOPE_API_KEY` 只写在本地 `.env` 里，已被 `.gitignore` 忽略。**不要提交、不要贴进对话、不要写进任何文档。**
