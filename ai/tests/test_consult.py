"""AI 服务的测试。

两层：

- **常规测试**（默认跑，CI 也跑）：模型调用被替换成桩，验的是提示词拼装、结构化输出的校验、
  三条降级路径、鉴权顺序——这些不需要网络，也不该依赖外部服务的可用性。
- **实弹测试**（`-m live`，默认跳过）：真打模型，验工具调用在真环境下确实能回来。
  它需要 key 与网络，所以只在本地手动跑：`AI_LIVE_TEST=1 pytest -m live`。
"""

import asyncio
import base64
import json
from typing import ClassVar

import pytest
from fastapi.testclient import TestClient

from app import knowledge, main, model_client, ops, prompts
from app.config import settings
from app.model_client import ModelOutputInvalid, ModelUnavailable, TriageResult
from app.models import ConsultInput, ConsultRequest, PetContext

from .conftest import TEST_INTERNAL_TOKEN, no_db

client = TestClient(main.app)

TOKEN = {"X-Internal-Token": TEST_INTERNAL_TOKEN}


def make_request(text: str = "今天吐了两次，精神还行", media_urls: list[str] | None = None) -> dict:
    return ConsultRequest(
        trace_id="trace-test-1",
        user_id=1,
        pet=PetContext(id=1, species=1, breed="柯基", birth_date="2023-05-01", weight=12.5),
        input=ConsultInput(type="text", text=text, media_urls=media_urls or []),
    ).model_dump()


# ---------------------------------------------------------------- 鉴权


def test_health_reports_capabilities():
    body = client.get("/internal/health", headers=TOKEN).json()
    assert body["status"] == "ok"
    # 版本号唯一来源是 settings（prompts.py 不再维护第二份）
    assert body["prompt_version"] == settings.prompt_version
    # 能力矩阵要如实报出来，别让调用方猜（当前供应商没有图片/语音/向量）
    # 图片能力实测为真（flash 能读图，pro 不能），所以这里是 True 而不是 False——
    # 我最初把它写成 False 是因为预算被 reasoning 吃光后得到空 content，那是误判（见 ADR-0017）
    assert body["capabilities"] == {"text": True, "image": True, "audio": False, "embedding": False}


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


def test_user_prompt_mentions_attached_images():
    """带图时提示词要说明「图随消息一起来了，结合文字判断」，并禁止凭想象描写图片。"""
    prompt = prompts.build_user_prompt(pet={"species": 1}, text="皮肤有红点", history=[], image_count=2)
    assert "2 张" in prompt and "结合图片与文字判断" in prompt
    # 铁律里必须有「看不清就如实说、不要编」这一条
    assert "不要" in prompts.SYSTEM_PROMPT and "图片不足以判断" in prompts.SYSTEM_PROMPT


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


def test_images_are_actually_sent_to_the_model(monkeypatch):
    """带图请求：图片要真的进模型上下文，并且留下「本轮看了几张图」的痕迹。"""
    seen = {}

    async def fake_assess(**kwargs):
        seen.update(kwargs)
        return TriageResult(
            risk_level=2,
            action_suggestion="建议 24 小时内就诊。",
            need_hospital=True,
            model_name="deepseek-flash",
            model_version="deepseek-flash",
            latency_ms=6700,
        )

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post(
        "/internal/consult",
        json=make_request(media_urls=["https://example.com/skin.jpg"]),
        headers=TOKEN,
    ).json()

    assert seen["images"] == ["https://example.com/skin.jpg"]
    assert body["degraded"] is False
    assert body["images_used"] == 1
    # 提示词里也要写到有图，否则模型不知道去看
    assert "结合图片与文字判断" in seen["user_prompt"]


def test_image_count_is_capped(monkeypatch):
    """图片直接进上下文，太多了既费 token 也没帮助——按配置截断。"""
    seen = {}

    async def fake_assess(**kwargs):
        seen.update(kwargs)
        return TriageResult(risk_level=1, action_suggestion="继续观察", model_name="m")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    urls = [f"https://example.com/{i}.jpg" for i in range(5)]
    body = client.post("/internal/consult", json=make_request(media_urls=urls), headers=TOKEN).json()

    assert len(seen["images"]) == settings.ai_max_images
    assert body["images_used"] == settings.ai_max_images


