"""模型调用的唯一出口。

只有这一个文件直接跟模型供应商说话。上层（`main.py`）拿到的是校验过的结构化结果，
永远不接触 HTTP、JSON 解析或重试细节。

四条实测得来的纪律（对应 [ADR-0017](../../docs/adr/0017-model-provider-deepseek.md)）：

1. **输出预算给足**。当前模型是推理型，reasoning 会先吃掉预算；给少了返回
   `finish_reason=length` + 空 `content`，看起来像「模型不说话」——**别据此判断能力**，
   我踩过这个坑：曾以为 flash 看不见图片，其实是预算被 reasoning 吃光了。
2. **图片要走支持视觉的模型**。`ai_vision_model` 为空 = 当前没有能看图的模型，
   带图请求由调用方降级——不要硬发给看不见图的模型，那会得到「无法确定」这种看似正常的错误答案。
3. **工具调用只能 `tool_choice=auto`**。思考模式拒绝强制指定（HTTP 400），
   所以「模型没调工具」是正常分支，要重试一次而不是当成崩溃。
4. **图片必须先取回本地再内联**。签名读地址指向我们自己的后端，供应商拉不到；
   直接把地址交出去会得到一个「看起来正常、其实没看图」的答案（`inline_images` 有说明）。
5. **失败一律降级、不向上抛错**。模型不可用时用户拿到的是保守建议，不是 500；
   连「200 但不是 JSON」「tool_calls 缺字段」这类形状异常也要降级，不能变成 500。
"""

import base64
import json
import time
from dataclasses import dataclass, field

import httpx

from .config import settings
from .prompts import REPORT_TOOL


class _UsageCarrying(Exception):
    """带 token 用量的异常基类。

    为什么异常要捎带用量：修复重试可能先成功一次再失败——**那一次的钱是真花了**。
    只在成功返回时带用量的话，预算会系统性偏低（花掉的不进账单，告警就不准）。
    """

    def __init__(self, *args: object, prompt_tokens: int = 0, completion_tokens: int = 0) -> None:
        super().__init__(*args)
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens


class ModelUnavailable(_UsageCarrying):
    """传输层失败：超时、连不上、非 2xx、响应体根本不是 JSON。调用方据此降级。"""


class VisionUnavailable(Exception):
    """带图请求但当前没有能看图的模型。调用方据此明确降级并告知用户。"""


class ImageUnavailable(Exception):
    """有图、也有能看图的模型，但**图片取不回来**（签名地址失效、后端不可达、体积超限）。

    与 `VisionUnavailable` 分开：那一个是「模型不能看图」，这一个是「我们没把图送到」，
    事后归因要能区分（测试报告 D7）。
    """


class ModelOutputInvalid(_UsageCarrying):
    """模型返回了，但内容不能用（没调工具 / 参数不是合法 JSON / 取值越界）。"""


@dataclass
class TriageResult:
    """校验过的分级结果 + 留痕字段。"""

    risk_level: int
    possible_causes: list[str] = field(default_factory=list)
    action_suggestion: str = ""
    need_hospital: bool = False
    care_tips: list[str] = field(default_factory=list)
    model_name: str = ""
    model_version: str = ""
    latency_ms: int = 0
    #: 本轮实际消耗的 token（供应商在响应里给 `usage`）。日预算告警要用它（ADR-0026）
    prompt_tokens: int = 0
    completion_tokens: int = 0


def _validate_arguments(raw: str) -> dict:
    """把工具参数解析并校验成我们认得的形状。越界一律当无效，宁可降级也不猜。"""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ModelOutputInvalid(f"工具参数不是合法 JSON：{raw[:120]}") from exc

    if not isinstance(data, dict):
        raise ModelOutputInvalid("工具参数不是对象")

    risk_level = data.get("risk_level")
    # **`bool` 要单独挡住**：Python 里 `True == 1`，`True in (1, 2, 3)` 是 True——
    # 模型回 `{"risk_level": true}` 会被当成「绿」放行。分级是安全相关的字段，
    # 宁可降级也不能让一个类型错误的分级走到用户面前（测试报告 D4）。
    # 同理拒掉 `2.0` / `"3"`：契约里它就是一个 1..3 的整数。
    if isinstance(risk_level, bool) or not isinstance(risk_level, int) or risk_level not in (1, 2, 3):
        raise ModelOutputInvalid(f"risk_level 必须是 1/2/3 的整数，实际是 {risk_level!r}")

    action = data.get("action_suggestion")
    if not isinstance(action, str) or not action.strip():
        raise ModelOutputInvalid("action_suggestion 为空")

    def string_list(value: object, limit: int) -> list[str]:
        if value is None:
            return []
        if not isinstance(value, list):
            raise ModelOutputInvalid(f"期望数组，实际是 {type(value).__name__}")
        return [str(item)[:200] for item in value[:limit]]

    # 红色**必带就医建议**（交付文档 9.5 / ADR-0021）：模型说不用去医院也照样置真。
    # 默认值只兜「字段缺失」，兜不住「模型明确给了 false 但判了红」。
    need_hospital = bool(data.get("need_hospital", risk_level >= 2)) or risk_level >= 3

    return {
        "risk_level": risk_level,
        "possible_causes": string_list(data.get("possible_causes"), 3),
        "action_suggestion": action.strip()[:500],
        "need_hospital": need_hospital,
        "care_tips": string_list(data.get("care_tips"), 3),
    }


