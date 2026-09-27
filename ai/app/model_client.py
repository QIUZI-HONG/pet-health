"""模型调用的唯一出口。

只有这一个文件直接跟模型供应商说话。上层（`main.py`）拿到的是校验过的结构化结果，
永远不接触 HTTP、JSON 解析或重试细节。

三条实测得来的纪律（对应 [ADR-0017](../../docs/adr/0017-model-provider-deepseek.md)）：

1. **输出预算给足**。当前模型是推理型，reasoning 会先吃掉预算；给少了返回
   `finish_reason=length` + 空 `content`，看起来像「模型不说话」。
2. **工具调用只能 `tool_choice=auto`**。思考模式拒绝强制指定（HTTP 400），
   所以「模型没调工具」是正常分支，要重试一次而不是当成崩溃。
3. **失败一律降级、不向上抛错**。模型不可用时用户拿到的是保守建议，不是 500。
"""

import json
import time
from dataclasses import dataclass, field

import httpx

from .config import settings
from .prompts import REPORT_TOOL


class ModelUnavailable(Exception):
    """传输层失败：超时、连不上、非 2xx。调用方据此降级。"""


class ModelOutputInvalid(Exception):
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


def _validate_arguments(raw: str) -> dict:
    """把工具参数解析并校验成我们认得的形状。越界一律当无效，宁可降级也不猜。"""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ModelOutputInvalid(f"工具参数不是合法 JSON：{raw[:120]}") from exc

    if not isinstance(data, dict):
        raise ModelOutputInvalid("工具参数不是对象")

    risk_level = data.get("risk_level")
    if risk_level not in (1, 2, 3):
        raise ModelOutputInvalid(f"risk_level 越界：{risk_level!r}")

    action = data.get("action_suggestion")
    if not isinstance(action, str) or not action.strip():
        raise ModelOutputInvalid("action_suggestion 为空")

    def string_list(value: object, limit: int) -> list[str]:
        if value is None:
            return []
        if not isinstance(value, list):
            raise ModelOutputInvalid(f"期望数组，实际是 {type(value).__name__}")
        return [str(item)[:200] for item in value[:limit]]

    return {
        "risk_level": risk_level,
        "possible_causes": string_list(data.get("possible_causes"), 3),
        "action_suggestion": action.strip()[:500],
        "need_hospital": bool(data.get("need_hospital", risk_level >= 2)),
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
    return response.json()


def _extract_tool_arguments(body: dict) -> tuple[str, str]:
    """从响应里取出工具参数与模型版本。没有工具调用 → 抛 ModelOutputInvalid。"""
    try:
        choice = body["choices"][0]
    except (KeyError, IndexError) as exc:
        raise ModelOutputInvalid("响应结构不符合预期") from exc

    tool_calls = choice.get("message", {}).get("tool_calls") or []
    if not tool_calls:
        finish = choice.get("finish_reason")
        # finish_reason=length 且没有 content，几乎总是输出预算被 reasoning 吃掉了
        raise ModelOutputInvalid(f"模型没有调用工具（finish_reason={finish}）")

    return tool_calls[0]["function"]["arguments"], str(body.get("model", ""))


async def assess(*, system_prompt: str, user_prompt: str, model: str | None = None) -> TriageResult:
    """跑一次分级。失败抛 ModelUnavailable / ModelOutputInvalid，由调用方决定怎么降级。"""
    model = model or settings.ai_model_grading
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    started = time.perf_counter()
    last_error: Exception | None = None

    # 第一次 + 最多 ai_max_repair_retry 次修复重试。
    # 重试时补一句「必须调用工具」——模型偶尔会直接用文字回答（#61 说的二次失败要拔高风险）。
    for attempt in range(settings.ai_max_repair_retry + 1):
        body = await _chat(messages, model)
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
        return TriageResult(model_name=model, model_version=model_version, latency_ms=latency_ms, **fields)

    raise ModelOutputInvalid(str(last_error))