def test_image_request_degrades_when_no_vision_model(monkeypatch):
    """没有能看图的模型时：明确降级并告知，不硬发给看不见图的模型。"""
    monkeypatch.setattr(settings, "ai_vision_model", "")
    calls = []

    async def fake_assess(**kwargs):  # 不该被调用
        calls.append(kwargs)
        raise AssertionError("没有视觉模型时不应该调用模型")

    # 注意：这里要打到真正的 assess（它负责判断能不能看图），所以只桩掉底层 _chat
    async def fake_chat(messages, model, tool):  # pragma: no cover
        raise AssertionError("不该真的发请求")

    monkeypatch.setattr(model_client, "_chat", fake_chat)
    monkeypatch.setattr(main.model_client, "assess", model_client.assess)

    body = client.post(
        "/internal/consult",
        json=make_request(media_urls=["https://example.com/skin.jpg"]),
        headers=TOKEN,
    ).json()

    assert body["degraded"] is True
    # degrade_code 是机器可读的码（用户可见的中文由 Java 侧映射），明细在 degrade_detail 里
    assert body["degrade_code"] == "image_not_supported"
    assert "VisionUnavailable" in body["degrade_detail"]
    assert body["images_used"] == 0
    assert "图片" in body["action_suggestion"]
    assert calls == []


def test_model_unavailable_degrades_to_conservative_advice(monkeypatch):
    async def fake_assess(**kwargs):
        raise ModelUnavailable("调用模型失败：ConnectTimeout")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["degraded"] is True
    assert body["degrade_code"] == "model_unavailable"
    # 明细里有异常类名，**这些不能出给用户**：Java 侧按 degrade_code 映射用户文案（测试报告 D6）
    assert "ConnectTimeout" in body["degrade_detail"]
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
    assert body["degrade_code"] == "model_output_invalid"
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
    # 这一轮检索读不到知识域（conftest 把所有查库接缝打到「没库」）：
    # 宁可让 citations 空着，也不编造条目 ID；retrieval_check 如实上报（ADR-0033）
    assert body["citations"] == []
    assert body["retrieval_check"] == "unavailable"


# ---------------------------------------------------------------- 「没检索」的两种事实


def _retrieval_switch_off() -> dict[str, bool]:
    """开关表**读到了**，且运营把检索关着（`enabled=False` 是事实，不是 fail-closed 的兜底）。"""
    return {ops.SWITCH_RETRIEVAL_ENABLED: False}


@pytest.mark.parametrize(
    ("read_switches", "expected", "alerts"),
    [
        # 开关读不到（库挂了、迁移没跑）：**故障** → unavailable，且得留下告警
        (no_db, "unavailable", True),
        # 开关读到了且为关：**人为动作** → disabled，不该告警
        (_retrieval_switch_off, "disabled", False),
    ],
    ids=["config-unreadable", "switch-really-off"],
)
def test_retrieval_check_separates_a_config_failure_from_a_real_off_switch(
    monkeypatch, caplog, read_switches, expected, alerts
):
    """ADR-0050 §三：`unavailable` 是故障、`disabled` 是人为关掉，两种「没检索」不能报成一个。

    原先的实现只把「开关值」传下去，配置读不到时按 fail-closed 取 False——于是留痕里写着
    「运营关了检索」，而真相是**知识库读不到**。运维看到 disabled 不会告警，AI 退化成
    无来源建议的真实原因就永远查不出来（`retrieval_check` 存在的意义正是防这件事）。

    反向也钉住：开关**确实为关**时若报 unavailable，会让人去查一个不存在的故障。
    两条分支都要咨询照常成功——改的只是上报语义，fail-closed 没有放松（两种情况下
    检索都不启用：`citations` 为空）。
    """
    monkeypatch.setattr(ops, "_query_switches", read_switches)

    async def fake_assess(**_kwargs):
        return TriageResult(risk_level=2, action_suggestion="观察 24 小时。", model_name="stub")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["retrieval_check"] == expected
    assert body["degraded"] is False
    assert body["citations"] == []
    if alerts:
        # 是故障就要留下告警，且 detail 要能分辨是哪一种 unavailable（配置读不到 vs 知识域读不到）
        assert "retrieval_unavailable" in caplog.text
        assert "检索开关读不到" in caplog.text
    else:
        # 人为关掉的闸门不该惊动运维
        assert "retrieval_unavailable" not in caplog.text


# ------------------------------------------------ 运营配置与开关：读不到不许被读成人为动作（ADR-0050 §三）


