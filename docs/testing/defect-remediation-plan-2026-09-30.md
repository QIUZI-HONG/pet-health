# 缺陷修复计划（2026-09-30）

> **来源**：本计划的每条缺陷都出自 [`change-assessment-2026-09-30.md`](change-assessment-2026-09-30.md)（新版方案变更评估），
> 并经过一次独立复核——复核否掉的三条误判记在那份报告 §12.3，**不在本计划里**。
> **性质**：只读扫描产出，**未改动任何源文件**。本文件是本次新增的第二个文件。
> **编号**：`D-xx` 修复项，`E-xx` 挂起项（需外部输入，不计工时）。

## 〇、进度（2026-09-30 本轮）

| 状态 | 条目 |
| --- | --- |
| ✅ **已完成** | **Wave 0 全部**（D-02 提交收口、D-03 补写 ADR-0053、D-32 文档指针、D-33 忽略 `.zcode/`）；**Wave 1 全部**（D-01 考核写回、D-04 资质图归属、**D-05 被邀请人得券**、**D-06 券自动选优**、D-07 两条列表排序同口径、D-15 两处假绿测试改真）；顺手做掉 D-09/D-10；**D-41 是新发现的 P0，已修** |
| ✅ **D-12 知识条目复核闭环**（2026-10-01） | 运营后台第六段「知识条目」：列表 + 复核通过 / 打回。复核必须填**复核人与资质**（落 `reviewed_by` / `reviewed_credential` / `reviewed_at`），且是**唯一**能改 `review_status` 的入口；补 **ADR-0054** 把 ADR-0040 的「复核流程待澄清」填上（该条禁的是自动置位，不是禁止记录人工复核） |
| ✅ **D-28 AI 用量（成本测算的只读那一半）**（2026-10-01） | `GET /admin/ai/usage?period=yyyy-MM`：按「模型 × 版本」聚合调用数 / 输入输出 token / 红线短路 / 降级 + 合计行；运营数据看板加一格，**单价与补贴比例由页面参数化**（不建假设表——ADR-0050 第五节）。金额仍缺，等甲方商务价 |
| ✅ **D-31 演示数据种子**（2026-10-01） | `server/scripts/demo-seed.sh`（幂等）：C 端演示账号 / 宠物 / 打卡走**接口**；运营侧配置（成本券模板 + 考核达标线 + 知识条目复核 + 一张演示券）走 SQL——脚本里没有运营令牌（独立登录域 + 白名单），理由写在脚本头部。已实跑两遍验证 |
| ✅ **D-34 / D-35**（2026-10-01） | V47 把审核流水的 `action` 注释补到 10（那份注释是这一列唯一的说明书）；两个服务层方法按 `conventions.md` 的命名约定改名（`listCategories` / `viewInviteCode`） |
| ⬜ 未动 | D-08 / D-11 / D-13 / D-14、D-16 ~ D-26（Wave 2）、D-27 / D-29 / D-30（Wave 3）、D-36 ~ D-40（Wave 4） |

**外部输入的裁决记录（2026-10-01，项目所有者）**：

| 项 | 裁决 | 后果 |
| --- | --- | --- |
| **E-04 版式** | **保持桌面 Web** | Wave 2 按 ADR-0016 推进：补齐五页区块 + 设计语言一致；验收口径是「区块齐 + 色值/圆角/间距/组件形态一致」，**不承诺**与手机稿逐像素一致 |
| **E-06 套餐/活动形态** | 未定（Wave 3 的 D-27 / D-29 仍挂起） | 先做 D-28 / D-30 / D-31 三条不依赖形态的 |
| **D-43 邀请人口径** | **维持现状 + 改文档** | 阶梯奖就是邀请人的奖励节奏；「第 2、4、6 次邀请只有被邀请人得券」写进文档与验收说明，不动代码 |

**下一批的顺序（项目所有者定的「按收益排序」）**：D-12 → D-28 → D-31 → D-36/37 → D-40。

