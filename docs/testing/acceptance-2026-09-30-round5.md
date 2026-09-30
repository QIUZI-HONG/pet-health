# 增量轮：一期验收标准的四条缺口（2026-09-30 · 第五轮）

> 本文档是**在既有仓库上做的增量落地轮**，不推翻重做：在现有的 `apps/` + `server/` + `contract/`
> 结构上补一期验收标准里明确缺的那几块，并逐项给出完成度、未做项的原因与替代方案。
>
> **起点**：[`acceptance-2026-09-30-round4.md`](acceptance-2026-09-30-round4.md)（当前有效的功能清单）。
> 本轮不动那份清单里已完成的部分，只关闭它第六节列出的几处缺口。
>
> ⚠️ **仓库里另有一批未提交改动**（资质材料图 `V42`：`ph-file` / `ph-provider` / `QualificationPanel`
> 等 23 个文件）——那是上一轮进行中的工作，**本轮没有回退它们**，只在其之上叠加。
> 本报告第三节列的「改动文件」里，带 ⊙ 标记的是**本轮新改的**，其余是那批在途改动。

---

## 一、结论摘要（对一期验收标准的完成度）

| 验收标准 | 完成度 | 说明 |
| --- | --- | --- |
| 一.1 服务者联盟分类：分类维度维护与商家归属 | ✅ **完成** | 新表 `provider_alliance_category`（V43）+ 运营侧维度 CRUD/启停 + 归属指派 + 服务者侧只读展示 |
| 一.2 标准服务目录与定价区间 | ✅ **本就完成**（未改动） | `ph-catalog`：目录 CRUD、区间校验（`CatalogQueryService.requirePriceInRange`，错误码 90001） |
| 一.3 券池管理：创建/发放/核销/库存/状态流转 | ✅ **本就完成**（未改动） | `ph-privilege`：额度公式收在 `CouponQuota`，三层并发控制 + `(source, source_ref)` 幂等 |
| 一.4 邀请任务：任务配置、进度跟踪、奖励发放 | ✅ **完成**（本轮补齐了「服务者侧入口」） | 用户侧本就完整；本轮补门店推广码（V44），闭合交付文档 F022 的「店内二维码」链路 |
| 一.5 服务者考核：指标、评分计算、结果应用 | ✅ **完成**（本轮接线） | 拉新与券两项**从「未接线」变为真实取数**；`recommend_priority` 写回并真正影响排序 |
| 二 前端五页对齐视觉稿 | 🟡 **部分**（本轮只收掉可查证的配色差异） | 见 §4.1：布局层面的偏离是 ADR-0016 的既定决策，不是本轮能改的 |
| 三 知识库建设方案（设计 + 基础实现） | ✅ **完成**（方案文档 + 已实现的基础） | 新增 [`docs/design/knowledge-base.md`](../design/knowledge-base.md) |
| 四 补全专项 | 🟡 **部分** | 流量平衡 ✅；三道照片墙 ✅（本就完成）；社区与积分 ✅（本就完成）；组合套餐 ❌；成本测算 ❌；营销中心主要缺口 ✅（推广码已通，物料模板/活动未做）；结算中心按 ADR-0002 退化为对账视图（既定） |
| 五 范围裁剪 | ✅ **遵守** | 未开发技师端小程序；图片沿用「先存电脑再上传」（`presign` + 直传，ADR-0020） |

**一句话**：一期验收标准的**「一」整条已经是绿的**；「四」里最难的两块（组合套餐、成本测算模型）
本轮没做，原因与替代方案写在 §4。

---

## 二、落地记录（每条：改了什么 / 为什么这么放）

### 2.1 联盟分类从「写死的三档」变成可维护的值域（V43）

**改了什么**：新表 `provider_alliance_category`（id 即 `provider.category` 的取值，种子 1/2/3 = 直接同业/
直接异业/间接异业）+ 运营侧四个端点：

