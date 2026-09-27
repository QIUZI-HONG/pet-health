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
    degraded: bool = False
    degrade_reason: str | None = None
    model_name: str = ""
    model_version: str = ""
    prompt_version: str = ""
    latency_ms: int = 0
