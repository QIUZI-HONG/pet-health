"""AI 服务入口。

**模型调用已打通**（纯文本路径，供应商见 ADR-0017），但离设计文档里的完整链路还差三块，
都会影响结果质量，所以明说：

- **硬红线预检**：词典要入库（#103），表还没建，因此现在完全依赖提示词里的铁律与模型判断；
- **知识检索**：L1/L2/L3（#63 已给架构）尚未实现，所以 `citations` 恒为空——
  回答里的来源引用要等检索层落地；
- **语音**：当前供应商没有转写端点；**图片可用**（走 `ai_vision_model`，实测 flash 能读图、
  pro 不能），没配视觉模型时才降级并告知。

链路设计见 `docs/design/ai-service.md` 第 3 节；护栏与留痕字段见同文第 5 节。
"""

from fastapi import Depends, FastAPI, Header, HTTPException

from . import model_client, prompts
from .config import settings
from .models import ConsultRequest, ConsultResponse

app = FastAPI(title="pet-health-ai", version="0.2.0")

#: 模型不可用时的保守建议：不分级、直接建议就医。医疗场景宁严勿松（docs/conventions.md）。
DEGRADED_SUGGESTION = "AI 服务暂时不可用，为避免耽误，建议尽快咨询兽医；情况紧急请直接送医。"


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """内部鉴权。**必须做成依赖而不是在函数体里判**：FastAPI 先校验请求体再进函数体，
    写在函数里会导致「无令牌 + 请求体不合法」先返回 422 —— 等于让未鉴权的调用方
    探查请求结构。挂在 dependencies 上，鉴权先跑（联调时实测过这两种行为）。
    """
    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/internal/health")
def health() -> dict[str, object]:
    """健康检查顺带报出「模型配好了没、具备哪些能力」，省得靠猜。"""
    return {
        "status": "ok",
        "model_configured": bool(settings.ai_api_key),
        "model_grading": settings.ai_model_grading,
        "prompt_version": prompts.PROMPT_VERSION,
        "capabilities": {
            "text": True,
            "image": settings.ai_supports_image,
            "audio": settings.ai_supports_audio,
            "embedding": settings.ai_supports_embedding,
        },
    }


@app.post(
    "/internal/consult",
    response_model=ConsultResponse,
    dependencies=[Depends(require_internal_token)],
)
async def consult(req: ConsultRequest) -> ConsultResponse:
    images = req.input.media_urls[: settings.ai_max_images]
    image_count = len(images)

    user_prompt = prompts.build_user_prompt(
        pet=req.pet.model_dump(),
        text=req.input.text,
        history=req.history,
        image_count=image_count,
    )

    try:
        result = await model_client.assess(
            system_prompt=prompts.SYSTEM_PROMPT,
            user_prompt=user_prompt,
            images=images,
        )
    except model_client.VisionUnavailable as exc:
        # 有图但当前没有能看图的模型。**明确告知**，不做静默忽略——
        # 用户以为模型看过照片、其实没看，比直接说不支持危险得多。
        return ConsultResponse(
            risk_level=2,
            action_suggestion="暂时无法分析图片，请把症状用文字补充清楚（部位、多久、有没有变化）；"
            "如情况紧急请直接送医。",
            need_hospital=True,
            degraded=True,
            degrade_reason=f"image_not_supported: {exc}",
            care_tips=[f"已收到 {image_count} 张图片，但本轮没有分析它们。"],
            images_used=0,
            model_name=settings.ai_model_grading,
            prompt_version=prompts.PROMPT_VERSION,
        )
    except model_client.ModelUnavailable as exc:
        # 传输层挂了：降级成保守建议，不向用户报错（设计文档第 5 节的硬要求）
        return ConsultResponse(
            risk_level=2,
            action_suggestion=DEGRADED_SUGGESTION,
            need_hospital=True,
            degraded=True,
            degrade_reason=f"model_unavailable: {exc}",
            images_used=0,
            model_name=settings.ai_model_grading,
            prompt_version=prompts.PROMPT_VERSION,
        )
    except model_client.ModelOutputInvalid as exc:
        # 模型答了但没法用（没调工具、参数越界）。按设计：风险拔高一档更安全
        return ConsultResponse(
            risk_level=3,
            action_suggestion=DEGRADED_SUGGESTION,
            need_hospital=True,
            degraded=True,
            degrade_reason=f"model_output_invalid: {exc}",
            images_used=0,
            model_name=settings.ai_model_grading,
            prompt_version=prompts.PROMPT_VERSION,
        )

    return ConsultResponse(
        risk_level=result.risk_level,
        possible_causes=result.possible_causes,
        action_suggestion=result.action_suggestion,
        need_hospital=result.need_hospital,
        care_tips=result.care_tips,
        # 检索层未实现，来源引用拿不出来。宁可空着，也不编造条目 ID。
        citations=[],
        # 本轮模型实际看了几张图。留痕用：事后归因分级漂移时要能区分「当时有图」和「当时没图」
        images_used=image_count,
        degraded=False,
        model_name=result.model_name,
        model_version=result.model_version,
        prompt_version=prompts.PROMPT_VERSION,
        latency_ms=result.latency_ms,
    )