- `GET|POST /api/v1/admin/alliance-categories`
- `PUT /api/v1/admin/alliance-categories/{category_id}`（改名/说明/排序，**编码不可改**）
- `PUT /api/v1/admin/alliance-categories/{category_id}/status`（启停）
- `PUT /api/v1/admin/providers/{provider_id}/alliance`（指派归属）

**为什么这么放**：

1. **值域进表、校验从注解挪到查表**：原本 `OnboardingApplicationRequest.category` 上是
   `@Min(1) @Max(3)`——运营想加一档就得改代码发版。现在服务层查表校验「存在且启用中」，
   新增一档立刻可指派（`AllianceCategoryService.requireEnabled`）。
2. **没有删除接口**：分类被 `provider.category` 引用，删掉会让历史归属悬空；停用是唯一的收敛手段，
   且**停用不移动既有归属**（已归属的门店照旧显示维度名）。
3. **归属变更进审核流水**（`provider_review_log`，`action = 9`）：它进考核与流量分配的输入，
   属于「事后要能查是谁改的」那一类动作。
4. **服务者不能自选**：交付文档 2.2 把经营主体分类划在平台侧。所以服务者后台的入驻表单里
   那个三选下拉被换成只读展示（`OnboardingPanel`），`ProfilePanel` 显示服务端给的 `category_name`。

### 2.2 考核的拉新与券两项接线（V44 + `ProviderGrowthFactsService`）

**改了什么**：

- 新表 `provider_invite_code`（**一店一码**，`PV` + 8 位）；`invite_relation` 加 `inviter_provider_id`
  并把 `inviter_user_id` 放开为可空，两者**互斥**（CHECK 约束）；
- 服务者侧 `GET|POST /api/v1/provider/invite-code`（码 + 有效/观察中/无效三个计数）；
- 用户归因路径扩展：`InviteService.attribute` 在用户码查不到时按门店码归因；
- `ProviderGrowthFactsService implements ProviderGrowthFactsApi`（四个方法，真实取数）；
- `PortWiringTest.PENDING_WIRING` 清空——**最后一处声明的未接线端口已清偿**。

**为什么这么放**：

1. **码与关系放在一起（ph-privilege）**：码的唯一用途是产生邀请关系，而那是 ph-privilege 的表。
   放 ph-provider 会让归因那一刻跨模块插入别人的表（ADR-0006 禁止）。
2. **门店归因不落到管理员账号上**：让 `inviter_user_id` 也填上（比如门店管理员）会把
   「门店拉新」算成某个人的个人战绩，并顺带把用户的 1/3/5/10/15 阶梯一起推进——两件事混起来后
   两边的数都不可信。代价是 `inviter_user_id` 要放开为可空，用 CHECK 把「两者恰好有一个」钉住。
3. **拉新的 `null` 与 `0` 是两种事实**（接口注释里的原口径）：**没有推广码** = 平台还没给这家店
   拉新入口 → 该维度**不参与**；**有码但一条有效邀请都没有** = 该做而没做 → **记 0 分**。
   接线之后这两条在明细里终于能区分开了。
4. **券的完成率是累计口径**，与 `CouponQuota` 同一处算法：账期的影响由同一个考核项里的
   「本账期核销数」承担（一个累计、一个当期，相乘才是 ADR-0039 第三节那条口径）。
5. **已知宽松**：门店码背后没有主人账号，所以用户码那三条反作弊判据（自邀自/同设备/同 IP 同号段）
   用不上，门店侧只靠 24 小时观察窗与「完成建档 + 有行为」。这条写进了服务类的注释与
   「物料与活动的边界」卡片里，不藏着。

### 2.3 流量平衡：推荐优先级真正影响排序，区域编码可维护（V45）

**改了什么**：

- `provider.recommend_priority`（非空，默认 3），由考核写回（`AssessmentService.writeBackToProvider`）；
- C 端找店与按项目找店的排序改读 `recommend_priority`（1 最高）→ `rating` → `id`；
- `PUT /api/v1/admin/providers/{provider_id}/region`（区域编码可写、可清空，格式受限，进审核流水 `action = 10`）；
- C 端找店支持 `region_code` 筛选。