**本轮验证**：`./mvnw -B verify` **441 例 0 失败**；三端与 shared 单测 301 / 99 / 87 / 16 全绿；
`gen:api:check` 与契约一致。
**实现过程中被守卫逮到的两处**（都修了）：① 契约里 `CouponView` 在 app / provider / admin **各声明一份**，
我只改了 app 那份 → `ContractDtoDriftTest` 报「DTO 有、契约无」；② 我删 C 端那份本地选券函数时
把文件尾部一起截断了（`redeemPlaceText` 被误删，`coupons.spec.ts` 6 例当场红）——两处都是**先红后修**，
没有跳过。
**评审后追加修掉**：ADR-0053 里与新实现自相矛盾的两段、契约里另外三处「只存映射/按等级排序」的陈述、
`OnboardingService` 补交路径把校验提到删除之前（此前依赖事务回滚）、`CatalogBrowseTest`
补上「按项目找店也不读 level」的反向断言。
**实现过程中新发现**：① **D-41**（号源网格午夜边界 OOM，见 §4.1）；② `OrderCreateTest` 的
「时段已经开始」用例**依赖墙上时钟**（写死今天 01:00，于是 00:00–01:00 跑必然假红）——两处都已修。

## 一、汇总

| 分组 | 条数 | 人天 | 说明 |
| --- | --- | --- | --- |
| **P0 阻断**（断链 / 安全 / 口径不符 / 过程收口） | 8 | **8.25** | 不做，F022 与 BPM-2 无法验收 |
| **P1 视觉对齐** | 11 | **10.75** | 在 ADR-0016 桌面口径下做「区块齐 + 设计语言一致」 |
| **P1 后端与口径** | 8 | **5.5** | 排序、风控、契约、ADR 同步、知识库元字段 |
| **P1 未做功能** | 5 | **21.0** | 套餐 / 成本 / 营销 / 看板 / 演示数据 |
| **P2 文档、过程与卫生** | 9 | **6.25** | 含 4 类缺失交付物 |
| **合计** | **41** | **≈ 51.75 人天** | 其中「功能与缺陷修复」45.5，「交付物与工程卫生」6.25 |

> **与评估报告 §5 的差异**：报告给的是约 38 人天的粗粒度估算；本计划展开到条目级后为约 51 人天。
> 差额来自三处此前未单列的项：缺失交付物补全（D-38，3 人天）、前端工程卫生（D-40，1 人天）、
> 重复代码收敛（D-36/D-37，1.5 人天），以及两条新查出的测试缺陷（D-07/D-15，1 人天）。
> **结论不变：三人并行约 3 周，单人串行约 7.5 周。**

**挂起项 8 条**（E-01…E-08）：需要甲方或运营给输入，本计划不排期，只写清「拿到什么就能开工」。

---

## 二、P0 阻断项（8 条 / 8.25 人天）

