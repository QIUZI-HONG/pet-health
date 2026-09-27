# 模型供应商用 DeepSeek：文本可用，图片 / 语音 / 向量暂缺

AI 服务的供应商换成 **DeepSeek（OpenAI 兼容接口）**，模型用 `deepseek-flash`。这是**实测定的**，不是选型比稿——下面每一条都在 2026-09-27 当天用项目自己的 key 验过。

本 ADR 覆盖 [docs/research/61-multimodal-model-selection.md](../research/61-multimodal-model-selection.md) 里「主力 qwen3-vl-plus 跑分级、flash 跑常规问答、ASR 用 paraformer、向量用 qwen3-vl-embedding」这套**供应商侧结论**；#61 的分析方法（结构化输出要自己兜、纯图片不可用、延迟必须自测）仍然有效。

## 实测事实

| 能力 | 结果 | 证据 |
| --- | --- | --- |
| 文本分级 | ✅ 可用 | `/chat/completions` 正常返回，工具调用参数合法 |
| 结构化输出 | ✅ 走工具调用 | 只能 `tool_choice: auto`；**强制指定工具在思考模式下被拒**（HTTP 400 `Thinking mode does not support this tool_choice`） |
| 图片输入 | ❌ 看不见 | 结构上接受图片块，但 `deepseek-v4-pro` 回「无法确定」；`deepseek-flash` 把输出预算全烧在 reasoning 上、`content` 为空 |
| 语音转写 | ❌ 无此端点 | `/audio/transcriptions` → 404 |
| 向量 | ❌ 无此端点 | `/embeddings` → 404 |
| 可用模型 | 两个 | `GET /models` → `deepseek-flash`、`deepseek-v4-pro`（都是推理型，响应里带 `reasoning_tokens`） |

**延迟实测**（同一段症状，各 3 次）：

| 模型 | 各次耗时 | 中位数 |
| --- | --- | --- |
| `deepseek-v4-pro` | 5.6 / 11.3 / **20.3（超时失败）** | 11.3 s |
| `deepseek-flash` | 5.0 / 5.3 / 6.3 | **5.3 s** |

## 决定

- **默认 `deepseek-flash` 跑分级。** 不是因为更强（pro 更强），而是因为 **pro 的延迟分布跨到 20 秒并确实超时**，而调用方的超时预算是按「尽快给用户答复」定的。分级质量该由 [#62](https://github.com/QIUZI-HONG/pet-health/issues/62) 的评测集用漏判率来判断，不该由「哪个名字看着厉害」来判断——评测集落地后如果 pro 的漏判率显著更低，就换成 pro 并同步放大超时预算。
- **提示词里明写铁律**（不给确诊/处方/剂量；呼吸困难、抽搐、误食毒物等一律红）。硬红线词典要入库（[#103](https://github.com/QIUZI-HONG/pet-health/issues/103)），表没建之前**没有红线预检这一层**，这是与 `docs/design/ai-service.md` 的已知差距。
- **图片请求明确降级并告知**，不静默忽略：用户以为模型看过照片、其实没看，比直接说不支持危险得多。契约里的 `input.media_urls` 保留（换供应商即可用），能力开关是配置项 `ai_supports_image`。
- **结构化输出用工具调用 + 客户端校验 + 一次修复重试**（沿用 #61 的结论）。「模型没调工具」是必须处理的正常分支，不是异常。

## Consequences

**产品的图片与语音能力全部挂起。** 交付文档里「拍照上传皮肤/耳道/排泄物」「语音提问」两条现在做不到。可做的是：先说清不支持（已实现）、把症状引导成文字。要恢复这两条，需要第二个供应商（多模态一家、文本一家），那是新决策——本 ADR 不预先批准。

**知识检索的向量层同样缺位。** `redis` 里的向量索引与 L3 检索（[#63](https://github.com/QIUZI-HONG/pet-health/issues/63)）需要 embedding 能力，这家没有。关键词检索走 MySQL ngram 不受影响（#63 本来就给中文关键词选的路）。

**超时预算必须重算。** 后端 `ai.service.timeout-ms` 的默认值已按实测从 8 秒调到 20 秒：一个中位数 5.3 秒、偶发 20 秒的供应商，配 8 秒超时等于常态化降级。前端那句「10 秒提示重试」也要跟着重新定（交付文档的假设建立在「没有厂商 P95 数据」之上，现在有数据了）。

**推理型模型有个坑写进了配置注释**：输出预算被 reasoning 吃掉时返回的是 `finish_reason=length` + 空 `content`（40 / 120 / 900 token 都踩过），看起来像「模型不说话」。所以 `ai_max_output_tokens` 默认 1500，别按普通模型的习惯写 256。

**密钥**：`AI_API_KEY` 只进本地 `ai/.env`（已 gitignore）。变量名从 `DASHSCOPE_API_KEY` 改成供应商中立的名字——换供应商时不用再改一遍代码与文档。