**为什么这么放**：

1. **改的是「哪一列决定排序」，不是加一个加权公式**：改造前排序读 `provider.level`，
   而档位映射（等级 → 优先级）在 `assessment_level_rule` 里、运营可改——**改了不影响排序**，
   所以交付文档 2.3 的「等级决定 AI 推荐优先级」实际是断的。现在写回的是档位表的**快照**，
   运营改映射当场生效（`CatalogBrowseTest.recommendPriorityDecidesOrder` 同时钉住
   「只改 level 不影响排序」这条反向断言）。
2. **非空默认 3 而不是 NULL**：MySQL 升序里 NULL 排最前，新店会被顶到最前面——
   「没有数据」不该长得像「数据很好」。默认 3 与 `level` 默认 1（基础档）同档。
3. **区域只做到「可维护 + 可筛选」，不假装已保护**：排他性规则（谁在哪个区独占、独占多久、
   冲突怎么判）仍未定。契约、迁移注释、页面文案三处都写了这句话。

### 2.4 知识库建设方案（文档交付物）

新增 [`docs/design/knowledge-base.md`](../design/knowledge-base.md)：三层架构与放层判据、
八大类 → 十项的处理（交付文档自身不一致）、条目的必填标注字段、三条检索路径与「为什么不做向量」、
知识图谱的表结构与三种用法、**五道风险分级闸门**（硬红线短路 → 模型 → 分级规则抬档 → 降级档位 → 输出护栏）、
入库管线与运营闭环，以及七条当前缺口。

### 2.5 一处视觉稿对齐（可查证的那一条）

**改了什么**：`HealthScoreCard` 的五维进度条配色改为 **≥90 绿 / ≥80 黄 / 其他暖橙**，
圆环在「需关注」档也用暖橙而不是警示红（`apps/c-web/src/components/HealthScoreCard.vue`）。

**为什么**：视觉稿 4.16.1 的开发者对照要点逐字写了这个档位，4.16.10 的清单又写明
「红色仅用于高风险提醒与异常状态」——原实现的 85/70 阈值 + 低分用红同时违反这两条，
而且与 ADR-0018「低分多半是记录不连续」的意图相反。测试钉住（`checkin-score.spec.ts`）。

---

## 三、改动文件清单

### 后端（新增）

| 文件 | 作用 |
| --- | --- |
| `db/migration/V43__provider_alliance_category.sql` + `undo/U43` | 联盟分类维度表 + 种子 + 审核流水 action 注释 |
| `db/migration/V44__provider_invite_code.sql` + `undo/U44` | 门店推广码 + `invite_relation` 门店归因列与 CHECK |
| `db/migration/V45__provider_recommend_priority_and_region.sql` + `undo/U45` | 推荐优先级列 + 回填 |
| `ph-api/.../provider/AllianceCategoryView|Request|StatusRequest.java` | 维度契约形状 |
| `ph-api/.../provider/ProviderAllianceRequest.java` | 归属指派请求 |
| `ph-api/.../provider/ProviderRegionRequest.java` | 区域编码请求 |
| `ph-api/.../provider/ProviderInviteCodeView.java` | 推广码与三个计数 |
| `ph-provider/.../domain/AllianceCategory.java`、`AllianceCategoryStat.java` | 维度实体与统计 |
| `ph-provider/.../mapper/AllianceCategoryMapper.java` | 维度访问（含归属数聚合） |
| `ph-provider/.../service/AllianceCategoryService.java` | 维度维护 + 归属指派 + 名称解析 |
| `ph-provider/.../web/AdminAllianceCategoryController.java` | 维度四个端点 |
| `ph-privilege/.../domain/ProviderInviteCode.java`、`mapper/ProviderInviteCodeMapper.java` | 推广码 |
| `ph-privilege/.../service/ProviderInviteCodeService.java` | 取码（幂等）+ 战况统计 |
| `ph-privilege/.../service/ProviderGrowthFactsService.java` | **`ProviderGrowthFactsApi` 的生产实现** |
| `ph-privilege/.../web/ProviderInviteController.java` | 服务者侧两个端点 |