async def _chat(messages: list[dict], model: str) -> dict:
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": settings.ai_max_output_tokens,
        "tools": [REPORT_TOOL],
        # 思考模式不支持指定具体工具（实测 HTTP 400），只能用 auto
        "tool_choice": "auto",
    }
    try:
        async with httpx.AsyncClient(timeout=settings.ai_timeout_seconds) as client:
            response = await client.post(
                f"{settings.ai_base_url.rstrip('/')}/chat/completions",
                headers={
                    "Authorization": f"Bearer {settings.ai_api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
    except httpx.HTTPError as exc:
        raise ModelUnavailable(f"调用模型失败：{type(exc).__name__}") from exc

    if response.status_code >= 400:
        raise ModelUnavailable(f"模型返回 HTTP {response.status_code}：{response.text[:160]}")
    try:
        return response.json()
    except ValueError as exc:
        # 200 但不是 JSON：`json.JSONDecodeError` 不是 `httpx.HTTPError` 的子类，
        # 不接住就会逃出上面的 try，变成未捕获异常 500——而本文件头的第 4 条纪律是
        # 「失败一律降级、不向上抛错」（测试报告里 AI 侧的同批缺陷）
        raise ModelUnavailable(f"模型响应不是合法 JSON：{response.text[:120]}") from exc


def _usage_of(body: dict) -> tuple[int, int]:
    """从响应里取 token 用量。供应商没给（或给了怪形状）就记 0，不让它影响主流程。"""
    usage = body.get("usage")
    if not isinstance(usage, dict):
        return 0, 0
    prompt = usage.get("prompt_tokens")
    completion = usage.get("completion_tokens")
    return (prompt if isinstance(prompt, int) else 0, completion if isinstance(completion, int) else 0)


def _extract_tool_arguments(body: dict) -> tuple[str, str]:
    """从响应里取出工具参数与模型版本。没有工具调用 → 抛 ModelOutputInvalid。"""
    try:
        choice = body["choices"][0]
    except (KeyError, IndexError, TypeError) as exc:
        raise ModelOutputInvalid("响应结构不符合预期") from exc

    tool_calls = choice.get("message", {}).get("tool_calls") or []
    if not tool_calls:
        finish = choice.get("finish_reason")
        # finish_reason=length 且没有 content，几乎总是输出预算被 reasoning 吃掉了
        raise ModelOutputInvalid(f"模型没有调用工具（finish_reason={finish}）")

    # 形状异常（不是 list、缺 function.arguments）也要降级，不能 KeyError/TypeError 逃出去
    first = tool_calls[0]
    if not isinstance(first, dict):
        raise ModelOutputInvalid("工具调用的形状不符合预期")
    arguments = first.get("function", {}).get("arguments")
    if not isinstance(arguments, str):
        raise ModelOutputInvalid("工具调用里没有 arguments")
    return arguments, str(body.get("model", ""))


def _user_content(user_prompt: str, images: list[str]) -> str | list[dict]:
    """文字 + 图片组成一条消息。有图时用 OpenAI 的 content blocks 形状。"""
    if not images:
        return user_prompt
    blocks: list[dict] = [{"type": "text", "text": user_prompt}]
    for url in images:
        blocks.append({"type": "image_url", "image_url": {"url": url}})
    return blocks


def _data_url(content: bytes, content_type: str) -> str:
    """内联成 data URL：模型供应商只需要能解码 base64，不需要能访问我们的内网。"""
    encoded = base64.b64encode(content).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


async def inline_images(urls: list[str]) -> list[str]:
    """把图片取回来、转成 data URL 交给模型。
    
    **为什么不能把签名读地址直接交给供应商**：那个地址指向我们自己的后端
    （本地是 127.0.0.1、线上是内网），供应商拉不到——而且是**静默失败**：
    模型基于「看不到图」给出一个格式完全正常的回答，用户以为照片被看过了。
    这种「看起来正常的错误答案」比报错危险得多（测试报告 D7）。
    
    取不到就抛 `ImageUnavailable`：调用方据此明确告诉用户「图片这轮没分析」，
    而不是假装看过。张数、类型、体积都在这里把关，别把 10MB 的原图塞进上下文。
    """
    if not urls:
        return []
    inlined: list[str] = []
    async with httpx.AsyncClient(timeout=settings.ai_image_timeout_seconds) as client:
        for url in urls:
            # 已经是 data URL 就直接用：调用方可能已经把字节内联好了（测试、离线环境、
            # 或将来改成由 Java 侧内联）。**httpx 只认 http(s)**，拿 data: 去 GET 会抛错，
            # 结果是把「图就在手里」这种最好办的情况降级成「读不到图」——实测踩到的。
            if url.startswith("data:"):
                inlined.append(url)
                continue
            try:
                response = await client.get(url)
            except httpx.HTTPError as exc:
                raise ImageUnavailable(f"图片下载失败（{type(exc).__name__}）") from exc
            if response.status_code != 200:
                raise ImageUnavailable(f"图片下载失败（HTTP {response.status_code}）")
            content_type = (response.headers.get("content-type") or "").split(";")[0].strip()
            if not content_type.startswith("image/"):
                raise ImageUnavailable(f"取回的不是图片（content-type={content_type or '未知'}）")
            if len(response.content) > settings.ai_max_image_bytes:
                raise ImageUnavailable(
                    f"图片超过 {settings.ai_max_image_bytes} 字节上限（{len(response.content)}）")
            inlined.append(_data_url(response.content, content_type))
    return inlined


async def assess(
    *,
    system_prompt: str,
    user_prompt: str,
    images: list[str] | None = None,
    model: str | None = None,
) -> TriageResult:
    """跑一次分级（可带图）。

    失败抛三种异常，由调用方决定怎么降级：
    `VisionUnavailable`（没配视觉模型）/ `ModelUnavailable`（传输层）/ `ModelOutputInvalid`（输出没法用）。
    """
    images = (images or [])[: settings.ai_max_images]
    if images:
        # 两个条件都要看：能力开关（这家有没有视觉能力）+ 具体用哪个模型。
        # 只看模型名的话，换成一家没有视觉能力的供应商时会在运行期才炸（得到「无法确定」这种假答案）。
        if not settings.ai_supports_image or not settings.ai_vision_model:
            raise VisionUnavailable("当前供应商/模型不支持图片输入")
        model = settings.ai_vision_model
        # 取回字节内联：供应商拉不到我们内网的签名地址（见 inline_images 的说明）
        images = await inline_images(images)
    model = model or settings.ai_model_grading
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": _user_content(user_prompt, images)},
    ]

    started = time.perf_counter()
    last_error: Exception | None = None

    # 第一次 + 最多 ai_max_repair_retry 次修复重试。
    # 重试时补一句「必须调用工具」——模型偶尔会直接用文字回答（#61 说的二次失败要拔高风险）。
    prompt_tokens = 0
    completion_tokens = 0
    for attempt in range(settings.ai_max_repair_retry + 1):
        try:
            body = await _chat(messages, model)
        except ModelUnavailable as exc:
            # 传输层挂在这一轮：把**已经花掉**的用量捎出去（上一次重试可能成功过）
            raise ModelUnavailable(str(exc), prompt_tokens=prompt_tokens,
                                   completion_tokens=completion_tokens) from exc
        # 用量按次累加：修复重试也是真实的钱（哪怕这一轮最后判无效）
        used_prompt, used_completion = _usage_of(body)
        prompt_tokens += used_prompt
        completion_tokens += used_completion
        try:
            raw_arguments, model_version = _extract_tool_arguments(body)
            fields = _validate_arguments(raw_arguments)
        except ModelOutputInvalid as exc:
            last_error = exc
            messages.append(
                {
                    "role": "user",
                    "content": "上一次没有按格式上报。请只调用 report_triage 工具上报结果，不要在正文输出。",
                }
            )
            continue

        latency_ms = int((time.perf_counter() - started) * 1000)
        return TriageResult(
            model_name=model,
            model_version=model_version,
            latency_ms=latency_ms,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            **fields,
        )

    # 重试到顶仍然不可用：把累计用量带上，别让这两次白花
    raise ModelOutputInvalid(str(last_error), prompt_tokens=prompt_tokens,
                             completion_tokens=completion_tokens)
