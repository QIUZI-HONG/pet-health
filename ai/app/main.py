"""AI 服务入口。

**链路已齐**（切片 #100/#101/#103 之后）：

- **硬红线预检**：判据在 `knowledge_red_flag`，命中即判红且**不调模型**（ADR-0021）；
- **知识检索**：`knowledge.search` 跑三层（L1 结构化事实 / L2 关系层 / L3 ngram 关键词检索，ADR-0022），
  检索到的条目进模型上下文，模型引用哪条由**引用校验**（`_review_citations`）核一遍，
  最后以 `citations` 出给 C 端——**没有引用就不许说「基于知识库」**（ADR-0028 的口径）；
  向量层挂起未实现（重开条件见 ADR-0022）；
- **运营可调项**：提示词（带版本号与灰度）、分级规则、护栏词表、降级开关都从库里读、可即时生效
  （ADR-0010），读不到时回落到代码基线并留痕（`ops.available=False`）；
- **语音**：当前供应商没有转写端点；**图片可用**（走 `ai_vision_model`，实测 flash 能读图、
  pro 不能），没配视觉模型时才降级并告知。

三条贯穿全链路的纪律（改动前先读，它们都有测试钉着）：

1. **不向用户报错**：模型不可用、检索读不到知识域、护栏命中——一律 HTTP 200 + 一句保守中文
   （交付文档 2.4 / ADR-0026）。降级的机器可读原因在 `degrade_code`，内部明细在 `degrade_detail`。
2. **检索失败不影响回答**：知识域读不到、或检索开关的配置读不到，都记 `retrieval_check=unavailable`
   并照常回答，只是**没有来源**（ADR-0033 / ADR-0050 §三）。红线则相反——读不到要让调用方知道
   （那是安全网）。
3. **每一层都留痕**：`red_flag_check` / `retrieval_check` / `ops_config_check` / `guard_hits` /
   `citations` / `prompt_version` 都在响应里，事后归因分级漂移靠它们。
4. **「读不到」与「人为关闭」必须分开上报**（ADR-0050 §三）——三处的口径：
   运营配置读不到 → `ops_config_check=unavailable`（**不是降级**，回落代码基线后照常回答）；
   两个开关读不到 → `guard_hits` 里出现 `switch:<代码>:unreadable`，且**兜底方向按开关性质定**
   （`retrieval_enabled` 不启用检索；`force_rule_only` 照样调模型，理由见 `consult` 的注释）；
   红线词表**读到但为空**与读不到都算这一层没生效（测试报告 D5 的取舍保留），成因靠日志分。

链路设计见 `docs/design/ai-service.md` 第 3 节；护栏与留痕字段见同文第 5 节。
"""

import logging
from dataclasses import dataclass

from fastapi import Depends, FastAPI, Header, HTTPException

from . import guardrails, knowledge, model_client, ops, prompts, red_flags
from .config import settings
from .models import (
    Citation,
    ConsultRequest,
    ConsultResponse,
    KnowledgeBrowseItem,
    KnowledgeBrowseRequest,
    KnowledgeBrowseResponse,
    SymptomMatchItem,
    SymptomMatchRequest,
    SymptomMatchResponse,
)
from .ops import SWITCH_FORCE_RULE_ONLY, SWITCH_RETRIEVAL_ENABLED, SWITCH_RETRIEVAL_STRICT

# 关掉交互式文档与 openapi.json：那张表把内部接口的请求结构免费送给任何能访问到这个进程的人，
# 而这正是 require_internal_token 的注释里说「不能让未鉴权调用方探查」的东西（测试报告 D17）。
app = FastAPI(title="pet-health-ai", version="0.3.0",
              docs_url=None, redoc_url=None, openapi_url=None)

logger = logging.getLogger("pet_health_ai")

#: 模型不可用时的保守建议：不分级、直接建议就医。医疗场景宁严勿松（docs/conventions.md）。
DEGRADED_SUGGESTION = "AI 服务暂时不可用，为避免耽误，建议尽快咨询兽医；情况紧急请直接送医。"

#: 纯规则通道（运营把 `force_rule_only` 打开）时的建议：**明确说这是规则给的**，
#: 不含任何「AI 判断」的说法——那句话在模型没参与时给不出对应物（ADR-0028 的口径）。
RULE_ONLY_SUGGESTION = "当前仅启用平台的规则判断：请按提示观察，出现急症表现立即送医；不确定时尽快咨询兽医。"

#: 检索为空且运营开了严格口径时的答复（#101 的「检索为空不生成」）。
EMPTY_KNOWLEDGE_SUGGESTION = (
    "平台知识库暂时没有覆盖到这个情况，为避免误导，这里不给推测结论；"
    "建议咨询兽医，情况紧急请直接送医。"
)