### 后端（修改）

`ProviderProfileView`（加 `categoryName` / `recommendPriority`）、`OnboardingApplicationRequest`（放开 category 范围）、
`OnboardingApplicationRequest`/`ProviderProfileView` ⊙、`Provider`（`recommendPriority` 字段）、
`ProviderReviewLog`（`action = 9 / 10`）、`ProviderViews`、`ProviderAdminService`、`ProviderProfileService`、
`ProviderBrowseService`、`OnboardingService`、`AdminProviderReviewController`、`AppProviderController`、
`InviteRelation`（门店归因列）、`InviteService`（门店归因分支 + 有效结算分支）。

> 注：`ProviderQualification*`、`FileService`、`ProviderFileController`、`QualificationGuard`、`ph-provider/pom.xml`
> 等 ⊙ 文件属于**在途的 V42 改动**，本轮未动。

### 契约与生成物

`contract/admin.yaml`（联盟分类四个端点 + 区域端点 + 三个 schema + `recommend_priority`/`category_name`）、
`contract/provider.yaml`（推广码两个端点 + `ProviderInviteCodeView` + `region_code` 口径改写 + 分类描述）、
`contract/app.yaml`（`region_code` 筛选参数 + 排序口径改写）；
`packages/shared/src/api/{admin,provider,app}.d.ts` **由契约重新生成**（未手写）。

### 前端

| 文件 | 改动 |
| --- | --- |
| `apps/admin-web/src/components/AllianceCategoryPanel.vue` | 新增：维度列表/新建/编辑/启停 |
| `apps/admin-web/src/views/ProviderAuditView.vue` | 加「联盟分类维度」分段 |
| `apps/admin-web/src/components/ProviderListPanel.vue` | 详情里加归属指派与区域编码两段 + 列表加分类列 |
| `apps/admin-web/src/api/adminApi.ts` | 五个新方法 + 三个类型别名 |
| `apps/provider-web/src/views/MarketingView.vue` | 从「缺接口」改成真实推广码卡（生成 + 三个计数） |
| `apps/provider-web/src/api/providerApi.ts` | `getInviteCode` / `ensureInviteCode` |
| `apps/provider-web/src/components/OnboardingPanel.vue` | 经营类别下拉 → 只读展示（平台归类） |
| `apps/provider-web/src/components/ProfilePanel.vue`、`utils/labels.ts` | 分类名改读服务端 `category_name` |
| `apps/c-web/src/components/HealthScoreCard.vue` | 五维进度条档位配色对齐视觉稿 |

### 测试（新增 4 个 + 改 9 个）

新增：`AllianceCategoryTest`（6 例）、`ProviderTrafficBalanceTest`（2 例）、`AssessmentGrowthFactsTest`（2 例）、
`apps/admin-web/src/__tests__/alliance-category.spec.ts`（6 例）；
`AssessmentGrowthUnwiredTest` **改名为** `AssessmentGrowthWiringTest`（未接线 → 已接线的新口径）；
`AssessmentStubSupport`（stub 加 `@Primary`）、`AssessmentTestSupport`/`PrivilegeTestSupport`/`ProviderApiTestSupport`
（清理新增的表与依赖）、`CatalogBrowseTest`（排序口径）、`AssessmentScoringTest`（写回断言）、
`PortWiringTest`（例外清空）、`marketing.spec.ts`（重写）、`checkin-score.spec.ts`（配色断言）。

---

## 四、未做项与原因、替代方案

### 4.1 前端五页「逐页还原视觉稿」——只做了配色层（本轮）

**为什么没全做**：视觉稿是**手机竖屏（420×836，底部 5 Tab）**，而本仓库按 ADR-0016 是**桌面 Web
（左栏 208px 导航）**。两者的差异不是「样式没调」，是信息架构不同：

