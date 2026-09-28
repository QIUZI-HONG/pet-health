"""AI 服务入口。

**模型调用已打通**（纯文本路径，供应商见 ADR-0017），但离设计文档里的完整链路还差三块，
都会影响结果质量，所以明说：

- **硬红线预检**：已实现（ADR-0021）——判据在 `knowledge_red_flag`，命中即判红且**不调模型**；
  词表的运营侧维护（增删与复核）属 #103/#118；
- **知识检索**：L1/L2/L3（#63 已给架构）尚未实现，所以 `citations` 恒为空——
  回答里的来源引用要等检索层落地；
- **语音**：当前供应商没有转写端点；**图片可用**（走 `ai_vision_model`，实测 flash 能读图、
  pro 不能），没配视觉模型时才降级并告知。

链路设计见 `docs/design/ai-service.md` 第 3 节；护栏与留痕字段见同文第 5 节。
"""

import logging

from fastapi import Depends, FastAPI, Header, HTTPException

from . import guardrails, model_client, prompts, red_flags
from .config import settings
from .models import ConsultRequest, ConsultResponse

# 关掉交互式文档与 openapi.json：那张表把内部接口的请求结构免费送给任何能访问到这个进程的人，
# 而这正是 require_internal_token 的注释里说「不能让未鉴权调用方探查」的东西（测试报告 D17）。
app = FastAPI(title="pet-health-ai", version="0.3.0",
              docs_url=None, redoc_url=None, openapi_url=None)

logger = logging.getLogger("pet_health_ai")

#: 模型不可用时的保守建议：不分级、直接建议就医。医疗场景宁严勿松（docs/conventions.md）。
DEGRADED_SUGGESTION = "AI 服务暂时不可用，为避免耽误，建议尽快咨询兽医；情况紧急请直接送医。"


def _log_degraded(reason: str, trace_id: str, detail: object) -> None:
    """降级一律留痕：降级率是判断「这个模型好不好用」最直接的指标。"""
    logger.warning("consult degraded reason=%s trace_id=%s detail=%s", reason, trace_id, detail)


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """内部鉴权。**必须做成依赖而不是在函数体里判**：FastAPI 先校验请求体再进函数体，
    写在函数里会导致「无令牌 + 请求体不合法」先返回 422 —— 等于让未鉴权的调用方
    探查请求结构。挂在 dependencies 上，鉴权先跑（联调时实测过这两种行为）。
    """
    # 未配置令牌时一律拒绝（fail closed）：漏配不能让接口变成对所有人开放
    if not settings.internal_token or x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    """给探针用的裸健康检查：**只回一句话**，不带配置与能力信息。

    与 `/internal/health` 分开：探针（容器编排、负载均衡）不需要知道模型配没配、
    有没有视觉能力，而那些信息对探测者是情报。
    """
    return {"status": "ok"}


@app.get("/internal/health", dependencies=[Depends(require_internal_token)])
def health() -> dict[str, object]:
    """健康检查顺带报出「模型配好了没、具备哪些能力」，省得靠猜。

    **需要内部令牌**：它描述的是我们的配置状态（有没有 key、用的哪个模型），
    不是给公网看的（测试报告 D17）。
    """
    return {
        "status": "ok",
        "model_configured": bool(settings.ai_api_key),
        "model_grading": settings.ai_model_grading,
        "prompt_version": settings.prompt_version,
        "capabilities": {
            "text": True,
            "image": settings.ai_supports_image,
            "audio": settings.ai_supports_audio,
            "embedding": settings.ai_supports_embedding,
        },
    }


def _degraded_response(
    *,
    risk_level: int,
    action_suggestion: str,
    code: str,
    detail: object,
    rule_set: red_flags.LoadResult,
    care_tips: list[str] | None = None,
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
) -> ConsultResponse:
    """降级答复的统一构造。四个分支同形状，收在一处免得「改了码忘了明细」。

    `degrade_detail` 里可能带异常类名、上游原文、甚至**模型返回的原始文本**：
    它只进日志与留痕（Java 侧落 `ai_consult.degrade_reason`），**不进用户可见文案**——
    用户看到的是一句按 `degrade_code` 映射的中文（测试报告 D6）。
    """
    return ConsultResponse(
        risk_level=risk_level,
        action_suggestion=action_suggestion,
        need_hospital=True,
        degraded=True,
        degrade_code=code,
        degrade_detail=f"{type(detail).__name__}: {detail}",
        care_tips=care_tips or [],
        images_used=0,
        # 降级也可能已经花过钱（修复重试先成功一次再失败）：异常捎带的用量照记，
        # 不然预算会系统性偏低（ADR-0026）
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        red_flag_check="ok" if rule_set.available else "unavailable",
        model_name=settings.ai_model_grading,
        prompt_version=settings.prompt_version,
    )


