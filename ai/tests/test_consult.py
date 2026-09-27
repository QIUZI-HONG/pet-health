"""AI 服务的测试。

两层：

- **常规测试**（默认跑，CI 也跑）：模型调用被替换成桩，验的是提示词拼装、结构化输出的校验、
  三条降级路径、鉴权顺序——这些不需要网络，也不该依赖外部服务的可用性。
- **实弹测试**（`-m live`，默认跳过）：真打模型，验工具调用在真环境下确实能回来。
  它需要 key 与网络，所以只在本地手动跑：`AI_LIVE_TEST=1 pytest -m live`。
"""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from app import main, model_client, prompts
from app.config import settings
from app.model_client import ModelOutputInvalid, ModelUnavailable, TriageResult
from app.models import ConsultInput, ConsultRequest, PetContext

client = TestClient(main.app)

TOKEN = {"X-Internal-Token": settings.internal_token}


def make_request(text: str = "今天吐了两次，精神还行", media_urls: list[str] | None = None) -> dict:
    return ConsultRequest(
        trace_id="trace-test-1",
        user_id=1,
        pet=PetContext(id=1, species=1, breed="柯基", birth_date="2023-05-01", weight=12.5),
        input=ConsultInput(type="text", text=text, media_urls=media_urls or []),
    ).model_dump()


# ---------------------------------------------------------------- 鉴权


def test_health_reports_capabilities():
    body = client.get("/internal/health").json()
    assert body["status"] == "ok"
    assert body["prompt_version"] == prompts.PROMPT_VERSION
    # 能力矩阵要如实报出来，别让调用方猜（当前供应商没有图片/语音/向量）
    assert body["capabilities"] == {"text": True, "image": False, "audio": False, "embedding": False}


def test_consult_requires_token():
    assert client.post("/internal/consult", json=make_request()).status_code == 401


def test_consult_requires_text_even_with_image():
    """#61 的结论钉在接口层：纯图片分诊只有 33%，所以 text 必填。"""
    body = make_request()
    del body["input"]["text"]
    response = client.post("/internal/consult", json=body, headers=TOKEN)
    assert response.status_code == 422


# ---------------------------------------------------------------- 提示词


def test_user_prompt_carries_pet_context():
    prompt = prompts.build_user_prompt(
        pet={"species": 2, "breed": "英短", "birth_date": "2024-01-02", "weight": 4.05, "chronic": ["慢性肾病"]},
        text="今天没怎么吃饭",
        history=[{"role": "user", "content": "上周也有一次"}],
        image_count=0,
    )
    assert "猫" in prompt
    assert "英短" in prompt
    assert "慢性肾病" in prompt
    assert "今天没怎么吃饭" in prompt
    assert "上周也有一次" in prompt


def test_user_prompt_flags_ignored_images():
    """带图但模型看不见时，要在提示词里说明已忽略——不能让模型假装看过。"""
    prompt = prompts.build_user_prompt(pet={"species": 1}, text="皮肤有红点", history=[], image_count=2)
    assert "2 张" in prompt and "看不见图片" in prompt


# ---------------------------------------------------------------- 结构化输出校验


def test_validate_accepts_well_formed_arguments():
    fields = model_client._validate_arguments(
        json.dumps(
            {
                "risk_level": 3,
                "possible_causes": ["误食异物", "急性肠胃炎", "第 4 条应被截断"],
                "action_suggestion": "立即送医。",
                "need_hospital": True,
                "care_tips": ["禁食 4 小时", "记录呕吐次数"],
            }
        )
    )
    assert fields["risk_level"] == 3
    assert fields["need_hospital"] is True
    assert len(fields["possible_causes"]) == 3  # 最多 3 条


@pytest.mark.parametrize(
    "payload",
    [
        "not-json",
        json.dumps({"risk_level": 5, "action_suggestion": "立即送医"}),  # 越界
        json.dumps({"risk_level": 1}),  # 缺 action_suggestion
        json.dumps({"risk_level": 1, "action_suggestion": "  "}),  # 空建议
        json.dumps({"risk_level": 1, "action_suggestion": "观察", "care_tips": "不是数组"}),
    ],
)
def test_validate_rejects_bad_arguments(payload):
    with pytest.raises(ModelOutputInvalid):
        model_client._validate_arguments(payload)


# ---------------------------------------------------------------- 三条降级路径