| 视觉稿 | 仓库现状 | 差异性质 |
| --- | --- | --- |
| 底部 5 Tab 导航 | 左侧栏 + 子路由 | **ADR-0016 的既定偏离**（三端都是 Web） |
| 「我的」页内含福利卡（橙）+ 积分卡（紫） | 券、积分、权益、邀请各自独立成页（`/coupons` `/points` `/rights` `/invites`） | 桌面面积够，不需要挤在一页 |
| 「服务」页分类标签三列流式 | 分类筛选 + 「按项目找服务」独立页 | 交互模型不同（桌面用导航而不是标签墙） |
| 健康评分卡圆环 + 五维进度条 | **已实现**（`HealthScoreCard`） | ✅ 本轮补齐配色档位 |
| AI 回复三段层级（风险/原因/行动） | **已实现**（`AiConsultView` 的 `ph-answer__*`） | ✅ 结构已对齐 |
| 档案五维进度条 + 未开启项灰色 | **已实现** | ✅ |

**替代方案**：要真正「逐页还原」，需要甲方先确认「C 端是否要改成手机版式」——
那是一次产品级改版（ADR-0016 会被推翻），不是增量。建议单开一票，并把五张 PNG 与
`docs/assets/mockups/README.md` 的取色记录一起作为验收基线。

### 4.2 组合套餐（骨架未做）

**现状**：`package_template` + `provider_package` **没有实现**；两份 ADR（0034 §5、0042 §2）已经把
「平台定骨架 + 服务者组包定价 + 订单里展开成多个订单项」的口径定下来了。

**为什么本轮没做**：它要动**三条链路**——目录侧（套餐模板挂在标准目录上）、服务者侧（组包与定价）、
订单侧（`order_item` 展开、价格校验按「各单项定价之和 ÷ 套餐价」的规则）。三条都要改契约与测试，
是**一个独立的切片**（约等于本轮全部工作量的一半），塞进本轮会做成半成品。

**替代方案（按代价从低到高）**：
1. **先用「备注 + 多选服务项」过渡**：下单时选多个标准项目，`order_item` 本来就是多行——
   已经支持多行，只是没有「打包价」。运营可用「券」承担折扣（券已经有面额与门槛）。
2. 若甲方要的是**打包价**而不是打包结构：给 `order` 加一个 `package_discount` 字段，
   在现有 `order_item` 之上做一次性折扣，不动目录与服务者的结构。
3. 完整的套餐模板（推荐，但要一个切片）：`package_template`（平台）+ `provider_package`（服务者）+ 下单展开。

### 4.3 成本测算模型（未做）

**现状**：只有 AI 日预算告警（`AiBudgetMonitor`，单价是占位值）与券的成本归属（`cost_bearer`）。
成体系的成本分析只在 [`docs/research/61-multimodal-model-selection.md`](../research/61-multimodal-model-selection.md) §6。

**为什么本轮没做**：成本模型的**输入全是运营假设**（单次 token 量、免费额度、DAU、券核销率、
补贴比例），而单价的真值要等甲方给出模型选型与商务价。做一张表存假设值，只会让「测算结果」
看起来比它实际的可信度高——那比不做更危险。

**替代方案**：
1. **先接真实用量，后建模**：`ai_consult` 已经有 `prompt_tokens` / `completion_tokens` 留痕，
   加一个运营页按「账期 × 模型」聚合真实用量（这是纯读，**不需要任何假设**），
   单价与补贴比例作为参数在页面上可填可改。这一步可随时做，且做完就能用。
2. 券侧的成本已经有账面数据（`cost_bearer` + 核销数），同一页里与 AI 用量并列即可。

### 4.4 其余零散未做