| ID | 缺陷 | 证据 | 修复方案 | 人天 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| **D-01** | **考核 → `provider.recommend_priority` 的写回没有实现**：排序已改读该列，但该列上线后不会再变，F022「等级决定 AI 推荐优先级」仍断 | `AssessmentService.java:478-493`（只写 level / monthlyScore）；全仓无 `provider.setRecommendPriority` 调用；V45 注释与 round5 §2.3 却称已闭环 | 在 `writeBackToProvider` 内按 `level` 查 `assessment_level_rule` 取 `recommendPriority` 写入；覆盖 override 路径（`:447` 的调用点） | 1.0 | 走真实考核路径（`AssessmentMonthlyJob` 或 `calculate`）后，C 端找店顺序随之改变；反向断言「只改 `level` 不改档位映射 → 顺序不变」 |
| **D-02** | **88 项在途改动未提交**（53 改 + 35 新增），任何人按仓库现状开工都会拿到过期认知 | `git status --porcelain \| wc -l` = 88；`git diff HEAD --shortstat` = 54 files, +2451 −349 | 按「后端 → 契约与生成物 → 前端 → 测试」分 4 个提交收口；提交前跑一次全量 verify | 0.5 | `git status` 干净；每个提交信息符合 `feat/fix/refactor/docs:` |
| **D-03** | **32 处引用不存在的 ADR-0053**（`docs/adr/` 只到 0052） | `grep -rn 0053` 命中 12+ 文件（`V42`、`QualificationGuard`、`FileService`、`ProviderFileController`、两个 DTO、`contract/provider.yaml`、`providerApi.ts`） | 补写 ADR-0053：把「文件读地址走 `FileQueryApi`、免归属校验」这条口径正式定下来（与 ADR-0020 第六条的关系要写清）；或改引用到既有 ADR | 0.25 | `grep -rn 0053` 的每个命中都指向存在的文件 |
| **D-04** | **资质图提交不校验 `file_id` 归属与用途**：只校非空，读走 `FileQueryApi` 不过滤上传者 | `QualificationGuard`；`ProviderFileController`；`QUALIFICATION` 白名单只管 presign | 提交时校验「该 `file_id` 是本人上传 + `biz_type=qualification`」；展示侧只回本人材料 | 0.75 | 用他人 `file_id` 提交被拒（40001/40300）；审核页看不到非本人材料 |
| **D-05** | **被邀请人得积分而非券**（BPM-2 说双方各得 20 元洗护券） | `V38` 只登记行为码、`V39` 发 20 分；`PointsService.award(INVITE_INVITEE…)` | 给被邀请人加一条发券路径：阶梯/配置里补 `reward_type=coupon` + `CouponService#issue` 调用（配置在库，不改代码路径） | 2.25 | 新用户凭码注册 → 建档 → 观察窗内有一次行为 → **结算后双方券包各多一张洗护券**（发放时机与积分同期，不是注册即发——注册即发会让刷号当晚就领到奖励）；重复结算不重复发 |
| **D-06** | **券无「自动匹配最优券」**（BPM-3 说系统自动匹配，实现是用户手选） | `OrderCreateView` 拉券后按 `appliesToProvider` 过滤；服务端 `couponApi.check` 只复核 | 后端加选优算法（门槛满足 → 面额最大 → 最近到期 → 稳定 ID 兜底），下单默认应用；前端保留可改选并显示「已选最优券」 | 2.5 | 下单页默认选中最优券；换选后金额与券记录一致；无可用券时不显示空选优 |
| **D-07** | **按项目找店未改排序**：`providersOfItem` 仍 `orderByDesc(level)`，而 round5 报告称两处都改了 | `ProviderBrowseService.java:156`（方法）、`:169-171`（排序块用 level）；`:136-138` 才是 `recommend_priority` | 把 `:169-171` 改成与 `browse` 同口径（`recommend_priority` → `rating` → `id`） | 0.5 | `CatalogBrowseTest` 改用例断言「改 `level` 不动、改 `recommend_priority` 才动」，且**不预先修改 `top.level`** |
| **D-15** | **两处测试假绿**：`AssessmentScoringTest` 断言的 `3` 恰等于默认值/等级 1 的映射值；`CatalogBrowseTest` 靠先改 `level` 保持绿 | 见 D-01 / D-07 的测试文件 | 两处都改成走生产路径的断言；D-07 的用例去掉预置 `level` | 0.5 | 把 D-01 的写回代码注释掉，D-15 的两个用例必须变红（反向验证） |

---

## 三、P1 视觉对齐（11 条 / 10.75 人天）

> **前置**：这一批按 ADR-0016 的**桌面口径**做——目标是「区块齐 + 色值/圆角/间距/组件形态一致」，
> **不追求与手机稿逐像素一致**。若 E-04 的结论是「C 端改手机版式」，本批全部作废重估（见第五节）。