def _log_alert(reason: str, trace_id: str, detail: object) -> None:
    """留一条需要人工关注的 WARN。

    记的是**三类**事件，`reason` 就是分类：降级（模型不可用、图片没送到…）、输出护栏改写
    （模型越界了），以及**配置读不到**（运营配置 / 开关——它不是降级，回答照常，但同样要有人看）。
    原先这个函数叫 `_log_degraded`，护栏命中也被写成 `degraded reason=output_guardrail`——
    读日志的人会以为发生了一次降级，而其实模型答得好好的、是我们改写了它的话。
    """
    logger.warning("consult alert reason=%s trace_id=%s detail=%s", reason, trace_id, detail)


@dataclass(frozen=True)
class _ConfigMarks:
    """随响应带出去的**配置读取状态**（ADR-0050 §三）。

    打成一个对象传而不是两个散参数，是为了让「漏传」在构造期就看得见：每个响应分支都必须
    带上它，漏一处就等于那条路径静默报「一切正常」——而「静默报正常」正是这一轮要修的缺陷。
    """

    #: 运营可调项这一批读到了没有：ok / unavailable（进 `ConsultResponse.ops_config_check`）
    ops_config_check: str
    #: 哪些开关读不到（`switch:<代码>:unreadable`），进 `guard_hits`
    switch_marks: tuple[str, ...] = ()

    def with_switch_marks(self, hits: list[str]) -> list[str]:
        """把开关读不到的标记并进护栏留痕。**只加读不到的**：确实为关的开关不占位置。"""
        return hits + list(self.switch_marks)


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
    marks: _ConfigMarks,
    care_tips: list[str] | None = None,
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    retrieval_check: str = "skipped",
    citations: list[Citation] | None = None,
    grading_rule_hits: list[str] | None = None,
) -> ConsultResponse:
    """降级答复的统一构造。几个分支同形状，收在一处免得「改了码忘了明细」。

    `degrade_detail` 里可能带异常类名、上游原文、甚至**模型返回的原始文本**：
    它只进日志与留痕（Java 侧落 `ai_consult.degrade_reason`），**不进用户可见文案**——
    用户看到的是一句按 `degrade_code` 映射的中文（测试报告 D6）。

    降级答复里**不带 citations**：结论不是基于那些条目给的，带上来源等于给一句固定话术
    伪造出处（ADR-0028 的口径：没有来源就不许说有）。
    """
    return ConsultResponse(
        risk_level=risk_level,
        action_suggestion=action_suggestion,
        need_hospital=True,
        degraded=True,
        degrade_code=code,
        degrade_detail=f"{type(detail).__name__}: {detail}",
        care_tips=care_tips or [],
        citations=citations or [],
        images_used=0,
        # 降级也可能已经花过钱（修复重试先成功一次再失败）：异常捎带的用量照记，
        # 不然预算会系统性偏低（ADR-0026）
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        red_flag_check="ok" if rule_set.available else "unavailable",
        retrieval_check=retrieval_check,
        # 降级也要如实报「这一轮配置读到了没有」：模型那条腿断了不代表配置是好的，
        # 反过来也一样（两件事分开看，ADR-0033 对 retrieval_check 的口径同样适用）
        ops_config_check=marks.ops_config_check,
        grading_rule_hits=grading_rule_hits or [],
        guard_hits=marks.with_switch_marks([]),
        model_name=settings.ai_model_grading,
        prompt_version=settings.prompt_version,
    )


