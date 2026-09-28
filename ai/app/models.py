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


class ConsultResponse(BaseModel):
    risk_level: int  # 1 绿 / 2 黄 / 3 红
    possible_causes: list[str] = Field(default_factory=list)
    action_suggestion: str = ""
    need_hospital: bool = False
    care_tips: list[str] = Field(default_factory=list)
    citations: list[str] = Field(default_factory=list)  # 知识条目 ID，如 K-0042
    # 本轮实际送进模型几张图（0 表示没看图或模型看不见）。留痕用：
    # 事后归因「分级漂移」时要能区分「当时有图」和「当时没图」。
    images_used: int = 0
    # 命中的硬红线规则编号（如 ["RF-007"]）。非空表示**没有调模型**，结论由规则给出——
    # 留痕里要能一眼分清「模型判的红」与「红线规则判的红」（ADR-0021）。
    red_flag_hits: list[str] = Field(default_factory=list)
    # 输出层护栏命中的标记（dose / phrase:xxx）：为「模型这次越界了」留证据（ADR-0021 第四条）。
    guard_hits: list[str] = Field(default_factory=list)
    # 红线预检本身是否生效：ok / unavailable。不可用时**不静默放行**，Java 侧据此告警。
    red_flag_check: str = "ok"
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