| ID | 缺陷（稿子有的区块，实现缺） | 证据（稿 → 实现） | 修复方案 | 人天 |
| --- | --- | --- | --- | --- |
| **D-16** | 首页缺 4 处：①提醒卡的**彩色分类 pill** ②每条提醒的**第二个动作 chip** ③券提醒条 ④邀请入口条 | `HomeView.vue:272-303`；契约 `MessageView.type` 已给全 10 类（`contract/app.yaml:3242`） | ①补分类 pill（10 类映射色）②第二动作需扩契约 `action_hint_2` ③④用 `primary-light` / 暖橙 token 各加一条 | 2.0 |
| **D-17** | 服务页缺：①「AI 帮我找服务」浅绿 banner 卡（现为一行文字链）②「分类 → 三列 chip 流」形态 | `ServicesView.vue:126-131`；`CatalogView.vue:129-142` | ①banner 卡（token 现成）②`CatalogView` 分类区改 chip 网格，点击下钻 | 1.5 |
| **D-18** | AI 页缺：①问候卡（「今天状态不错 / N 项需要关注」）②四个症状 chip（现只在 `/service-finder`） | `AiConsultView.vue`；`ServiceFinderView.vue:83-93` 有现成 chip 与词表 | ①问候卡接首页提醒计数 ②把 chip 组件抽到 `packages/ui` 或 C 端共享组件 | 1.0 |
| **D-19** | 档案页缺：①**健康评分详情**块（稿子的主块）②左右栏严重失衡（左栏底部约 2/3 空白） | `RecordsView.vue:448-470`；`HealthScoreCard.vue` 组件已存在 | ①档案页首块挂 `HealthScoreCard` ②把「宠物信息」移出右栏或改一栏流式 | 1.5 |
| **D-20** | 我的页缺：①**我的订单**（进行中/已完成两组）②**我的福利**（券 + 快过期提示） | `ProfileView.vue`；数据接口已有（`/orders`、`/coupons`） | 各加一个区块，复用 `CouponCard`；订单取最近 2 条 + 「查看全部」 | 1.5 |
| **D-21** | 服务者后台缺：①绿地「今日概览」汇总带（6 个数字）②**AI 推荐优先级**一行 | `DashboardView.vue:152/221/273`（三张白卡）；`utils/labels.ts:389-393` 文案函数已在 | ①合并成一条概览带 ②补优先级一行（字段已有，缺的是 D-01 的写回） | 1.0 |
| **D-22** | 四态用 **emoji** 当图标（⚠️/🐾/🔒），规范要的是 52px 圆形底 + 红/橙/灰语义 | `ConsoleState.vue:38-43`；`StateEmpty.vue:12`（自注「后续统一换 SVG」） | 换 SVG 图标组件，四态 + 后台 `variant` 统一 | 0.5 |
| **D-23** | **420px 下页面挤坏**：左栏 208px 不折叠，内容区窄到逐字换行、快捷服务格溢出 | 截图 `P003-首页-phone.png`、`P007-我的-phone.png` | `AppShell` 加 `min-width: 1080px`（窄屏走横向滚动）；**或**立项做移动版式（E-04） | 0.75 |
| **D-24** | 日期控件显示 `09/30/2026`（原生英文格式），违反交付文档「日期展示 YYYY年MM月DD日」的默认决策 | 截图 `P003-首页-豆豆-desktop.png` 的「今日健康任务」日期框 | 统一日期展示/输入形态（展示用中文格式；输入保留原生但加格式化显示） | 0.25 |
| **D-25** | 卡片内边距注释与实现不一致：注释说 16px，代码是 `--ph-space-5` = 20px | `console.css:59-65`（注释引交付文档 4.12） | 二者取一，并同步 `app.css` 的同名类 | 0.25 |
| **D-26** | 首页「快捷服务」入口集合与稿子不同（稿 5 个圆形入口 vs 实现 6 个方块磁贴） | `HomeView.vue:258-267` | 至少补「找洗护 / 找训犬 / 上门喂养」三个服务页分类深链；形态可保留磁贴（桌面） | 0.5 |

---

## 四、P1 后端与口径 + P1 未做功能 + P2（22 条 / 32.75 人天）