| 项 | 原因 | 替代方案 |
| --- | --- | --- |
| 营销中心的物料模板 / 活动管理 | 需要素材库与活动表两张新表，且「活动」的形态没定 | 推广码已通（本轮），物料可以先靠门店自制；活动等甲方给形态 |
| 结算中心 | 平台不经手资金（ADR-0002 / 0036）→ 退化为对账视图 | 既定；待甲方澄清支付链 |
| 被邀请人「双方各得券」 | 需要一条给被邀请人发券的路径（现在是积分） | 已在 `V39` 里用「被邀请人 20 分」对等；要改成券是**一次配置 + 一条发券调用**，可单开小票 |
| 技师端小程序 | 一期验收标准已裁剪（并入 PC 端后台） | 服务标准页（`/b/standard`）已承载接车/拍照/确认口径 |
| 二维码图片 | 契约只给码本身，没有生成图片的接口 | 门店拿码自行生成二维码；要内置则加一个 `qrcode` 渲染（前端纯函数即可） |

---

## 五、验证（跑了什么、结果）

```bash
# 后端：Testcontainers 起真实 MySQL 8.4 + Redis 8（需要 Docker）
cd server && ./mvnw -B verify

# 前端：三端各自类型检查 + 单测（vitest）
cd apps/c-web        && npx vue-tsc -b && npx vitest run
cd apps/provider-web && npx vue-tsc -b && npx vitest run
cd apps/admin-web    && npx vue-tsc -b && npx vitest run
cd packages/shared   && npx vitest run

# 契约与生成物一致性
pnpm --filter @pet-health/shared gen:api:check
```

**结果**（本轮最后一次全量）：

| 套件 | 规模 | 结果 |
| --- | --- | --- |
| 后端 `verify`（13 个模块） | **435 例** | ✅ `BUILD SUCCESS`（0 失败） |
| `apps/c-web` | 298 例 | ✅ 全绿 |
| `apps/provider-web` | 99 例 | ✅ 全绿 |
| `apps/admin-web` | 87 例 | ✅ 全绿 |
| `packages/shared` | 16 例 | ✅ 全绿 |
| 契约生成物 | 5 份 | ✅ 与契约一致（`gen:api:check`） |

> 后端跑的是 **Testcontainers 起的真实 MySQL 8.4 + Redis 8**（ADR-0014），迁移 `V1–V45` 全部执行过一遍。

**本轮跑红过的四次**（都记下来，因为每次都是真问题）：

1. **术语守卫** `TerminologyGuardTest`：我在注释里写了「商家联盟分类」（借用一期验收标准的原文），
   而本仓库的硬约束是**代码目录禁用词零命中**（`商家`/`merchant` 一律说服务者）。**改的是我的注释**，
   不是守卫——并且这是唯一一处「引用需求原文」与「术语纪律」冲突的地方，处理方式是改写为
   「服务者联盟分类」并保留对验收条的语义引用。
2. **券完成率的口径**：`AssessmentGrowthFactsTest` 期望 0.40、实际 0.50——完成率是**累计**口径
   （上月核销的那张也算进分子），只有「本账期核销数」是按账期取的。**测试写错了**，代码是对的；
   顺带把这条差异在测试与实现的注释里都写清楚。
3. **排序口径变更的涟漪**：`CatalogBrowseTest.levelDecidesRecommendationPriority` 原本靠
   「直接改 `level`」来验排序，接线之后**只改 level 不再影响排序**（这正是要修的东西）。
   改成同时钉住两条：改 `level` 不动、改 `recommend_priority` 才动。
4. **跨切片的测试污染**：`AssessmentGrowthWiringTest` 期望「券池里没有可贡献的券」，却被别的切片
   留下的券模板判成「有」——`AssessmentTestSupport` 的清理范围不够。改成与 `PrivilegeTestSupport`
   同一口径（清**全部**模板）。这类假绿/假红只有跑真库才会暴露（ADR-0014 的价值）。

---

## 六、本轮自己拍的判断（都可以推翻，但要知道推的是哪一条）

1. **门店推广码的码形取 `PV` + 8 位**（与用户邀请码的 8 位**不同长**，所以两张表不会互撞）。
   代价：码是 10 位，比用户码长两位。
