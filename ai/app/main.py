"""AI 服务入口。

**当前是骨架**：接口契约（`models.py`）与配置（`config.py`）已就位，处理链路
——硬红线匹配 → 意图路由 → 知识检索 → 生成 → 校验兜底——尚未实现。
链路设计见 `docs/design/ai-service.md` 第 3 节。
"""

from fastapi import Depends, FastAPI, Header, HTTPException

from .config import settings
from .models import ConsultRequest, ConsultResponse

app = FastAPI(title="pet-health-ai", version="1.0.0")


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """内部鉴权。**必须做成依赖而不是在函数体里判**：FastAPI 先校验请求体再进函数体，
    写在函数里会导致「无令牌 + 请求体不合法」先返回 422 —— 等于让未鉴权的调用方
    探查请求结构。挂在 dependencies 上，鉴权先跑（联调时实测过这两种行为）。
    """
    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/consult", response_model=ConsultResponse, dependencies=[Depends(require_internal_token)])
def consult(req: ConsultRequest) -> ConsultResponse:
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