def _ops_config_readable(monkeypatch) -> None:
    """四条来源都**读得到**（库里的内容可以是空的——那是运营没配，不是故障）。

    与 conftest 的默认相反：那里把所有查库接缝打到「没库」，于是每个用例都会走到
    `ops_config_check=unavailable`。要验 ok 的那一半必须显式换回来。
    """
    monkeypatch.setattr(ops, "_query_prompts", list)
    monkeypatch.setattr(ops, "_query_grading_rules", list)
    monkeypatch.setattr(ops, "_query_guard_terms", dict)
    monkeypatch.setattr(ops, "_query_switches", dict)


def _knowledge_readable_but_empty(monkeypatch) -> None:
    """知识域**读得到**、但一条都召不回（`context.status == "empty"`）——严格口径的触发条件。"""
    monkeypatch.setattr(knowledge, "_static", lambda: ([], ()))
    for name in ("_query_entries", "_query_facts", "_query_relations", "_query_entries_by_codes"):
        monkeypatch.setattr(knowledge, name, lambda *args, **kwargs: [])


def _assess_returning(risk_level: int = 2, action: str = "观察 24 小时。"):
    async def fake_assess(**_kwargs):
        return TriageResult(risk_level=risk_level, action_suggestion=action, model_name="stub")

    return fake_assess


def test_ops_config_check_reports_a_read_failure(monkeypatch, caplog):
    """① 运营配置**读不到**：响应报 `ops_config_check=unavailable` + 一条 WARN。

    缺陷原状：`OpsConfig.available` / `detail` 只有 `ops.load()` 内部的一条 WARNING，
    **没有任何出口**——调用方（Java 侧告警）与留痕都看不出「服务在跑，但这一轮用的是
    代码基线」。红线有 `red_flag_check` 承载同一件事，配置加载没有对应字段（ADR-0050 §三）。

    **它不是降级**：回落代码基线之后回答照常（`degraded` 仍为 false）——fail-closed 只影响
    行为（开关按关闭算），而「配置读到了没有」是一个可观测的事实。
    """
    monkeypatch.setattr(main.model_client, "assess", _assess_returning())

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["ops_config_check"] == "unavailable"
    assert body["degraded"] is False
    assert body["risk_level"] == 2
    # 是故障就要留下告警（含哪一项、为什么读不到），而不是只在 ops 内部记一条没人看的 WARNING
    assert "ops_config_unavailable" in caplog.text


def test_ops_config_check_is_ok_when_the_tables_are_reachable(monkeypatch, caplog):
    """反方向：四条来源都读得到 → `ok`。

    **「读到了但库里是空的」不算读不到**（与开关表同一条口径）：`available=False` 只在
    **读取失败**时出现，运营没配内容是个事实，不是故障。这条把两种「空」钉开，
    免得下一次改动把「库里没种子」也报成故障而淹没真正的故障。
    """
    _ops_config_readable(monkeypatch)
    monkeypatch.setattr(main.model_client, "assess", _assess_returning())

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["ops_config_check"] == "ok"
    assert "ops_config_unavailable" not in caplog.text