2. **`provider.category` 继续存数值、值域交给新表**，而不是把列改成 `varchar` 编码。
   理由：不动既有列与历史数据；代价是「值 → 名称」每次要查一次维度表
   （列表页已做批量解析，没有 N+1）。
3. **门店归因不吃用户阶梯**：门店码拉来的人不计入任何用户的 1/3/5/10/15 阶梯，
   只计门店的拉新。理由是两者是不同的账；代价是「用户既是门店管理员、又想拿个人邀请奖」
   这两个动作要各做一次。
4. **`recommend_priority` 非空默认 3**（不用 NULL 表达「还没算过考核」）。
5. **区域只做到「可维护 + 可筛选」**，不实现排他保护——规则未定，不编。

---

## 七、本地启动与冒烟验证步骤

### 7.1 起服务

```bash
# 1) 中间件（MySQL 3307 + Redis 8）
docker compose -f deploy/docker-compose.dev.yml up -d

# 2) 后端（JDK 17；迁移 V1–V45 会自动跑）
cd server && ./mvnw -pl ph-boot -am spring-boot:run          # :8080

# 3) 三端（另开终端）
pnpm install
pnpm --filter c-web dev          # :5173   C 端
pnpm --filter provider-web dev   # :5174   服务者后台
pnpm --filter admin-web dev      # :5175   运营后台
```

### 7.2 冒烟路径（按本轮改动逐条走）

| # | 入口 | 操作 | 期望 |
| --- | --- | --- | --- |
| 1 | 服务者后台 `/b/login` → `/b/onboarding` | 提交入驻申请（联盟分类显示为只读「由平台审核时归类」） | 提交成功，进审核队列 |
| 2 | 运营后台 `/admin/providers` → 「联盟分类维度」 | 新建一档「测试连锁集团」→ 停用一档种子 | 列表出现新档；停用后 `provider_count` 仍显示挂着的门店数 |
| 3 | 同页「服务者列表与状态」 | 打开刚入驻那家店的详情 → 选新档 → 保存归属 → 填 `SH-XH` → 保存区域 | 两处都成功；「联盟分类」那一行显示新档名；重复保存同一值 → 40900 提示 |
| 4 | 服务者后台 `/b/marketing` | 「生成推广码」→ 记下 `PVxxxxxxxx` | 显示码与三个计数（有效 0 / 观察中 0 / 无效 0） |
| 5 | C 端 `/login` | 用推广码注册一个新账号 → 建档 → 打卡一次 | 注册成功 |
| 6 | 服务者后台 `/b/marketing` | 刷新（等 24 小时观察窗或直接看明细口径） | 计数出现变化；「本店拉新」那一项的说明从「还没有拉新入口」变成有入口的口径 |
| 7 | 运营后台 `/admin/assess-rules` | 给「拉新」「券」配上达标线（种子是 0，即「平台还没定要求」） | 保存成功 |
| 8 | 运营后台 `/admin/providers` | 触发一次考核（或等每月 1 日 04:00 的批算），再看服务者考核明细 | 拉新项 `participated=true` 或写明缺的条件（无券可出 / 未配达标线） |
| 9 | C 端 `/services` | 列表排序 | 优先级高的店在前；带 `region_code=SH-XH` 请求只返回该片区门店 |
| 10 | C 端 `/` | 首页健康评分卡 | 五维进度条：≥90 绿、≥80 黄、其他暖橙（低分不是红色） |

### 7.3 演示前需要人工准备的数据

- **运营要建一张「服务者成本券」**（`cost_bearer = 1` 且启用），否则考核的券项永远「不参与」——
  种子里的两张（CP-101 / CP-102）都是平台补贴券；
- **运营要配拉新与券的达标线**（`invite_target` / `coupon_target` 种子为 0 = 未定要求）；
- **知识条目的兽医复核**（40 条全 `pending_review`）——AI 引用为空的原因在这里，
  不在代码（见知识库方案 §7）。