def test_image_request_degrades_explicitly(monkeypatch):
    """图片：不是静默忽略，也不是假装成功——明确降级并告知。"""
    calls = []

    async def fake_assess(**kwargs):  # pragma: no cover - 不该被调用
        calls.append(kwargs)
        raise AssertionError("带图请求且供应商不支持图片时，不应调用模型")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post(
        "/internal/consult",
        json=make_request(media_urls=["https://example.com/skin.jpg"]),
        headers=TOKEN,
    ).json()

    assert body["degraded"] is True
    assert body["degrade_reason"] == "image_not_supported"
    assert "图片" in body["action_suggestion"]
    assert calls == []


def test_model_unavailable_degrades_to_conservative_advice(monkeypatch):
    async def fake_assess(**kwargs):
        raise ModelUnavailable("调用模型失败：ConnectTimeout")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["degraded"] is True
    assert body["degrade_reason"].startswith("model_unavailable")
    assert body["need_hospital"] is True
    # 降级不等于报错：HTTP 仍然是 200，前端拿到的是一句人话
    assert body["risk_level"] == 2


def test_invalid_model_output_escalates_risk(monkeypatch):
    """模型答了但没法用：按设计把风险拔高，而不是给一个「绿」。"""

    async def fake_assess(**kwargs):
        raise ModelOutputInvalid("模型没有调用工具（finish_reason=length）")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["degraded"] is True
    assert body["degrade_reason"].startswith("model_output_invalid")
    assert body["risk_level"] == 3


def test_happy_path_returns_grading_with_traceability(monkeypatch):
    async def fake_assess(**kwargs):
        # 系统提示词必须带上铁律（不给确诊/处方/剂量）
        assert "绝不给确诊" in kwargs["system_prompt"]
        return TriageResult(
            risk_level=2,
            possible_causes=["饮食不当"],
            action_suggestion="观察 24 小时，若继续呕吐请就医。",
            need_hospital=True,
            care_tips=["少量多次饮水"],
            model_name=settings.ai_model_grading,
            model_version="deepseek-v4-pro-2026-09",
            latency_ms=1234,
        )

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["degraded"] is False
    assert body["risk_level"] == 2
    assert body["action_suggestion"].startswith("观察")
    # 留痕三件套齐备，否则事后没法归因「分级漂移」
    assert body["model_name"] and body["model_version"] and body["prompt_version"]
    assert body["latency_ms"] == 1234
    # 检索层没实现前，来源引用宁可空着也不编
    assert body["citations"] == []


# ---------------------------------------------------------------- 队列/重试


def test_repair_retry_happens_once_then_gives_up(monkeypatch):
    """模型不调工具时重试一次（补一句要求），两次都失败才抛 —— 这是 #61 的「二次失败拔高风险」。"""
    responses = [
        {"choices": [{"message": {"content": "我觉得是轻微肠胃不适。"}, "finish_reason": "stop"}], "model": "m"},
        {"choices": [{"message": {"content": "还是不用工具。"}, "finish_reason": "stop"}], "model": "m"},
    ]
    seen_prompts = []

    async def fake_chat(messages, model):
        seen_prompts.append(messages[-1]["content"])
        return responses.pop(0)

    monkeypatch.setattr(model_client, "_chat", fake_chat)

    with pytest.raises(ModelOutputInvalid):
        asyncio.run(model_client.assess(system_prompt="s", user_prompt="u"))

    assert len(seen_prompts) == 2  # 一次原始 + 一次修复重试
    assert "必须" in seen_prompts[-1] or "只调用" in seen_prompts[-1]


# ---------------------------------------------------------------- 实弹（默认跳过）


@pytest.mark.live
def test_live_model_call_returns_tool_call():
    """真打模型。需要 key 与网络，默认跳过：AI_LIVE_TEST=1 pytest -m live"""
    if not settings.ai_api_key:
        pytest.skip("没有配置 AI_API_KEY")
    result = asyncio.run(
        model_client.assess(
            system_prompt=prompts.SYSTEM_PROMPT,
            user_prompt=prompts.build_user_prompt(
                pet={"species": 1, "birth_date": "2023-05-01"},
                text="我家狗今天吐了两次，精神还行，还能喝水",
                history=[],
                image_count=0,
            ),
        )
    )
    assert result.risk_level in (1, 2, 3)
    assert result.action_suggestion
    assert result.model_name
    assert result.latency_ms > 0
