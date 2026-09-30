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

import asyncio
import base64
import json
import re
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
    """带图请求但当前没有能看图的模型。调用方据此明确降级并告知用户。

    **刻意不继承 `_UsageCarrying`**（与另外三条异常不同）：它在 `assess` 开头的
    能力检查里就抛了，**一次模型调用都还没发生**，用量必然是 0。给它挂上用量字段
    只会让读代码的人以为「这条路径也可能花过钱」。
    """


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
    #: 模型自报的知识条目编号（如 ["K-0010"]）。**这里只做形状校验**（是不是 K-xxxx），
    #: 「在不在本轮召回集里」由 `main._review_citations` 判——那一层才知道召回集。
    citations: list[str] = field(default_factory=list)
    model_name: str = ""
    model_version: str = ""
    latency_ms: int = 0
    #: 本轮实际消耗的 token（供应商在响应里给 `usage`）。日预算告警要用它（ADR-0026）
    prompt_tokens: int = 0
    completion_tokens: int = 0


#: 知识条目编号的写法（K-0010）。模型自报的编号先按它筛一遍：不匹配的一律丢掉，
#: 免得一个 `{"entry":"呕吐"}` 这类自由文本混进引用列表（校验在 main，形状把关在这里）。
CITATION_PATTERN = re.compile(r"K-\d{3,6}")


def _validate_arguments(raw: str) -> dict:
    """把工具参数解析并校验成我们认得的形状。越界一律当无效，宁可降级也不猜。

    字段与 `prompts.REPORT_TOOL` 的 properties 一一对应，**两边要一起改**：切片 #103 之后
    工具定义跟提示词一起入库、一起版本化，这里对齐的那一份可能来自库而不是代码。
    这里放过的形状错误会直接进 `TriageResult`，而 risk_level 是安全字段——所以宁可抛
    `ModelOutputInvalid`：`assess` 会据此补一次修复重试，重试到顶才降级。
    """
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
        """数组字段 → 字符串数组，顺手截到 `limit` 条、每条 200 字。

        `None` 当空数组（字段缺失是正常的），但**不是数组就判无效**：形状不对说明模型没按
        工具定义填，「猜它想说什么」比降级危险。截断是防上下文被灌爆，不是业务规则。
        """
        if value is None:
            return []
        if not isinstance(value, list):
            raise ModelOutputInvalid(f"期望数组，实际是 {type(value).__name__}")
        return [str(item)[:200] for item in value[:limit]]

    def citation_list(value: object) -> list[str]:
        """模型自报的引用编号：只留形如 K-0010 的，其余（自由文本、错格式）丢掉。

        **不因为「编号不合规」把整次回答判无效**：引用是附加信息，丢了它最坏是
        citations 为空（C 端就不说「基于知识库」），而判无效会让用户拿不到结论。
        """
        if not isinstance(value, list):
            return []
        return [found for item in value[:8] if (found := CITATION_PATTERN.search(str(item)))]

    # 红色**必带就医建议**（交付文档 9.5 / ADR-0021）：模型说不用去医院也照样置真。
    # 默认值只兜「字段缺失」，兜不住「模型明确给了 false 但判了红」。
    need_hospital = bool(data.get("need_hospital", risk_level >= 2)) or risk_level >= 3

    return {
        "risk_level": risk_level,
        "possible_causes": string_list(data.get("possible_causes"), 3),
        "action_suggestion": action.strip()[:500],
        "need_hospital": need_hospital,
        "care_tips": string_list(data.get("care_tips"), 3),
        "citations": citation_list(data.get("citations")),
    }


async def _chat(messages: list[dict], model: str, tool: dict) -> dict:
    """模型调用的**唯一出口**：发一次 chat/completions，返回原始响应 JSON。

    为什么只有这里能发 HTTP：鉴权头、超时、`tools` 的形状、以及「非 2xx 与连不上都算
    `ModelUnavailable`」这三件事必须全局一致——换供应商时只改这一个函数
    （ADR-0017）。用量与异常的分类留给调用方 `assess`。

    `tool` 由调用方传进来（不再直接读 `prompts.REPORT_TOOL`）：切片 #103 之后工具定义与
    提示词一起入库、一起版本化，两边必须来自**同一行**配置——一个来自库、一个来自代码，
    就会出现「提示词说必须调工具、工具定义却没有那个字段」这种对不上的组合。

    **失败一律抛异常，不返回错误对象**：调用方（`assess` → consultant）需要按类型决定
    是降级、还是补一次修复重试，静默返回一个「看起来成功」的对象会把这两条路混在一起。
    """
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": settings.ai_max_output_tokens,
        "tools": [tool],
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
    """从响应里取 token 用量（prompt, completion）。

    它是日预算告警（ADR-0026）与「修复重试也真花了钱」的唯一凭证，所以**形状不对就记 0**：
    为了一个统计字段把一次成功的分级判成失败不值得，但也不能因此把这个函数挪去影响主流程。
    """
    usage = body.get("usage")
    if not isinstance(usage, dict):
        return 0, 0
    prompt = usage.get("prompt_tokens")
    completion = usage.get("completion_tokens")
    return (prompt if isinstance(prompt, int) else 0, completion if isinstance(completion, int) else 0)