### 4.1 后端与口径（8 条 / 5.5 人天）

| ID | 缺陷 | 证据 | 修复方案 | 人天 |
| --- | --- | --- | --- | --- |
| **D-08** | 门店码拉新无风控：门店码归因**明说不跑** `InviteRiskGuard`；`provider_invite_code.status` 无写入口、归因也**不看** status（死开关） | `ProviderInviteCodeService`（服务只碰 `InviteRelation.status`）；`ProviderInviteCode.java:57` 的 `isEnabled()` 零调用 | ①归因加同手机号/IP 段限次 ②把 `status` 接进归因**或**在契约里删掉该字段（二选一，别留死开关） | 1.0 |
| **D-09** | 契约自相矛盾：`provider.yaml` 一处写「C 端找店按它排序」，另一处写「只存映射结果，不改推荐逻辑」 | `contract/provider.yaml:1573` vs `:2173` | 按 D-01 修完后的真实口径统一两处描述，重生成 `.d.ts` | 0.25 |
| **D-10** | ADR-0052 未同步：ADR 写「连字段都不动」「没有改任何推荐逻辑」，代码已经改了 | `docs/adr/0052-*` vs `V45` / `ProviderBrowseService` | 更新 ADR-0052（或补一条新 ADR 说明推荐逻辑已改） | 0.25 |
| **D-11** | 知识条目的元字段（有效期 `effective_from/to`、复核人 `reviewed_by`、`version`）**代码从不读** → 过期条目不会被停用 | `ai/app/*.py` 无任何引用（grep 为空）；V18 里列存在 | 检索与展示侧读有效期并过滤过期条目；`reviewed_by` 进「未复核」判定 | 0.75 |
| **D-12** | 知识条目**复核闭环无入口**：契约只有 prompts/red-flags/grading-rules/guard-terms/switches，没有条目或 `review_status` 写接口 → `vetted` 只能人工改库 | `contract/admin.yaml` 的 `/ai/*` 路径清单 | 加「条目列表 + 复核/退回」两个 admin 接口 + 运营页一栏 | 1.0 |
| **D-13** | 设计文档与实现不符：文档称急救属 L1，但 `_L1_INTENTS` 无急救词（first_aid 永不走 L1）；`knowledge_category.layer_hint` 的取值与文档 §2.2 矛盾 | `ai/app/knowledge.py:56`（`_L1_INTENTS`）、`:248`（`_retrieve_l1`）；`V18:124-133` | 二者取一：补 L1 急救意图，或改设计文档与分类表的 `layer_hint` | 0.5 |
| **D-14** | 提醒规则与照护阈值**没有 admin 契约路径**（运营改不了，只能改库） | `ARCHITECTURE.md §10` 自述；`contract/admin.yaml` 无对应路径 | 先定契约（规则字段形状），再补 2 个端点 + 运营页 | 1.5 |
| **D-15b** | 券池完成率的累计口径与账期口径差异只写在测试注释里，页面未标注 | `AssessmentGrowthFactsTest` 注释；`AssessmentView.vue` 明细 | 页面补一句口径说明（一个累计、一个当期） | 0.25 |
| **D-42** | **奖励发放与结算同事务**：`couponApi.issue` 是默认传播的事务方法（`CouponService.issue` 注解 `@Transactional(READ_COMMITTED)`），抛错会把当前事务标成 rollback-only，`settle` 提交时 `UnexpectedRollbackException` → **整批邀请回滚、每小时重试仍失败**（模板被停用 / 额度用尽即触发）。**两处都有**：`grantLadderRewards` 与本轮新增的 `grantInviteeCoupon`——两处的 catch 都只能让日志说清原因，挡不住回滚 | `InviteService` 两个 grant 方法；仓库对同款坑的既定手法是 AFTER_COMMIT（`CheckInPointsListener`） | 把发奖移到 AFTER_COMMIT 监听器（或加一层 `REQUIRES_NEW`）——**要一次改两处**，否则等于只修一半 | 2.0 |
| **D-43** | **「双方各得」只对首次邀请成立**：邀请人侧的券来自阶梯奖（1/3/5/10/15 档各一张），所以第 2、4、6 次邀请**只有被邀请人得券**；而 BPM-2 的字面口径是每次有效邀请双方各得一张 | `invite_ladder_tier`（V39 每档 1 张）vs `invitee_reward_rule`（每次一张） | 需产品确认：给邀请人加「每次一张」的发券路径（配置化，与阶梯奖并存还是替代），还是维持现口径并改文档 | 1.5 |
| **D-41** | **号源网格在午夜边界无限循环**（**本轮新发现**）：切网格的循环拿 `LocalTime` 当循环变量，`23:30.plusMinutes(30)` 绕回 `00:00` → 「还有下一格吗」永远成立 → 列表无限增长 → **OOM**。触发条件是**正常营业时间**（打烊 23:31–23:59，或正好 23:30），下单与号源查询双双 50000，**把整个堆打爆，不是可恢复的报错** | `SlotGrid.java:89-92`（守卫只拦了 `close <= open`，拦不住跨午夜的回绕）；2026-10-01 00:14 实测复现 | 格数先算出、再按索引生成窗口（不再拿 `LocalTime` 当循环变量）；补边界用例 `AppointmentSlotTest#gridStopsAtMidnightBoundary`（47 格、末格 23:00–23:30） | 0.75 |