@pytest.mark.parametrize(
    ("read_switches", "model_called", "expected_model_name", "expects_mark"),
    [
        # 电闸的值**读不到** → 运营的意图不可知：维持现状（照常调模型），但事实进留痕
        (no_db, True, "stub", True),
        # **确实关着**（读到了、值是 false）→ 正常路径，不留「读不到」的痕迹
        (dict, True, "stub", False),
        # **确实开着** → 走规则通道，一次模型都不调（运营的意图照旧被尊重）
        (lambda: {ops.SWITCH_FORCE_RULE_ONLY: True}, False, "rule:switch", False),
    ],
    ids=["config-unreadable", "switch-really-off", "switch-really-on"],
)
def test_force_rule_only_is_never_inferred_from_a_config_failure(
    monkeypatch, caplog, read_switches, model_called, expected_model_name, expects_mark
):
    """② 电闸读不到时**照常调模型**，但「读不到」要进留痕 + 日志（ADR-0050 §三）。

    这是三处里方向最要紧的一处。`force_rule_only` 开着的含义是「撤掉模型、只走规则通道」，
    所以把「读不到」读成「运营拉了闸」= **一次配置故障静默撤掉整条模型通道**：所有咨询都
    变成一句固定话术（连「可能原因」都没有），而纯规则通道本身是一种合法工作模式，留痕
    （`model_name=rule:switch`）与用户侧看起来都像正常运营动作——没人会去查。
    反方向（多花一次本来就在基线里的调用）代价有界、且现在有 WARN + 标记可查，
    所以按「运营的意图不可知」处理：维持现状 + 留痕。

    后两个参数把人为动作钉住：读到了就照运营的选择走（开→规则通道、关→照常调模型），
    方向判断不许把真实的人为动作也一起改掉。

    （`ops_config_check` 在这一组里恒为 unavailable：conftest 把提示词/规则/护栏三条来源
    都打到「没库」，只有开关那一层被本用例覆盖——**规则通道这条响应分支也要带这个字段**。）
    """
    monkeypatch.setattr(ops, "_query_switches", read_switches)
    calls = []

    async def fake_assess(**_kwargs):
        calls.append(1)
        return TriageResult(risk_level=2, action_suggestion="观察 24 小时。", model_name="stub")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert bool(calls) is model_called
    assert body["model_name"] == expected_model_name
    assert body["ops_config_check"] == "unavailable"
    if expects_mark:
        assert "switch:force_rule_only:unreadable" in body["guard_hits"]
        assert "switch_unreadable" in caplog.text
        assert f"{ops.SWITCH_FORCE_RULE_ONLY} 读不到" in caplog.text
    else:
        # 「确实为关」与「读不到」看起来都是 False，但留痕必须分得开
        assert not [hit for hit in body["guard_hits"] if "unreadable" in hit]
        assert "switch_unreadable" not in caplog.text


@pytest.mark.parametrize(
    ("read_switches", "expected_retrieval", "expects_degrade", "expects_mark"),
    [
        # 开关表整体**读不到** → 严格口径按关算（不生成），但「没读到」要留痕 + 告警。
        # 检索那一层同时报 unavailable：两个开关共用一张表，故障是同一件事（各自的上报分开）
        (no_db, "unavailable", False, True),
        # **确实关着** → 行为相同，但不该留「读不到」的痕迹（默认值就是关，不是异常）
        (lambda: {ops.SWITCH_RETRIEVAL_ENABLED: True}, "empty", False, False),
        # **确实开着** + 召回为空 → 走严格口径（不生成）；此时也不该有「读不到」的痕迹
        (lambda: {ops.SWITCH_RETRIEVAL_ENABLED: True, ops.SWITCH_RETRIEVAL_STRICT: True},
         "empty", True, False),
    ],
    ids=["config-unreadable", "switch-really-off", "switch-really-on"],
)
def test_retrieval_strict_is_not_inferred_from_a_config_failure(
    monkeypatch, caplog, read_switches, expected_retrieval, expects_degrade, expects_mark
):
    """② 严格口径读不到 → 按关算（**不**在故障时把它打开），但要以留痕 + WARN 说明。

    方向与电闸那处相反、结论相同：值一律取关闭（保持现状），差别只在有没有「读不到」的痕迹。
    故障时把严格口径打开 = 让每一次咨询都变成固定话术，那是 ADR-0033 明确不接受的全局降级，
    所以这里不把 fail-closed 解释成「更保守的那一侧」。
    """
    monkeypatch.setattr(ops, "_query_switches", read_switches)
    _knowledge_readable_but_empty(monkeypatch)
    monkeypatch.setattr(main.model_client, "assess", _assess_returning())

    body = client.post("/internal/consult", json=make_request(), headers=TOKEN).json()

    assert body["degraded"] is expects_degrade
    assert body["retrieval_check"] == expected_retrieval
    if expects_degrade:
        assert body["degrade_code"] == "knowledge_empty"
        # 降级路径也要如实带配置状态：`_ConfigMarks` 每个分支都传，漏一处就等于静默报正常
        assert body["ops_config_check"] == "unavailable"
    if expects_mark:
        assert "switch:retrieval_strict:unreadable" in body["guard_hits"]
        assert "switch_unreadable" in caplog.text
    else:
        assert not [hit for hit in body["guard_hits"] if "unreadable" in hit]
        assert "switch_unreadable" not in caplog.text


# ---------------------------------------------------------------- 队列/重试