def _red_flag_response(
    req: ConsultRequest,
    hits: list[red_flags.RedFlagHit],
    image_count: int,
    rule_set: red_flags.LoadResult,
) -> ConsultResponse:
    """红线命中时的响应：一句话给动作，其余命中项作为补充事项。

    `model_name` 标成 `rule:red_flag` 而不是留空——留痕里必须能看出**这次没有模型参与**，
    否则事后分析分级准确率时会把规则判定混进模型样本里（ADR-0021）。
    """
    primary = hits[0].rule
    tips = [hit.rule.action_hint for hit in hits[1:] if hit.rule.action_hint]
    logger.warning(
        "consult red_flag trace_id=%s user_id=%s hits=%s images=%s",
        req.trace_id, req.user_id, [hit.code for hit in hits], image_count,
    )
    return ConsultResponse(
        risk_level=3,
        possible_causes=[],
        action_suggestion=primary.action_hint or "命中急症信号，请立即送医。",
        need_hospital=True,
        care_tips=tips,
        images_used=0,
        red_flag_check="ok" if rule_set.available else "unavailable",
        red_flag_hits=[hit.code for hit in hits],
        # 命中详情带给 Java 侧留痕：哪条规则、命中的是哪个词
        guard_hits=[f"{hit.code}:{hit.term}" for hit in hits],
        degraded=False,
        model_name="rule:red_flag",
        prompt_version=settings.prompt_version,
    )


@app.post(
    "/internal/consult",
    response_model=ConsultResponse,
    dependencies=[Depends(require_internal_token)],
)
async def consult(req: ConsultRequest) -> ConsultResponse:
    images = req.input.media_urls[: settings.ai_max_images]
    image_count = len(images)

    # ---- 硬红线预检：命中即判红，**不调模型**（ADR-0021）----
    # 排在最前面有两个理由：它判的是分钟级急症，早一次跳转就少一段时间；它还很便宜，
    # 命中时省掉一次模型调用。
    rule_set = red_flags.load_rules()
    hits = red_flags.match(
        req.input.text,
        rule_set.rules,
        species=req.pet.species,
        age_stage=red_flags.age_stage_of(req.pet.birth_date, req.pet.species),
    )
    if hits:
        return _red_flag_response(req, hits, image_count, rule_set)

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
        _log_degraded("image_not_supported", req.trace_id, exc)
        return _degraded_response(
            risk_level=2,
            action_suggestion="暂时无法分析图片，请把症状用文字补充清楚（部位、多久、有没有变化）；"
            "如情况紧急请直接送医。",
            code="image_not_supported",
            detail=exc,
            rule_set=rule_set,
            care_tips=[f"已收到 {image_count} 张图片，但本轮没有分析它们。"],
        )
    except model_client.ImageUnavailable as exc:
        # 有能看图的模型，但图片没取回来（签名过期、后端不可达、体积超限）。
        # 与上一类分开留痕：一个是「模型不能看图」，一个是「我们没把图送到」。
        _log_degraded("image_unavailable", req.trace_id, exc)
        return _degraded_response(
            risk_level=2,
            action_suggestion="这次没能读到你的图片，请先用文字描述症状（部位、多久、有没有变化）；"
            "如情况紧急请直接送医。",
            code="image_unavailable",
            detail=exc,
            rule_set=rule_set,
            care_tips=[f"已收到 {image_count} 张图片，但本轮没有分析它们。"],
        )
    except model_client.ModelUnavailable as exc:
        # 传输层挂了：降级成保守建议，不向用户报错（设计文档第 5 节的硬要求）
        _log_degraded("model_unavailable", req.trace_id, exc)
        return _degraded_response(
            risk_level=2,
            action_suggestion=DEGRADED_SUGGESTION,
            code="model_unavailable",
            detail=exc,
            rule_set=rule_set,
            prompt_tokens=exc.prompt_tokens,
            completion_tokens=exc.completion_tokens,
        )
    except model_client.ModelOutputInvalid as exc:
        # 模型答了但没法用（没调工具、参数越界）。按设计：风险拔高一档更安全
        _log_degraded("model_output_invalid", req.trace_id, exc)
        return _degraded_response(
            risk_level=3,
            action_suggestion=DEGRADED_SUGGESTION,
            code="model_output_invalid",
            detail=exc,
            rule_set=rule_set,
            prompt_tokens=exc.prompt_tokens,
            completion_tokens=exc.completion_tokens,
        )

    # 留痕：trace_id 从 Java 一路带过来，这里落日志，模型侧出问题才追得回去（ADR-0009）
    logger.info(
        "consult trace_id=%s user_id=%s model=%s risk=%s images=%s degraded=false latency_ms=%s prompt=%s",
        req.trace_id, req.user_id, result.model_name, result.risk_level,
        image_count, result.latency_ms, settings.prompt_version,
    )

    # ---- 输出层护栏：绝不输出确诊 / 处方 / 剂量（交付文档 9.5）----
    # 提示词是概率性的，这里是机械的。命中就改写并留痕，不静默删除（ADR-0021 第四条）。
    causes, cause_hits = guardrails.review_list(list(result.possible_causes))
    action, action_hits = guardrails.review(result.action_suggestion)
    care, care_hits = guardrails.review_list(list(result.care_tips))
    guard_hits = cause_hits + action_hits + care_hits
    if guard_hits:
        _log_degraded("output_guardrail", req.trace_id, guard_hits)

    return ConsultResponse(
        risk_level=result.risk_level,
        possible_causes=causes,
        action_suggestion=action,
        need_hospital=result.need_hospital,
        care_tips=care,
        # 检索层未实现，来源引用拿不出来。宁可空着，也不编造条目 ID。
        citations=[],
        # 本轮模型实际看了几张图。留痕用：事后归因分级漂移时要能区分「当时有图」和「当时没图」
        images_used=image_count,
        guard_hits=guard_hits,
        red_flag_check="ok" if rule_set.available else "unavailable",
        degraded=False,
        model_name=result.model_name,
        model_version=result.model_version,
        prompt_version=settings.prompt_version,
        latency_ms=result.latency_ms,
        # 真实用量：日预算告警与「分级漂移」归因都看它（ADR-0026）
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
    )
