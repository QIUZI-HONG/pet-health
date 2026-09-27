# 宠物 AI 健康管理平台

以 AI 为基座、24 小时监护宠物健康的平台：先让用户低成本自助解决问题，解决不了再匹配服务者完成交易。三个端**全部是 Web**——C 端、服务者后台、运营后台。

## 开工前必读

1. **`CONTEXT.md`** —— 领域术语表。**命名以它为准**；「商家」「商户」「店铺」「merchant」是禁用词，统一说「服务者」。
2. **`docs/adr/`** —— 已定的架构决策。先看 ADR-0001 ~ 0004，那四条是对外部交付文档的刻意偏离。
3. **`README.md`** —— 目录结构与本地跑法。
4. **地图 [#52](https://github.com/QIUZI-HONG/pet-health/issues/52)** —— 哪些决策已定、哪些还没定、下一步做什么。

## 当前阶段

**规划阶段。** 交付路线正在地图上逐张裁决，尚未开始写业务代码。仓库里是文档、结构骨架与契约位置。

## 硬约束

- 领域命名遵循 `CONTEXT.md`；代码标识符用 `provider`
- 模块间只能走接口或领域事件，**禁止 join 其它模块的表**（ADR-0006；**唯一例外**见 ADR-0009——AI 服务只读 `knowledge_*`）
- 接口先写 `contract/` 的 YAML。**前端不手写接口类型**（由契约生成）；**后端 DTO 手写，但要与契约对齐**
- 金额用 `decimal(12,2)` 或以「分」为单位的整型，**禁止浮点**
- 所有写操作留 `operator_id` 与 `trace_id`
- **提交信息**用 `feat/fix/refactor/docs: 描述`；每个功能自测通过后再提交
- **AI 调用的唯一边界**：模型调用与知识检索都在 `ai/`（Python），后端一律经 `ph-ai` 走 HTTP。`ai/` 对数据库**只读 `knowledge_*` 表**，其余数据读写走 Java 接口（ADR-0009）
- **AI 配置分三层**：技术参数进环境变量，业务可调项（提示词/红线词/分级规则/降级开关）入库 + 运营后台，代码常量留代码（ADR-0010）
- **密钥只进本地 `.env`**（已被 gitignore）：不提交、不贴进对话、不写进文档

**写代码前读 [`docs/conventions.md`](docs/conventions.md)**——分页、脱敏、缓存、重试、软删除、图片规格这些实现级约定都在那里，别每处临时拍。

## Agent skills

### Issue tracker

Issues live as GitHub issues on `QIUZI-HONG/pet-health`, driven by the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The default five-role vocabulary, label strings identical to role names (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
