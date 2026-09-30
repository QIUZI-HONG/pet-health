"""Java ↔ Python 的内部契约。详见 docs/design/ai-service.md 第 2 节。

改这里的字段等于改跨服务契约，两侧必须同步改。
"""

from pydantic import BaseModel, Field


class PetContext(BaseModel):
    id: int
    species: int
    breed: str | None = None
    birth_date: str | None = None
    weight: float | None = None
    chronic: list[str] = Field(default_factory=list)
    recent_records: list[dict] = Field(default_factory=list)


class ConsultInput(BaseModel):
    type: str  # text | image
    # 必填，不是可选项：宠物皮肤病纯图片零样本分诊只有 33%，
    # 补上症状文本才到 72–94%（61 号调研）。这条约束从接口层卡住。
    text: str
    media_urls: list[str] = Field(default_factory=list)


class ConsultRequest(BaseModel):
    trace_id: str
    user_id: int
    pet: PetContext
    input: ConsultInput
    history: list[dict] = Field(default_factory=list)


class Citation(BaseModel):
    """一条被引用的知识条目（切片 #100/#101）。

    **`review_status` 必须带出去**：ADR-0025 定「种子条目一律 pending_review、只用于检索与引用」，
    而「没经兽医复核过的内容要被标出来」是同一份 ADR 的硬要求——C 端据此可以把「待复核」
    显示给用户，而不是把未复核的条目当成权威资料。

    `source_*` 一起给：用户看到的「有来源」必须真能点回原始资料（63 号调研 §4.5 第 5 条），
    只给一个内部编号等于让来源不可追溯。
    """

    entry_id: str  # K-0010
    title: str = ""
    category: str = ""
    source_title: str = ""
    source_version: str | None = None
    source_url: str | None = None
    review_status: str = "pending_review"


class ConsultResponse(BaseModel):
    risk_level: int  # 1 绿 / 2 黄 / 3 红
    possible_causes: list[str] = Field(default_factory=list)
    action_suggestion: str = ""
    need_hospital: bool = False
    care_tips: list[str] = Field(default_factory=list)
    #: 本次回答**实际引用**的知识条目，**只含 `vetted`（兽医复核过）的那些**。
    #: 检索为空、模型没引用、引用全被校验剔除、命中的条目未复核——这四种情况都会给出空数组，
    #: 而 C 端据此**不许**说「基于知识库」（ADR-0028 的口径；解禁条件是「至少有一条 vetted 引用」）。
    citations: list[Citation] = Field(default_factory=list)
    #: 本轮进了模型上下文的**未复核**条目编号（pending_review）。非空时回答必须明说
    #: 「该建议尚未经兽医复核」——用未复核内容生成回答是允许的，假装它复核过不行（ADR-0033）。
    #: 它们**不会**出现在 `citations` 里。
    unvetted_hits: list[str] = Field(default_factory=list)
    #: 本轮实际送进模型几张图（0 表示没看图或模型看不见）。留痕用：
    #: 事后归因「分级漂移」时要能区分「当时有图」和「当时没图」。
    images_used: int = 0
    #: 命中的硬红线规则编号（如 ["RF-007"]）。非空表示**没有调模型**，结论由规则给出——
    #: 留痕里要能一眼分清「模型判的红」与「红线规则判的红」（ADR-0021）。
    red_flag_hits: list[str] = Field(default_factory=list)
    #: 命中的**分级规则**编号（如 ["GR-001"]）。与红线不同：红线短路，分级规则只抬档（#103）。
    grading_rule_hits: list[str] = Field(default_factory=list)
    #: 输出层护栏与检索侧的命中痕迹。三类前缀各自可读：
    #: `dose` / `phrase:xxx` / `drug:xxx`（模型或运营文案越界，ADR-0021 第四条）；
    #: `kb:flag:<条目>:<原因>`（检索到的条目被安全门剔除或被护栏清洗）；
    #: `citation:<编号>`（编造/无效引用被剔除）、`citation:unbacked:n`（n 条结论没有来源）。
    guard_hits: list[str] = Field(default_factory=list)
    # 红线预检本身是否生效：ok / unavailable。不可用时**不静默放行**，Java 侧据此告警。
    red_flag_check: str = "ok"
    #: 知识检索这一层是否生效：ok（召回非空）/ empty（正常但没召回到）/ unavailable（**读不到**：
    #: 知识域读不到，或检索开关的配置读不到——`detail` 里写明是哪一种）/
    #: disabled（运营**确实**把检索开关关了：只有读到 false 才算，读不到报 unavailable）/
    #: skipped（红线短路，检索根本没跑）。
    #: **五种取值都要记**：`empty` 是 ADR-0022 说的「召回缺口」的观测口，`unavailable` 与
    #: `disabled` 则完全不同——一个是故障，一个是人为关掉。把前者记成后者等于「库挂了没人告警」
    #: （ADR-0050 §三）。
    retrieval_check: str = "ok"
    #: 运营可调项这一批（提示词 / 分级规则 / 护栏词表 / 运行时开关）**读到了没有**：
    #: ok / unavailable。与 `red_flag_check` 同构，只是对象从一层词表换成整批配置。
    #: `unavailable` 表示四条来源里至少有一条读不到（哪一条、为什么，在 `detail` 与
    #: AI 侧的 WARN 日志里），此时那一项回落到代码基线、咨询照常。
    #: **它不是降级**：`degraded` 仍为 false——fail-closed 只影响行为（开关按关闭算），
    #: 不影响可用性判定，而「这一轮用的是代码基线还是运营配置」是个**可观测的事实**
    #: （ADR-0050 §三）。没有它，`OpsConfig.available` / `detail` 只有一条 WARNING 出口：
    #: 调用方与留痕都看不出「服务在跑但运营配置没生效」（ADR-0010 的那条口径就落不下来）。
    ops_config_check: str = "ok"
    degraded: bool = False
    # 降级的**机器可读原因**（image_not_supported / image_unavailable / model_unavailable /
    # model_output_invalid）。给用户看的中文由 Java 侧按这个码映射——原因里的细节
    # （异常类名、上游原始响应、模型原始输出）不能出现在用户可见的文案里（测试报告 D6）。
    degrade_code: str | None = None
    # 内部明细：只进日志与留痕，**不进用户可见文案**。故意不叫 degrade_reason——
    # 那个名字在 C 端契约里是「给用户看的中文句子」，同名双语义只会让下一次改动改错地方。
    degrade_detail: str | None = None
    model_name: str = ""
    model_version: str = ""
    prompt_version: str = ""
    latency_ms: int = 0
    # 本轮实际消耗的 token（红线短路与降级路径记 0：那两条没调模型）。
    # Java 侧落 ai_consult 的两列，日预算告警按它们估算花费（ADR-0026）
    prompt_tokens: int = 0
    completion_tokens: int = 0