def _red_flag_response(
    req: ConsultRequest,
    hits: list[red_flags.RedFlagHit],
    image_count: int,
    rule_set: red_flags.LoadResult,
    marks: _ConfigMarks,
) -> ConsultResponse:
    """红线命中时的响应：一句话给动作，其余命中项作为补充事项。

    `model_name` 标成 `rule:red_flag` 而不是留空——留痕里必须能看出**这次没有模型参与**，
    否则事后分析分级准确率时会把规则判定混进模型样本里（ADR-0021）。
    `retrieval_check` 记 `skipped`：红线短路发生在检索之前，检索这一层根本没跑过，
    记成 ok/empty 都会让人以为「检索了但没召回到」。

    这条路径上开关**一个都没被读过**（不调模型、不检索），所以 `switch_marks` 不加进来——
    没读过的开关谈不上被误读，报它只会让留痕里多一条查不出所以然的记录。
    `ops_config_check` 照记：它报的是**这一轮配置加载的总体事实**，与这条路径用没用无关。
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
        retrieval_check="skipped",
        ops_config_check=marks.ops_config_check,
        red_flag_hits=[hit.code for hit in hits],
        # 命中详情带给 Java 侧留痕：哪条规则、命中的是哪个词
        guard_hits=[f"{hit.code}:{hit.term}" for hit in hits],
        degraded=False,
        model_name="rule:red_flag",
        prompt_version=settings.prompt_version,
    )


#: 哪几种异常代表「降级」（其余异常照旧 500，让调用方知道是 bug 而不是模型问题）。
_DEGRADABLE = (
    model_client.VisionUnavailable,
    model_client.ImageUnavailable,
    model_client.ModelUnavailable,
    model_client.ModelOutputInvalid,
)


@dataclass(frozen=True)
class _DegradeSpec:
    """一条降级路径的全部差异（原先散在四个 except 分支里，改一处要记得改四处）。"""

    code: str
    risk_level: int
    suggestion: str
    #: 文案里要不要提「收到了 N 张图」
    mentions_images: bool
    #: 异常是否捎带了真实用量（见 model_client._UsageCarrying）
    carries_usage: bool


#: 降级路径 → 参数。风险等级是刻意的：图片相关问题降成黄（2，还能用文字补），
#: 模型输出不可用拔到红（3，「不知道」比「猜」安全）。
_DEGRADE_SPECS: dict[type[BaseException], _DegradeSpec] = {
    # 有图但当前没有能看图的模型。**明确告知**，不做静默忽略——
    # 用户以为模型看过照片、其实没看，比直接说不支持危险得多。
    model_client.VisionUnavailable: _DegradeSpec(
        code="image_not_supported",
        risk_level=2,
        suggestion="暂时无法分析图片，请把症状用文字补充清楚（部位、多久、有没有变化）；"
        "如情况紧急请直接送医。",
        mentions_images=True,
        carries_usage=False,
    ),
    # 有能看图的模型，但图片没取回来（签名过期、后端不可达、体积超限）。
    # 与上一类分开留痕：一个是「模型不能看图」，一个是「我们没把图送到」。
    model_client.ImageUnavailable: _DegradeSpec(
        code="image_unavailable",
        risk_level=2,
        suggestion="这次没能读到你的图片，请先用文字描述症状（部位、多久、有没有变化）；"
        "如情况紧急请直接送医。",
        mentions_images=True,
        carries_usage=False,
    ),
    # 传输层挂了：降级成保守建议，不向用户报错（设计文档第 5 节的硬要求）
    model_client.ModelUnavailable: _DegradeSpec(
        code="model_unavailable",
        risk_level=2,
        suggestion=DEGRADED_SUGGESTION,
        mentions_images=False,
        carries_usage=True,
    ),
    # 模型答了但没法用（没调工具、参数越界）。按设计：风险拔高一档更安全
    model_client.ModelOutputInvalid: _DegradeSpec(
        code="model_output_invalid",
        risk_level=3,
        suggestion=DEGRADED_SUGGESTION,
        mentions_images=False,
        carries_usage=True,
    ),
}


def _degrade(exc: BaseException, req: ConsultRequest, rule_set: red_flags.LoadResult,
             image_count: int, retrieval_check: str, marks: _ConfigMarks) -> ConsultResponse:
    """把一条降级异常翻译成给用户的答复（顺带留一条 WARN）。

    `retrieval_check` 由调用方带进来：模型这条腿断了不代表检索没跑过，
    留痕里两件事要能分开看（ADR-0033）。
    """
    spec = _DEGRADE_SPECS[type(exc)]
    _log_alert(spec.code, req.trace_id, exc)
    prompt_tokens, completion_tokens = (
        (getattr(exc, "prompt_tokens", 0), getattr(exc, "completion_tokens", 0))
        if spec.carries_usage else (0, 0)
    )
    return _degraded_response(
        risk_level=spec.risk_level,
        action_suggestion=spec.suggestion,
        code=spec.code,
        detail=exc,
        rule_set=rule_set,
        marks=marks,
        care_tips=[f"已收到 {image_count} 张图片，但本轮没有分析它们。"] if spec.mentions_images else None,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        retrieval_check=retrieval_check,
    )


def _review_citations(items: list[str], allowed: set[str]) -> tuple[list[str], list[str], int]:
    """引用校验：剔除**引用了不在召回集里的编号**的结论（切片 #101 的验收标准第三条）。

    三件事分开处理，因为它们的严重程度不同：

    - **引用了编造的编号，且没有别的有效编号** → 整条剔除（`fabricated`）。这条结论把自己的
      来源指向了一个不存在的条目，比「没有来源」更糟——用户会以为它有出处。
    - **引用了有效编号 + 编造编号混着** → 只洗掉编造的编号，结论保留。
    - **一个编号都没有** → 保留，但计入 `unbacked`（无来源结论数）。**默认不因此降级**：
      当前语料只有几十条，强制每条结论都带来源会让回答频繁变成空壳（理由与待澄清见 ADR-0033）；
      运营可以打开 `retrieval_strict` 让它变成降级。
    """
    kept: list[str] = []
    removed: list[str] = []
    unbacked = 0
    for text in items:
        cleaned, gone = knowledge.strip_invalid_refs(text, allowed)
        removed.extend(gone)
        has_ref = bool(knowledge.REF_PATTERN.search(cleaned))
        if gone and not has_ref:
            removed.append("fabricated")
            continue
        if not has_ref:
            unbacked += 1
        if cleaned.strip():
            kept.append(cleaned.strip())
    return kept, removed, unbacked


@app.post(
    "/internal/knowledge/browse",
    dependencies=[Depends(require_internal_token)],
)
async def knowledge_browse(req: KnowledgeBrowseRequest) -> KnowledgeBrowseResponse:
    """C 端知识库浏览（F024 / F025）：按分类翻、按关键词搜，或按编号取单条。

    **与 `/internal/consult` 的检索是两件事**：检索是「这次咨询该引用什么」（排序、安全门、关系层召回），
    浏览是「知识库里有什么」（按分类列出来）。所以这一层不做打分与禁忌过滤——
    安全门管的是「不要把禁忌项推荐给这个物种」，而浏览是用户自己翻。
    中文匹配仍走同一套 ngram 短语（`build_boolean_query`），两种入口不该有两种中文搜索行为。

    读不到知识库时返回 `knowledge_check=unavailable` + 空列表（HTTP 200）：与检索的降级同一条口径。
    """
    items, total, state = knowledge.browse(
        category_code=req.category_code,
        keyword=req.keyword,
        code=req.code,
        page=req.page,
        page_size=req.page_size,
    )
    return KnowledgeBrowseResponse(
        items=[
            KnowledgeBrowseItem(
                code=item.code,
                title=item.title,
                summary=item.summary,
                # 列表不下发正文（契约里 body 只在详情有值）：省一次几 KB 的传输，也让「点进去」有意义
                body=item.body if req.code else "",
                category_code=item.category_code,
                category_name=item.category_name,
                risk_hint=item.risk_hint,
                review_status=item.review_status,
                source_title=item.source_title,
                source_url=item.source_url,
            )
            for item in items
        ],
        total=total,
        knowledge_check=state,
    )


@app.post(
    "/internal/symptom-match",
    dependencies=[Depends(require_internal_token)],
)
async def symptom_match(req: SymptomMatchRequest) -> SymptomMatchResponse:
    """F011「AI 帮我找服务」的**症状识别**（规则版，ADR-0050 第二节）。

    这一层只回答「这句话里有哪些受控症状词」——**不推荐项目、不调模型**：
    症状到目录项的映射是业务可调项，落在 Java 侧的表里（`catalog_symptom_rule`）。
    这样切与知识层的边界一致：`knowledge_*` 只有这里能读，而目录与映射归业务侧。

    读不到词典返回 `knowledge_check=unavailable` + 空列表（HTTP 200）：
    与检索的降级同一条口径——「这次认不出」不该变成一次报错（ADR-0026）。
    """
    hits, state = knowledge.match_symptoms(req.text, species=req.species, age_stage=req.age_stage)
    return SymptomMatchResponse(
        matches=[
            SymptomMatchItem(
                symptom=hit.symptom,
                entry_code=hit.entry_code,
                title=hit.title,
                risk_hint=hit.risk_hint,
            )
            for hit in hits
        ],
        knowledge_check=state,
    )


@app.post(
    "/internal/consult",
    response_model=ConsultResponse,
    dependencies=[Depends(require_internal_token)],
)
async def consult(req: ConsultRequest) -> ConsultResponse:
    """咨询主链路：红线 → 分级规则 → 检索 → 模型 → 抬档 → 护栏 → 引用。

    **每一步的先后都是结论的一部分**，别按「看起来更顺」重排：

    - **红线是唯一的短路**：命中即返回、连模型都不调（`_red_flag_response`）。
      除它之外还有三处提前返回，都在下面各自的注释里写明条件：严格口径下的「检索为空」
      与「有 vetted 条目却一条都没被引用」，以及运营拉了电闸的纯规则通道。
    - **分级规则只抬不降**：它算的是风险**下限**，在模型之后才套到结论上（`_apply_escalation`），
      任何分支都不许用它把档位调低。
    - **检索失败不影响回答**：知识域或检索开关读不到，只改 `retrieval_check` 与日志，
      照常回答、只是没有来源（ADR-0033）；开关「读不到」与「人为关闭」的上报语义在这里分开
      （ADR-0050 §三）。
    - **护栏排在抬档之后**：规则给的建议和模型的话是同一批用户可见文案，要过同一道护栏，
      否则运营填一句「建议用药三天」就绕过了 9.5 的三个绝不。
    - **引用最后算**：`citations` 要同时满足「真被引用」与「条目 vetted」，而文案要等护栏改写
      （带编号的整句可能被整段换掉）与抬档补句都走完才定型——先算引用等于把已经被改写的编号
      算成来源（ADR-0028：没有来源就不许说有）。

    所有分支都返回 HTTP 200（降级走 `_degraded_response`），只有 `_DEGRADABLE` 之外的异常才让它
    500——那是 bug，不是模型问题。
    """
    images = req.input.media_urls[: settings.ai_max_images]
    image_count = len(images)

    # ---- 运营可调项：提示词（含版本号与灰度）、分级规则、护栏词表、开关（ADR-0010 / #103）----
    # 放在最前面读：后面每一步都要用它。读不到会回落到代码基线，**不让咨询失败**。
    ops_config = ops.load()
    # 这一批读到了没有、以及哪两个开关的值没读到：一次算清，之后每个响应分支都带上它们
    # （漏传一处 = 那条路径静默报「一切正常」，所以 `_ConfigMarks` 没有默认值）。
    marks = _ConfigMarks(
        ops_config_check="ok" if ops_config.available else "unavailable",
        switch_marks=tuple(ops_config.switch_unreadable_marks(
            SWITCH_RETRIEVAL_STRICT, SWITCH_FORCE_RULE_ONLY)),
    )
    # 读不到**不是降级**——回落代码基线之后回答照常（ADR-0010），所以 `degraded` 不变。
    # 但它必须是一个**可观测的事实**：留痕里报 `ops_config_check=unavailable`，日志里留一条
    # WARN（含是哪一项、为什么读不到）。没有这一条，「服务在跑而运营配置没生效」只能靠
    # 翻库才发现——`OpsConfig.available` / `detail` 原先只有 ops 内部的一条 WARNING，没有出口。
    if marks.ops_config_check != "ok":
        _log_alert("ops_config_unavailable", req.trace_id, ops_config.detail)

    # 两个「读不到按关闭算」的开关：**值照旧**（fail-closed 的方向不变），但「这个值是不是
    # 读到的」要留痕——`switch()` 会把「运营关的」与「读不到」压成同一个 False，
    # 那正是这轮要修的缺陷（ADR-0050 §三）。
    strict_state = ops_config.switch_state(SWITCH_RETRIEVAL_STRICT)
    force_state = ops_config.switch_state(SWITCH_FORCE_RULE_ONLY)
    if not strict_state.known:
        # 严格口径读不到 → 按关闭算：不启用「检索为空不生成」。
        # 这里**保持现状**而不是 fail-closed 到「开」：默认值本来就是关，故障时把它打开
        # 会让每一次咨询都变成固定话术（ADR-0033 的取舍），而那是一次故障引发的全局降级。
        _log_alert("switch_unreadable", req.trace_id,
                   f"{SWITCH_RETRIEVAL_STRICT} 读不到（运营配置不可用）：按关闭算，不启用严格口径")
    if not force_state.known:
        # 电闸读不到 → 照常调模型（判断与代价见下面那段注释）
        _log_alert("switch_unreadable", req.trace_id,
                   f"{SWITCH_FORCE_RULE_ONLY} 读不到（运营配置不可用）：按关闭算，照常调模型")

    # ---- 硬红线预检：命中即判红，**不调模型**（ADR-0021）----
    # 排在检索与模型之前有两个理由：它判的是分钟级急症，早一次跳转就少一段时间；它还很便宜，
    # 命中时省掉一次模型调用与一次检索。
    rule_set = red_flags.load_rules()
    age_stage = red_flags.age_stage_of(req.pet.birth_date, req.pet.species)
    hits = red_flags.match(req.input.text, rule_set.rules, species=req.pet.species, age_stage=age_stage)
    if hits:
        return _red_flag_response(req, hits, image_count, rule_set, marks)

    # ---- 分级规则（#103）：算出风险**下限**，与红线不同，它不短路、只是兜底 ----
    escalation = ops.escalate(req.input.text, ops_config.grading_rules, req.pet.species, age_stage)

    # ---- 知识检索（#100/#101）：L1 结构化 + L2 关系层 + L3 ngram 关键词 ----
    # **读不到知识域不影响回答**：记 retrieval_check=unavailable，按「无来源的通用建议」继续。
    # 开关取 `switch_state` 而不是 `switch`：**读不到**要报 unavailable（故障、要告警），
    # 只有开关**确实为关**才报 disabled（人为动作）——混起来故障就永远没人查（ADR-0050 §三）。
    retrieval_gate = ops_config.switch_state(SWITCH_RETRIEVAL_ENABLED)
    context = knowledge.search(
        req.input.text,
        species=req.pet.species,
        age_stage=age_stage,
        enabled=retrieval_gate.enabled,
        enabled_known=retrieval_gate.known,
    )
    if context.status == "unavailable":
        _log_alert("retrieval_unavailable", req.trace_id, context.detail)
    if context.flags:
        # 被安全门剔除 / 被护栏清洗的条目：这批数据要有人看（多半是种子内容需要复核）
        _log_alert("knowledge_filtered", req.trace_id, list(context.flags))

    # 严格口径（#101 的「检索为空不生成」）默认**关闭**：当前语料只有几十条，
    # 开它会把这批咨询全变成固定话术，与 ADR-0025 对「不熔断」的取向冲突。理由见 ADR-0033。
    # 读 `strict_state.enabled` 而不是 `switch()`：值一样，但「读到没有」已经在上面留痕，
    # 下面不必再判一次（判两次迟早会出现两处口径不一致）。
    if strict_state.enabled and context.status == "empty":
        return _degraded_response(
            risk_level=2,
            action_suggestion=EMPTY_KNOWLEDGE_SUGGESTION,
            code="knowledge_empty",
            detail=context.detail,
            rule_set=rule_set,
            marks=marks,
            retrieval_check=context.status,
            grading_rule_hits=list(escalation.codes),
        )

    # ---- 运营把电闸拉了：不调模型，只走规则通道（ADR-0010 的降级开关）----
    # **读不到时照常调模型**（`known=False` 拿到的 `enabled=False` 只是兜底值，不是运营的选择）。
    # 这一处与检索开关的方向**故意不同**，因为两个方向的错误代价不对称：
    #
    # - 把「读不到」读成「运营拉了闸」→ 一次配置故障就**静默撤掉整条模型通道**：所有咨询都
    #   变成一句固定话术 + 规则建议（连「可能原因」都没有），而纯规则通道本身是一种合法的
    #   工作模式，留痕（`model_name=rule:switch`）与用户侧看起来都像正常运营动作，没人会去查。
    #   这就是「故障冒充人为动作」最贵的一种形态（ADR-0050 §三禁的正是它）。
    # - 把「读不到」读成「没拉闸」→ 多花一次**本来就在基线里**的模型调用。`force_rule_only`
    #   是成本/质量开关，不是安全网：调模型这条路上红线预检、分级规则抬档、输出护栏照常跑，
    #   所以「照常调模型」不会让急症漏判，代价有界（一次调用），而且现在有 WARN + 留痕可查。
    #
    # 结论：按「运营的意图不可知」处理——维持现状（调模型）+ `guard_hits` 里的
    # `switch:force_rule_only:unreadable` 标记 + 一条 `switch_unreadable` 的 WARN。
    # ADR-0050 §三 的 fail-closed 适用于「读不到时少一层输入」这类开关（检索开关），
    # 不适用于「读不到时少一整条能力通道」。
    if force_state.enabled:
        return _rule_only_response(req, escalation, rule_set, context, marks)

    prompt = ops_config.prompt
    user_prompt = prompts.build_user_prompt(
        pet=req.pet.model_dump(),
        text=req.input.text,
        history=req.history,
        image_count=image_count,
        # **已过安全门与输出护栏**的条目（knowledge._filter）；排版见 prompts.render_knowledge
        knowledge=list(context.items),
    )

    try:
        result = await model_client.assess(
            system_prompt=prompt.system_prompt,
            user_prompt=user_prompt,
            images=images,
            tool=prompt.tool_schema,
        )
    except _DEGRADABLE as exc:
        return _degrade(exc, req=req, rule_set=rule_set, image_count=image_count,
                        retrieval_check=context.status, marks=marks)

    # 留痕：trace_id 从 Java 一路带过来，这里落日志，模型侧出问题才追得回去（ADR-0009）
    # `kb_vetted` / `kb_unvetted` 分开记：前者才能进 citations，后者必须带「未复核」提示
    logger.info(
        "consult trace_id=%s user_id=%s model=%s risk=%s images=%s degraded=false latency_ms=%s "
        "prompt=%s retrieval=%s kb_vetted=%s kb_unvetted=%s",
        req.trace_id, req.user_id, result.model_name, result.risk_level,
        image_count, result.latency_ms, prompt.version, context.status,
        len(context.vetted_codes()), len(context.unvetted_codes()),
    )

    # ---- 引用校验：剔除不在召回集里的编号（#101）----
    # 允许集就是本轮**真正注入了**的条目编号；模型编一个 K-9999 出来会被这里拦掉。
    allowed = set(context.codes())
    causes, cause_removed, cause_unbacked = _review_citations(list(result.possible_causes), allowed)
    action, action_removed = knowledge.strip_invalid_refs(result.action_suggestion, allowed)
    care, care_removed, _ = _review_citations(list(result.care_tips), allowed)
    # 工具参数里的 citations 也要过同一道校验：它是「本次回答的来源」这一栏的第二来源
    model_citations = {code for code in result.citations if code in allowed}
    claimed = set(knowledge.REF_PATTERN.findall("\n".join(causes + [action] + care))) | model_citations

    # 严格口径 + 本轮有 vetted 条目可引用、却一条**已复核**引用都没有 → 降级（#101 的第四条）。
    # 它只可能被运营打开（默认关），理由见 ADR-0033：种子里一条 vetted 都没有，
    # 默认开会让每一次咨询都变成固定话术。
    # 严格口径 + 本轮有 vetted 条目可引用、却一条**已复核**引用都没有 → 降级（#101 的第四条）。
    # 它只可能被运营打开（默认关），理由见 ADR-0033：种子里一条 vetted 都没有，
    # 默认开会让每一次咨询都变成固定话术。
    if strict_state.enabled and context.vetted_codes() and not (claimed & set(context.vetted_codes())):
        return _degraded_response(
            risk_level=max(2, result.risk_level),
            action_suggestion=EMPTY_KNOWLEDGE_SUGGESTION,
            code="knowledge_empty",
            detail="本轮没有一条结论能追溯到已复核的知识条目",
            rule_set=rule_set,
            marks=marks,
            retrieval_check=context.status,
            grading_rule_hits=list(escalation.codes),
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )

    # ---- 分级规则抬档：结论不低于命中规则的下限（#103）----
    # **排在护栏之前**：规则给的建议也是用户可见文案，必须和模型的话过同一道护栏
    # （否则运营填一句「建议用药三天」就绕过了 9.5 的三个绝不——测试钉着这条）。
    risk_level, care = _apply_escalation(result.risk_level, escalation, care)

    # ---- 输出层护栏：绝不输出确诊 / 处方 / 剂量（交付文档 9.5）----
    # 提示词是概率性的，这里是机械的。命中就改写并留痕，不静默删除（ADR-0021 第四条）。
    causes, cause_hits = guardrails.review_list(causes)
    action, action_hits = guardrails.review(action)
    care, care_hits = guardrails.review_list(care)
    guard_hits = cause_hits + action_hits + care_hits
    # 检索侧的痕迹也进 guard_hits：安全门剔除的条目、被护栏清洗的条目、编造/缺失的引用
    guard_hits += [f"kb:flag:{flag}" for flag in context.flags]
    guard_hits += [f"citation:{code}" for code in (cause_removed + action_removed + care_removed)]
    if cause_unbacked:
        guard_hits.append(f"citation:unbacked:{cause_unbacked}")
    if guard_hits:
        _log_alert("output_guardrail", req.trace_id, guard_hits)
    # 开关读不到的标记**排在最后**（上面那条 WARN 是「模型/护栏出了问题」，与配置无关，
    # 混在一起会让读日志的人以为模型越界了）。它进留痕而不是只在日志里：这条事实要能被
    # 按 trace_id 事后查到——「这一轮调了模型，而电闸的值当时没读到」正是要能回答的问题。
    guard_hits = marks.with_switch_marks(guard_hits)

    if risk_level >= 3 and not action:
        # 抬到红却没有可用建议（原建议被护栏整段改写）：安全方向补一句明确的送医
        action = "出现急症信号的可能，请立即送医，不要在家观察。"

    return ConsultResponse(
        risk_level=risk_level,
        possible_causes=causes,
        action_suggestion=action,
        need_hospital=result.need_hospital or risk_level >= 2,
        care_tips=care,
        # 只带**vetted 且真被引用**的条目：召回集与引用集是两件事，而「没复核过的不许当依据」
        # 是这条口径的硬约束（ADR-0033）。未复核的那些走下面的 unvetted_hits 留痕 + 文案提示。
        citations=_cited_citations(causes + [action] + care, context, extra=model_citations),
        unvetted_hits=list(context.unvetted_codes()),
        # 本轮模型实际看了几张图。留痕用：事后归因分级漂移时要能区分「当时有图」和「当时没图」
        images_used=image_count,
        guard_hits=guard_hits,
        red_flag_check="ok" if rule_set.available else "unavailable",
        retrieval_check=context.status,
        ops_config_check=marks.ops_config_check,
        grading_rule_hits=list(escalation.codes),
        degraded=False,
        model_name=result.model_name,
        model_version=result.model_version,
        prompt_version=prompt.version,
        latency_ms=result.latency_ms,
        # 真实用量：日预算告警与「分级漂移」归因都看它（ADR-0026）
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
    )


def _apply_escalation(risk_level: int, escalation: ops.Escalation, care: list[str]) -> tuple[int, list[str]]:
    """把分级规则的下限套到模型结论上。

    抬到红时**把规则给的建议放到第一位**：模型可能给了「再观察一天」这类措辞，
    而规则说这属于急症——用户先看到的那一句必须是「立即送医」（宁严勿松）。
    抬到黄及以下只追加一条观察建议，不动模型的原话（它的措辞通常更贴合具体症状）。

    **只抬等级，不动 `need_hospital`**：那个字段由调用方按最终等级或上（`risk_level >= 2`），
    两处都改会出现「谁最后写的」这种查不出的分歧。
    """
    if escalation.level <= risk_level:
        return risk_level, care
    if escalation.level >= 3:
        tips = ([escalation.advice] if escalation.advice else []) + care
        return escalation.level, tips
    tips = care + ([escalation.advice] if escalation.advice and escalation.advice not in care else [])
    return escalation.level, tips


def _cited_citations(texts: list[str], context: knowledge.Context,
                     extra: set[str] | None = None) -> list[Citation]:
    """把「真的被引用到的编号」翻成 C 端要的来源信息。

    两个来源合并：**文案里的行内编号**（`[K-0010]`）+ **工具参数里那份自报编号**
    （已经过召回集校验）。两者是同一件事的两种写法，缺任一个都会让「模型确实引了、
    但界面上没来源」这种自相矛盾的情况出现。

    两道过滤，缺一不可：

    - **必须真被引用**：用户看到的「来源」必须与结论对得上，否则又回到「声称有来源、
      实际没有」（ADR-0028 的口径）；
    - **必须是 vetted**：未复核的条目不许出现在来源列表里——那条口径是「没经兽医复核的
      内容不许被当作依据」，而「列在来源里」就是把它当依据展示（ADR-0033）。

    顺序按召回排序给，让最相关的那条排前面。
    """
    referenced = set(knowledge.REF_PATTERN.findall("\n".join(texts)))
    referenced.update(extra or ())
    return [Citation(**item.citation()) for item in context.items if item.vetted and item.code in referenced]


def _rule_only_response(
    req: ConsultRequest,
    escalation: ops.Escalation,
    rule_set: red_flags.LoadResult,
    context: knowledge.Context,
    marks: _ConfigMarks,
) -> ConsultResponse:
    """纯规则通道的答复（运营把 `force_rule_only` 打开时）。

    这条路径**没有模型参与**，所以：`model_name` 标 `rule:switch`（留痕里一眼分得清），
    引用一律为空（结论不是从知识条目推出来的），风险等级取规则下限与「黄」的较大者——
    关掉模型不等于可以把用户按绿放回家（宁严勿松）。

    能走到这里说明电闸的值**确实读到了**（读不到会走「照常调模型」那条路，见 `consult`），
    所以 `guard_hits` 里不会有 `switch:force_rule_only:unreadable`；但 `retrieval_strict`
    的值可能没读到，标记照样带出去。
    """
    risk_level = max(2, escalation.level)
    tips = [escalation.advice] if escalation.advice else []
    logger.info(
        "consult rule_only trace_id=%s user_id=%s rules=%s retrieval=%s",
        req.trace_id, req.user_id, list(escalation.codes), context.status,
    )
    return ConsultResponse(
        risk_level=risk_level,
        possible_causes=[],
        action_suggestion=RULE_ONLY_SUGGESTION,
        need_hospital=True,
        care_tips=tips,
        images_used=0,
        red_flag_check="ok" if rule_set.available else "unavailable",
        retrieval_check=context.status,
        ops_config_check=marks.ops_config_check,
        grading_rule_hits=list(escalation.codes),
        guard_hits=marks.with_switch_marks([]),
        degraded=False,
        model_name="rule:switch",
        prompt_version=settings.prompt_version,
    )