### 4.2 未做功能（5 条 / 21 人天）

| ID | 缺陷 | 现状与口径 | 修复方案 | 人天 | 前置 |
| --- | --- | --- | --- | --- | --- |
| **D-27** | **组合套餐未做**（无表无接口） | ADR-0034 §5 / ADR-0042 §2 已定口径：平台定骨架 + 服务者组包定价 + 订单展开成多个订单项 | `package_template`（平台）+ `provider_package`（服务者）+ 下单展开 + 打包价校验（各单项定价之和 ÷ 套餐价） | 10.0 | E-06（打包价规则确认） |
| **D-28** | **成本测算未做** | 只有 AI 日预算告警（单价占位）与券的 `cost_bearer` | 按「账期 × 模型」聚合 `ai_consult.prompt_tokens/completion_tokens` + 券核销，单价/补贴比例做成页面可填参数（**纯只读，不建假设表**） | 3.0 | 无（参数后填） |
| **D-29** | **营销中心残缺**：物料模板、活动管理、二维码图片都没有（只有推广码文本） | `MarketingView.vue`（推广码卡已通）；契约只给码本身 | ①二维码图片（前端纯函数，0.5）②物料模板（素材库表）③活动（活动表 + 参与记录）——②③等形态定稿 | 5.5 | E-06（活动形态） |
| **D-30** | 数据看板与结算对账不完整 | `StatsView.vue`（运营）自述缺口；结算中心按 ADR-0002 退化为对账视图 | 合并券额度账 + 订单流水 + AI 用量到一张对账页；结算中心只读视图 | 1.25 | 无 |
| **D-31** | **演示数据要人工准备**（券的成本券、达标线、知识复核） | round5 §7.3 | 写幂等种子脚本（`server/scripts/demo-seed.sh` 或 SQL） | 1.25 | 无 |

### 4.3 P2 文档、过程与卫生（9 条 / 6.25 人天）

