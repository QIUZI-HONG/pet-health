# 宠物 AI 健康管理平台

以 AI 为基座、24 小时监护宠物健康的平台：先让用户低成本自助解决问题，解决不了再匹配服务者完成交易。三个端**全部是 Web**——C 端、服务者后台、运营后台。

## 开工前必读

1. **`CONTEXT.md`** —— 领域术语表。**命名以它为准**；「商家」「商户」「店铺」「merchant」是禁用词，统一说「服务者」。
2. **`docs/adr/`** —— 已定的架构决策（当前 54 条）。先看 ADR-0001 ~ 0004，那四条是对外部交付文档的刻意偏离。
3. **`README.md`** —— 目录结构与本地跑法。
4. **`ARCHITECTURE.md`** —— 东西是怎么连起来的：模块边界、三条核心数据流、跨切面机制、关键文件索引。
5. **地图 [#52](https://github.com/QIUZI-HONG/pet-health/issues/52)** —— 哪些决策已定、哪些还没定、下一步做什么。

## 当前阶段

**主体功能已经落地，剩下的是几处有明确前置的缺口（见下）。** 服务供给、交易、增长三条链路（含券池 / 邀请 / 积分 / 权益 / 考核）与 AI 咨询（红线短路 + 三层检索 + 护栏）都有跑在真实 MySQL/Redis 上的接口测试。

三端的功能面已经铺满：**C 端 24 条路由、服务者后台 11 个模块、运营后台 11 个模块**，两个后台的登录入口也已补上。

动手前先看**逐项完成状态**：`docs/testing/acceptance-2026-09-30-round5.md`（第五轮「增量落地轮」，**取代第四轮**），以及本轮缺陷清单 `docs/testing/defect-remediation-plan-2026-09-30.md`（41 条 D-xx，按 Wave 0–4 排好序，对应 issue [#127](https://github.com/QIUZI-HONG/pet-health/issues/127)）；目录与跑法见 `README.md`，架构见 `ARCHITECTURE.md`。

**还没做的**（别当成已就绪；改完一条就删一条）：

- **首批增长配置的数值要运营确认**：券面额与邀请阶梯照交付文档取，其余是暂定值（见 `V39` 与第五轮报告 §7.3）
- **打卡得券的规则只有种子、评价是「第一版」**：连续 7 天发券的规则改起来要动库（运营页留给下一版）；评价只做评分 + 一句话（无图片/追评/回复/审核，列表需登录）
- **区域保护的 `provider.region_code` 已可写可筛选，但排他规则未定**（谁在哪个区独占、多久、冲突怎么判）；**提醒规则/照护阈值**的契约里没有 admin 路径——两处都属于「缺业务输入或先定契约」
- **组合套餐、成本测算、营销中心的物料/活动未做**：口径已定（ADR-0034 §5 / ADR-0042 §2），是三个独立切片（计划 D-27 / D-28 / D-29）
- **支付链路按 ADR-0002 是到店付**（平台不经手资金），实现待甲方澄清
- **两处架构欠账**：跨模块端口有「两个家」（`ph-api` 与各域 `api/` 包各占一半）；条件允许时再统一，见 `ARCHITECTURE.md` §4.4

## 硬约束

- 领域命名遵循 `CONTEXT.md`；代码标识符用 `provider`
- 模块间只能走接口或领域事件，**禁止 join 其它模块的表**（ADR-0006；**唯一例外**见 ADR-0009——AI 服务只读 `knowledge_*`）
- 接口先写 `contract/` 的 YAML。**前端不手写接口类型**（由契约生成）；**后端 DTO 手写，但要与契约对齐**
- 金额用 `decimal(10,2)`（与交付文档的 DDL 示例一致），**禁止浮点**
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