class SymptomMatchRequest(BaseModel):
    """F011 规则版的输入：一句症状描述（不需要宠物档案，所以没有 pet 上下文）。"""

    text: str = Field(min_length=2, max_length=500)
    #: 可选：带上物种与年龄段能让分诊摘要更准（检索层对条目做同样的过滤）；不传即不限
    species: int | None = None
    age_stage: str = "all"


class SymptomMatchItem(BaseModel):
    """一个被认出来的症状（规范名 + 它的分诊条目摘要）。

    `title` / `risk_hint` 可能为空：词表命中而条目读不到时，症状照样报出去——
    「你说的是腹泻」这件事成立，只是拿不到条目里的就医紧迫程度。
    """

    symptom: str
    entry_code: str | None = None
    title: str | None = None
    #: green / yellow / red（`knowledge_entry.risk_hint`）；为空表示这次拿不到
    risk_hint: str | None = None


class SymptomMatchResponse(BaseModel):
    matches: list[SymptomMatchItem] = Field(default_factory=list)
    #: 知识层状态：`ok` / `unavailable`（读不到词典或条目）。unavailable 时 matches 为空，
    #: 调用方按降级处理——**不报错**，只是这次认不出症状（与检索的降级口径一致）。
    knowledge_check: str = "ok"


class KnowledgeBrowseRequest(BaseModel):
    """C 端知识库浏览的输入：按分类翻、按关键词搜，或按编号取单条。"""

    category_code: str | None = None
    keyword: str | None = Field(default=None, max_length=64)
    #: 给了编号就是「取这一条」（详情，含正文）；不给就是列表
    code: str | None = Field(default=None, max_length=32)
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=20, ge=1, le=50)


class KnowledgeBrowseItem(BaseModel):
    """一条可读的知识条目。`body` 只在详情里有值——列表不下发正文（省流量，也让「点进去看」有意义）。"""

    code: str
    title: str
    summary: str = ""
    body: str = ""
    category_code: str | None = None
    category_name: str | None = None
    #: green / yellow / red（仅症状分诊与急救类填）；为空表示这条不含就医紧迫程度
    risk_hint: str | None = None
    #: pending_review / vetted——C 端**必须**据此标出「未经兽医复核」（ADR-0033）
    review_status: str
    source_title: str | None = None
    source_url: str | None = None


class KnowledgeBrowseResponse(BaseModel):
    items: list[KnowledgeBrowseItem] = Field(default_factory=list)
    total: int = 0
    #: ok / unavailable：读不到知识库时返回空 + unavailable，调用方按「暂时看不了」处理（不报错）
    knowledge_check: str = "ok"