| ID | 缺陷 | 证据 | 修复方案 | 人天 |
| --- | --- | --- | --- | --- |
| **D-32** | 文档指针过期：`AGENTS.md` 说「第四轮是当前有效清单」，磁盘上第五轮已是最新且**未入库**；`ARCHITECTURE.md §10` 仍称 `packages/shared` 没有自己的 vitest（实际 `packages/shared/vitest.config.ts` 与 `test: vitest run` 都在） | `AGENTS.md`、`ARCHITECTURE.md §10`、`packages/shared/package.json` | 更新两处指针；round5 报告入库 | 0.25 |
| **D-33** | `.zcode/plans/plan-sess_*.md` 未被 `.gitignore` 覆盖，会被误提交 | `git check-ignore -v .zcode/plans/` 无输出；目录下确实有文件 | `.gitignore` 加 `.zcode/`（或 `.zcode/plans/`） | 0.1 |
| **D-34** | `ProviderReviewLog.ACTION_ASSIGN_REGION = 10` 没同步迁移里的列注释（V43 的注释止于 9），而 V43 自称「注释是它唯一的说明书」 | `ProviderReviewLog.java:48`；`V43:49` | 下一条迁移补 `action=10` 的说明 | 0.1 |
| **D-35** | 服务层命名：`AllianceCategoryService#list()`、`ProviderInviteCodeService#view()` 用纯名词方法名 | `docs/conventions.md:48` | 按约定改名（`listCategories` / `viewInviteCode` 之类） | 0.2 |
| **D-36** | **重复代码 4 处**：①`InviteService` 的 `attributeToProvider` 与 `attribute` 同形 ②`categoryName` 在 3 个服务里各拼一次 ③`QualificationPanel` 与 `OnboardingPanel` 的图片上传逐行同形（约定里已有 `*Uploader.vue` 形态名）④96×96 缩略图样式全仓 6 份 | `InviteService.java:135`；`ProviderProfileService.java:147` / `ProviderAdminService.java:130` / `AllianceCategoryService.java:173`；`QualificationPanel.vue:135-173`；三处新增缩略图样式 | ①抽一份归因模板 ②名称解析收进 `AllianceCategoryService` ③抽共享 `PhotoUploader` ④抽一个 `.ph-thumb` 类 | 1.0 |
| **D-37** | 零碎：两个零调用方法（`ProviderInviteCodeService#now()`、`ProviderInviteCode#isEnabled()`）、三处内联全限定名、两处 `fileId!` 断言、`ProviderListPanel.vue:355` 模板漏出 Markdown `**…**` | 见评估报告 §12.1 B | 删零调用 / 补 import / 去断言 / 修模板 | 0.5 |
| **D-38** | **4 类交付物缺失**：压测报告（含 P95 实测）、回滚演练记录、培训文档（用户/服务者/运营）、UI 标注稿/切片 | 交付文档 12.2 清单 vs `docs/` 现状（`server/scripts/undo-drill.sh` 有，但没有演练记录归档） | 逐项产出：压测（wrk/JMeter 一轮）、回滚演练复跑并归档、三份培训文档、标注稿（可直接用 token 页面导出） | 3.0 |
| **D-40** | 三端与 `packages/*` 没有 eslint/prettier（只有 `.editorconfig`） | `ARCHITECTURE.md §10` | 引入 flat config + `lint` script，接进 `web.yml` | 1.0 |
| **D-39** | 新版《完整方案》与「一期验收标准」原文未入库 | 见评估报告 §0（附件与旧版文档 md5 相同） | 索取原文 → 入 `docs/reference/` + 登记 md5 | 0.1 + 等甲方 |

---

## 五、挂起项（需外部输入，不计工时）

| ID | 需要什么 | 影响谁 | 拿到之后 |
| --- | --- | --- | --- |
| **E-01** | **区域保护的排他规则**（谁在哪个区独占、多久、冲突怎么判） | 交付文档 F022；当前只做「可维护 + 可筛选」 | D-27 之后单开切片（估 3 人天） |
| **E-02** | **视觉稿「转人工 39 元/次」与 ADR-0002/0036 的冲突**（实现写「不收费」） | D-16~D-26 的验收口径 | 甲方改稿 → 文案统一 |
| **E-03** | **视觉稿「请联系商家管理员」用了禁用词** | 4.16.10 检查清单 | 甲方改稿（本仓库不能照做） |
| **E-04** | **C 端是否改手机版式**（推翻 ADR-0016？） | **整批 P1 视觉** | 若「改」→ D-16~D-26 作废重估（产品级改版）；若「不改」→ 按本计划执行 |
| **E-05** | 支付链路口径（到店付是否确认） | D-30、结算中心 | 确认后单开切片 |
| **E-06** | 组合套餐的打包价规则 & 营销活动的形态 | D-27、D-29 | 形态定稿 → 按 ADR-0034/0042 开工 |
| **E-07** | 券面额、邀请阶梯、达标线、AI 额度与预算的运营数值 | 考核与券的所有对外承诺 | 运营确认 → 改 `V39` 与运营页配置 |
| **E-08** | 40 条知识条目的**兽医复核**（现全 `pending_review`，AI 引用恒空） | 知识库体验 | 复核后 `vetted` → 引用自动出现（不需要改代码） |

