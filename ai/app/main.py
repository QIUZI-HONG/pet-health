"""AI 服务入口。

**当前是骨架**：接口契约（`models.py`）与配置（`config.py`）已就位，处理链路
——硬红线匹配 → 意图路由 → 知识检索 → 生成 → 校验兜底——尚未实现。
链路设计见 `docs/design/ai-service.md` 第 3 节。
"""

from fastapi import FastAPI, Header, HTTPException

from .config import settings
from .models import ConsultRequest, ConsultResponse

app = FastAPI(title="pet-health-ai", version="0.1.0")


def _require_internal_token(token: str | None) -> None:
    if token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/consult", response_model=ConsultResponse)
def consult(
    req: ConsultRequest,
    x_internal_token: str | None = Header(default=None),
) -> ConsultResponse:
    _require_internal_token(x_internal_token)
    _ = req  # 骨架阶段不消费请求内容

    # 尚未接入任何模型。返回**保守的降级结果**，避免调用方把占位值当成真实分级：
    # 黄灯 + 建议就医 + degraded 标记。
    return ConsultResponse(
        risk_level=2,
        possible_causes=[],
        action_suggestion="AI 服务尚未接入模型，建议就医或咨询兽医。",
        need_hospital=True,
        care_tips=[],
        citations=[],
        degraded=True,
        degrade_reason="not_implemented",
        model_name=settings.ai_model_chat,
    )