def test_repair_retry_happens_once_then_gives_up(monkeypatch):
    """模型不调工具时重试一次（补一句要求），两次都失败才抛 —— 这是 #61 的「二次失败拔高风险」。"""
    responses = [
        {"choices": [{"message": {"content": "我觉得是轻微肠胃不适。"}, "finish_reason": "stop"}], "model": "m"},
        {"choices": [{"message": {"content": "还是不用工具。"}, "finish_reason": "stop"}], "model": "m"},
    ]
    seen_prompts = []

    async def fake_chat(messages, model, tool):
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


# ---------------------------------------------------------------- 缺陷回归（2026-09-28 测试报告）


@pytest.mark.parametrize(
    "risk",
    ['true', 'false', '2.0', '"3"', 'null', '[]'],
)
def test_validate_rejects_non_integer_risk_level(risk):
    """risk_level 必须是 1/2/3 的整数。

    这组用例来自一个真实缺陷（D4）：Python 里 `True == 1`，所以 `{"risk_level": true}` 原先能通过
    `risk_level not in (1, 2, 3)` 的检查，再被 pydantic 转成 1——**一个类型错误的分级被当成「绿」放行**。
    浮点与字符串同理（`2.0 in (1,2,3)` 也为真）。红线/分级是安全相关字段，宁可降级也不能猜。
    """
    payload = json.dumps({"risk_level": json.loads(risk), "action_suggestion": "观察"})
    with pytest.raises(ModelOutputInvalid):
        model_client._validate_arguments(payload)


def test_red_level_forces_need_hospital():
    """判红就必须建议就医：模型说「不用去医院」也照样置真（交付文档 9.5「红色必带就医建议」）。"""
    fields = model_client._validate_arguments(
        json.dumps({"risk_level": 3, "need_hospital": False, "action_suggestion": "观察一下"})
    )
    assert fields["risk_level"] == 3
    assert fields["need_hospital"] is True


def test_chat_treats_non_json_body_as_unavailable(monkeypatch):
    """200 但不是 JSON：必须降级，不能变成未捕获异常 500（本文件头第 5 条纪律）。"""

    class FakeResponse:
        status_code = 200
        text = "<html>gateway</html>"

        def json(self):
            raise json.JSONDecodeError("Expecting value", "<html>gateway</html>", 0)

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def post(self, *args, **kwargs):
            return FakeResponse()

    monkeypatch.setattr(model_client.httpx, "AsyncClient", lambda **kwargs: FakeClient())

    with pytest.raises(ModelUnavailable):
        asyncio.run(model_client._chat([{"role": "user", "content": "hi"}], "m", prompts.REPORT_TOOL))


def test_extract_tool_arguments_survives_malformed_shape():
    """tool_calls 缺 function.arguments 时是降级，不是 KeyError。"""
    with pytest.raises(ModelOutputInvalid):
        model_client._extract_tool_arguments({"choices": [{"message": {"tool_calls": [{}]}}], "model": "m"})
    with pytest.raises(ModelOutputInvalid):
        model_client._extract_tool_arguments({"choices": [{"message": {"tool_calls": ["oops"]}}], "model": "m"})


def test_inline_images_converts_to_data_url(monkeypatch):
    """图片要取回本地、内联成 data URL：模型供应商拉不到我们内网的签名地址（D7）。"""
    png = b"\x89PNG\r\n\x1a\n" + b"0" * 32

    class FakeResponse:
        status_code: int = 200
        content: bytes = png
        headers: ClassVar[dict[str, str]] = {"content-type": "image/png"}

    class FakeClient:
        def __init__(self, **kwargs):
            self.requested = []

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url):
            self.requested.append(url)
            return FakeResponse()

    monkeypatch.setattr(model_client.httpx, "AsyncClient", FakeClient)

    inlined = asyncio.run(model_client.inline_images(["http://127.0.0.1:8080/api/v1/open/files/1?token=t"]))

    assert len(inlined) == 1
    assert inlined[0].startswith("data:image/png;base64,")


def test_inline_images_rejects_non_image(monkeypatch):
    """取回的不是图片（或压根没取到）：明确抛 ImageUnavailable，好让上层如实告诉用户「图没看」。"""

    class FakeResponse:
        status_code: int = 200
        content: bytes = b"<html>login</html>"
        headers: ClassVar[dict[str, str]] = {"content-type": "text/html"}

    class FakeClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url):
            return FakeResponse()

    monkeypatch.setattr(model_client.httpx, "AsyncClient", FakeClient)

    with pytest.raises(model_client.ImageUnavailable):
        asyncio.run(model_client.inline_images(["http://127.0.0.1:8080/x"]))