---

## 六、执行批次与出口标准

| 批次 | 内容 | 人天 | 出口标准（缺一不可） |
| --- | --- | --- | --- |
| **Wave 0 · 收口**（先做，不改业务代码） | D-02 提交收口 → D-03 ADR-0053 → D-32 文档指针 → D-33 gitignore | 1.1 | `git status` 干净；`grep 0053` 全部命中；新人按 `AGENTS.md` 读到的是最新轮次 |
| **Wave 1 · 断链与安全** | D-01 写回 → D-07 排序 → D-15 测试改真 → D-04 资质图归属 → D-06 券选优 → D-05 双发券 | 7.5 | `mvnw -B verify` 全绿；**把 D-01 的写回注释掉后 D-15 必须变红**；新用户凭码注册双方各得一张券 |
| **Wave 2 · 视觉**（E-04 决策后启动） | D-16 → D-17 → D-18 → D-19 → D-20 → D-21 → D-22 → D-23 → D-24 → D-25 → D-26 | 10.75 | 4.16.10 的十四条清单逐条过并留截图；420px 不再挤坏；五页区块对照表全绿 |
| **Wave 3 · 独立切片**（可并行，三条互不阻塞） | D-27 套餐(10) ∥ D-28 成本(3) ∥ D-29 营销(5.5) ∥ D-30 看板(1.25) ∥ D-31 种子(1.25) | 21.0 | 每条各自的接口测试 + 冒烟路径（照 round5 §7.2 的形式写一组） |
| **Wave 4 · 卫生与交付物**（穿插做） | D-08 风控 → D-09/D-10 ADR与契约 → D-11/D-12/D-13/D-14 知识库与提醒 → D-34~D-37 → D-38 交付物 → D-40 lint | 11.3 | 契约生成检查过关；四类交付物入库 |
| **回归** | 三端 + 后端 + AI 全量；契约生成一致性 | 1.5 | 见下方命令与预期 |

> 批次合计 1.1 + 7.5 + 10.75 + 21.0 + 11.3 + 1.5 = **53.15 人天**（含回归 1.5；D-39 的 0.1 未计入，它等甲方）。

**回归命令（每批合并前跑）**

```bash
cd server && ./mvnw -B verify                      # 真库，约 11 分钟，需要 Docker
cd apps/c-web && npx vue-tsc -b && npx vitest run
cd apps/provider-web && npx vue-tsc -b && npx vitest run
cd apps/admin-web && npx vue-tsc -b && npx vitest run
cd packages/shared && npx vitest run
pnpm --filter @pet-health/shared gen:api:check      # 契约与生成物必须一致
```

---

## 七、这两件事必须由人来定（否则计划会空转）

1. **E-04（C 端是否改手机版式）**：它决定 Wave 2 的 10.75 人天是执行还是作废。建议在 Wave 1 期间就拍板。
2. **E-07（运营数值）**：券面额、邀请阶梯、达标线不定，考核与券的每一处对外展示都只能标「暂定」。

**不做 D-01 的代价**（单独强调）：交付文档 F022「等级决定 AI 推荐优先级、流量加权」无法验收，
而且排序读着一列永不更新的值——比改造前更难排查（改造前至少读的是会变的 `level`）。