def _extract_tool_arguments(body: dict) -> tuple[str, str]:
    """从响应里取出（工具参数原文, 模型版本）。取不到就抛 `ModelOutputInvalid`。

    两种「没有可用输出」都归到这个异常：响应结构不符合预期，以及模型压根没调工具。
    后者把 `finish_reason` 一起写进消息——`length` 通常意味着输出预算被 reasoning 吃光了
    （见文件头第 1 条），与「模型就是不肯调」的处置方式不同，归因时要分得开。
    **形状异常也要降级**：`tool_calls[0]` 不是 dict、缺 `function.arguments` 都不许让
    KeyError/TypeError 逃出去变成 500。
    """
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
    """文字 + 图片拼成一条 user 消息。**没有图就返回纯字符串**：content blocks 是给带图那条
    路准备的，纯文本时套上它只是白搭一层结构。

    这里只拼装、不再校验：图片走到这一步已经是 data URL（见 `inline_images`），
    把关在 `_fetch_one` / `_checked_data_url`。
    """
    if not images:
        return user_prompt
    blocks: list[dict] = [{"type": "text", "text": user_prompt}]
    for url in images:
        blocks.append({"type": "image_url", "image_url": {"url": url}})
    return blocks


def _checked_data_url(url: str) -> str:
    """校验调用方自己内联的 data URL：类型必须是图片、base64 能解码、体积不超上限。

    「已经内联好」不等于「可以跳过把关」——体积上限防的是把 10MB 原图塞进上下文，
    与字节是谁内联的无关；绕过这一处等于给 `inline_images` 留了一条不限量的旁路。
    通过之后**原样返回入参**，不重新拼一个。
    """
    header, _, payload = url.partition(",")
    if not header.startswith("data:image/") or ";base64" not in header:
        raise ImageUnavailable(f"data URL 只接受 base64 编码的图片（收到 {header[:40]}）")
    try:
        size = len(base64.b64decode(payload, validate=True))
    except (ValueError, TypeError) as exc:
        raise ImageUnavailable("data URL 的 base64 内容无法解码") from exc
    if size > settings.ai_max_image_bytes:
        raise ImageUnavailable(f"图片超过 {settings.ai_max_image_bytes} 字节上限（{size}）")
    return url


def _data_url(content: bytes, content_type: str) -> str:
    """内联成 data URL：模型供应商只需要能解码 base64，不需要能访问我们的内网。"""
    encoded = base64.b64encode(content).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


async def _fetch_one(client: httpx.AsyncClient, url: str) -> str:
    """取回一张图并校验，返回 data URL。取不到就抛 `ImageUnavailable`。

    「已经是 data URL」这一支也必须过同一套把关（类型、体积）——
    字节已经在手里不等于可以直接用，绕过把关就等于留了一条不限量的旁路。
    """
    # 已经内联好的：**httpx 只认 http(s)**，拿 data: 去 GET 会抛错 → 降级成「读不到图」，
    # 而「字节已经在手里」是最不该失败的一种情况（实测踩到）。
    if url.startswith("data:"):
        return _checked_data_url(url)
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
    return _data_url(response.content, content_type)


async def inline_images(urls: list[str]) -> list[str]:
    """把图片取回来、转成 data URL 交给模型。

    **为什么不能把签名读地址直接交给供应商**：那个地址指向我们自己的后端
    （本地是 127.0.0.1、线上是内网），供应商拉不到——而且是**静默失败**：
    模型基于「看不到图」给出一个格式完全正常的回答，用户以为照片被看过了。
    这种「看起来正常的错误答案」比报错危险得多（测试报告 D7）。

    取不到就抛 `ImageUnavailable`：调用方据此明确告诉用户「图片这轮没分析」，
    而不是假装看过。张数、类型、体积都在这里把关，别把 10MB 的原图塞进上下文。

    **并发取而不是逐张 await**：每张的超时是 `ai_image_timeout_seconds`（默认 10 秒），
    串行取 4 张最坏就是 40 秒，而整轮咨询的读超时只有 20 秒（`ai_timeout_seconds`）——
    慢网络下会整轮降级，明明每一张都还有机会取回来。

    用 `asyncio.gather` 而不是 `TaskGroup`：前者原样抛出第一处异常，调用方仍然
    `except ImageUnavailable` 就够；后者抛的是 `ExceptionGroup`，会漏过那条 except。
    gather 保序，所以返回的顺序与 `urls` 一致。
    """
    if not urls:
        return []
    async with httpx.AsyncClient(timeout=settings.ai_image_timeout_seconds) as client:
        return list(await asyncio.gather(*[_fetch_one(client, url) for url in urls]))


async def assess(
    *,
    system_prompt: str,
    user_prompt: str,
    images: list[str] | None = None,
    model: str | None = None,
    tool: dict | None = None,
) -> TriageResult:
    """跑一次分级（可带图）。

    失败抛三种异常，由调用方决定怎么降级：
    `VisionUnavailable`（没配视觉模型）/ `ModelUnavailable`（传输层）/ `ModelOutputInvalid`（输出没法用）。

    `tool` 留空时用代码基线（`prompts.REPORT_TOOL`）：库里读不到提示词时整条链路都退回基线，
    所以两者要一起退——只退提示词、工具定义用库里的，会出现两者不匹配的组合。
    """
    tool = tool or REPORT_TOOL
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
            body = await _chat(messages, model, tool)
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