def test_image_unavailable_degrades_distinctly(monkeypatch):
    """取不到图与「模型看不见图」是两件事，降级码要分开，事后归因才分得清。"""

    async def fake_assess(**kwargs):
        raise model_client.ImageUnavailable("图片下载失败（ConnectError）")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = client.post(
        "/internal/consult",
        json=make_request(media_urls=["http://127.0.0.1:8080/api/v1/open/files/9?token=x"]),
        headers=TOKEN,
    ).json()

    assert body["degraded"] is True
    assert body["degrade_code"] == "image_unavailable"
    assert body["images_used"] == 0


# ---------------------------------------------------------------- 对外暴露面


def test_docs_and_openapi_are_disabled():
    """内部服务的请求结构不外泄：交互式文档与 openapi.json 一律关闭（D17）。"""
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_healthz_is_open_but_bare():
    """探针用的 /healthz 不需要令牌，但也不吐配置与能力信息。"""
    body = client.get("/healthz").json()
    assert body == {"status": "ok"}


def test_internal_health_requires_token():
    assert client.get("/internal/health").status_code == 401


def test_usage_survives_repair_retry_failure(monkeypatch):
    """修复重试先成功一次、再失败：**那一次的钱要能带出去**（否则预算系统性偏低）。

    ADR-0026 说的是「记录每次模型调用的 token 用量」，只在成功返回时带用量就违背了它。
    """
    calls = {"n": 0}

    async def fake_chat(messages, model, tool):
        calls["n"] += 1
        if calls["n"] == 1:
            # 第一次：有用量，但没调工具（触发修复重试）
            return {"choices": [{"message": {"content": "我直接说吧"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 700, "completion_tokens": 300}, "model": "m"}
        raise ModelUnavailable("第二轮连不上")

    monkeypatch.setattr(model_client, "_chat", fake_chat)

    with pytest.raises(ModelUnavailable) as caught:
        asyncio.run(model_client.assess(system_prompt="s", user_prompt="u"))

    assert caught.value.prompt_tokens == 700
    assert caught.value.completion_tokens == 300


def test_usage_survives_exhausted_repair_retries(monkeypatch):
    """两次都没调工具：累计用量要随 ModelOutputInvalid 带出去。"""
    async def fake_chat(messages, model, tool):
        return {"choices": [{"message": {"content": "还是不说"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50}, "model": "m"}

    monkeypatch.setattr(model_client, "_chat", fake_chat)

    with pytest.raises(ModelOutputInvalid) as caught:
        asyncio.run(model_client.assess(system_prompt="s", user_prompt="u"))

    assert caught.value.prompt_tokens == 200      # 两次各 100
    assert caught.value.completion_tokens == 100


def test_inline_images_passes_data_urls_through(monkeypatch):
    """已经是 data URL 的图直接透传，不去 GET 它。

    实测踩到的：`httpx` 只认 http(s)，拿 `data:` 去 GET 会抛错 → 降级成「读不到图」。
    而「字节已经在手里」是最不该失败的一种情况（调用方自己内联的、或将来由 Java 侧内联的）。
    """
    data_url = "data:image/png;base64,iVBORw0KGgo="

    class NoFetchClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url):  # pragma: no cover —— 被调用就是失败
            raise AssertionError("data URL 不该发起 GET")

    monkeypatch.setattr(model_client.httpx, "AsyncClient", NoFetchClient)

    assert asyncio.run(model_client.inline_images([data_url])) == [data_url]


def test_data_url_still_goes_through_the_limits(monkeypatch):
    """调用方自己内联的 data URL 也要过类型与体积把关——否则等于留了一条不限量的旁路。"""
    oversize = base64.b64encode(b"\x00" * (settings.ai_max_image_bytes + 1)).decode()
    with pytest.raises(model_client.ImageUnavailable):
        asyncio.run(model_client.inline_images(["data:image/png;base64," + oversize]))

    with pytest.raises(model_client.ImageUnavailable):
        asyncio.run(model_client.inline_images(["data:text/plain;base64,aGk="]))

    # 合法的小图照常直通
    ok = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 16).decode()
    assert asyncio.run(model_client.inline_images(["data:image/png;base64," + ok])) == [
        "data:image/png;base64," + ok
    ]
