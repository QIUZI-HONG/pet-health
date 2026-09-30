"""咨询链路上的引用与知识接入（切片 #101 的验收标准）。

这一份走完整的 `POST /internal/consult`（模型桩掉、检索用手工造的 Context），
验的是**组装与口径**而不是检索算法（那在 test_knowledge.py）：

- 回答带来源引用，来源可追溯到知识条目；
- **只有 vetted 条目能进 citations**，未复核条目要走 unvetted_hits 并让 C 端能提示（ADR-0033）；
- 引用校验剔除不在召回集内的编号，编造来源的结论整条剔除；
- 检索读不到 / 召回为空 → **咨询照常成功**（citations 空、retrieval_check 如实上报）；
- 运营开关：`force_rule_only` 不调模型、`retrieval_strict` 才降级；
- 分级规则（#103）命中即抬档，且抬到红时把「立即送医」放到第一条。
"""

import pytest
from fastapi.testclient import TestClient

from app import knowledge, main, ops, prompts
from app.config import settings
from app.model_client import TriageResult
from app.models import ConsultInput, ConsultRequest, PetContext

from .conftest import TEST_INTERNAL_TOKEN

client = TestClient(main.app)
TOKEN = {"X-Internal-Token": TEST_INTERNAL_TOKEN}

VETTED = knowledge.STATUS_VETTED
PENDING = knowledge.STATUS_PENDING


def make_request(text: str = "今天吐了两次，精神还行",
                 *, species: int = 1, birth_date: str = "2023-05-01") -> dict:
    return ConsultRequest(
        trace_id="trace-kb-1",
        user_id=1,
        pet=PetContext(id=1, species=species, breed="柯基", birth_date=birth_date, weight=12.5),
        input=ConsultInput(type="text", text=text, media_urls=[]),
    ).model_dump()


def item(code: str, *, status: str = PENDING, title: str = "犬猫呕吐的家庭观察要点") -> knowledge.KnowledgeItem:
    return knowledge.KnowledgeItem(
        code=code,
        title=title,
        summary="记录次数与性状，单次呕吐可先观察",
        body="呕吐后先记录次数与时间，观察精神与饮水情况。",
        category_code="triage",
        species_scope="all",
        age_stage_scope="all",
        source_title="合作兽医审核稿（工程整理，待复核）",
        source_version="待复核稿 v1",
        source_url=None,
        review_status=status,
        confidence="medium",
        layers=(knowledge.LAYER_L3,),
        score=0.9,
    )


@pytest.fixture
def stub_retrieval(monkeypatch):
    """可控的检索结果：默认「召回一条未复核条目」，用例按需覆盖。"""
    state = {"context": knowledge.Context(items=(item("K-0010"),), status="ok", detail="词=呕吐")}

    def fake_search(*_args, **_kwargs):
        return state["context"]

    monkeypatch.setattr(main.knowledge, "search", fake_search)
    return state


def stub_model(monkeypatch, *, risk_level: int = 2, causes: list[str] | None = None,
               action: str = "观察 24 小时，若继续呕吐请就医。", care: list[str] | None = None,
               citations: list[str] | None = None):
    """把一个分级结果塞进模型桩。`citations` 是工具参数里那份模型自报的编号。"""
    async def fake_assess(**_kwargs):
        return TriageResult(
            risk_level=risk_level,
            possible_causes=causes if causes is not None else [],
            action_suggestion=action,
            need_hospital=True,
            care_tips=care or [],
            citations=citations or [],
            model_name="stub", model_version="stub", latency_ms=1,
        )

    monkeypatch.setattr(main.model_client, "assess", fake_assess)


def consult(text: str = "今天吐了两次，精神还行", *, species: int = 1,
            birth_date: str = "2023-05-01") -> dict:
    response = client.post("/internal/consult",
                           json=make_request(text, species=species, birth_date=birth_date),
                           headers=TOKEN)
    assert response.status_code == 200, response.text
    return response.json()


# ---------------------------------------------------------------- 索引到的条目进上下文


def test_retrieved_entries_are_injected_into_the_prompt(monkeypatch, stub_retrieval):
    """召回到的条目要真的进模型输入，并写明「可能原因必须来自它们」。"""
    seen = {}

    async def fake_assess(**kwargs):
        seen.update(kwargs)
        return TriageResult(risk_level=2, action_suggestion="观察", model_name="stub", model_version="s")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    consult()

    assert "【知识条目】" in seen["user_prompt"]
    assert "K-0010" in seen["user_prompt"]
    assert "只能引用这些编号" in seen["user_prompt"]
    # 工具定义与提示词来自同一行配置（#103 之后两者一起版本化）
    assert seen["tool"]["function"]["name"] == "report_triage"


# ---------------------------------------------------------------- 引用口径


def test_only_vetted_entries_become_citations(monkeypatch, stub_retrieval):
    """模型引用了未复核条目：它在 citations 里**不出现**，但要让调用方知道用到了未复核内容。"""
    stub_retrieval["context"] = knowledge.Context(
        items=(item("K-0001", status=VETTED, title="犬核心疫苗的接种时间表"),
               item("K-0010", status=PENDING)),
        status="ok",
        detail="",
    )
    stub_model(monkeypatch, causes=["可能：饮食不当 [K-0010]", "可能：疫苗后反应 [K-0001]"])

    body = consult()

    assert [citation["entry_id"] for citation in body["citations"]] == ["K-0001"]
    assert body["citations"][0]["review_status"] == VETTED
    assert body["unvetted_hits"] == ["K-0010"]
    assert body["retrieval_check"] == "ok"


def test_fabricated_citation_drops_that_conclusion(monkeypatch, stub_retrieval):
    """引用了不在召回集里的编号，且没有别的有效编号 → 整条剔除并留痕（#101 第三条）。"""
    stub_model(monkeypatch, causes=["可能：急性胰腺炎 [K-9999]", "可能：饮食不当 [K-0010]"])

    body = consult()

    assert body["possible_causes"] == ["可能：饮食不当 [K-0010]"]
    assert "citation:K-9999" in body["guard_hits"]


def test_unbacked_conclusions_are_kept_but_flagged(monkeypatch, stub_retrieval):
    """一条引用都不给的结论保留（默认口径），但留痕记下「无来源」——它是召回缺口/提示词失效的信号。"""
    stub_model(monkeypatch, causes=["可能：饮食不当"])

    body = consult()

    assert body["possible_causes"] == ["可能：饮食不当"]
    assert "citation:unbacked:1" in body["guard_hits"]
    assert body["citations"] == []  # 没有有效引用 → C 端不许说「基于知识库」


def test_model_self_reported_citation_is_validated_too(monkeypatch, stub_retrieval):
    """工具参数里的 citations 也要过召回集校验：编造的编号进不了 citations。"""
    stub_retrieval["context"] = knowledge.Context(
        items=(item("K-0001", status=VETTED),), status="ok", detail="")
    stub_model(monkeypatch, action="观察 24 小时。", citations=["K-0001", "K-8888"])

    body = consult()

    assert [citation["entry_id"] for citation in body["citations"]] == ["K-0001"]


# ---------------------------------------------------------------- 降级与开关


def test_retrieval_unavailable_still_answers(monkeypatch):
    """读不到知识域：咨询照常成功（不 degraded），只是没有来源、并如实上报 retrieval_check。"""
    monkeypatch.setattr(
        main.knowledge, "search",
        lambda *_a, **_k: knowledge.Context(status="unavailable", detail="连不上知识库"),
    )
    stub_model(monkeypatch, risk_level=2, causes=["可能：饮食不当"])

    body = consult()

    assert body["degraded"] is False
    assert body["retrieval_check"] == "unavailable"
    assert body["citations"] == []
    assert body["risk_level"] == 2


def test_empty_retrieval_keeps_answering_by_default(monkeypatch):
    """召回为空默认照常回答（citations 空、无来源口径）。

    **与 #101 的「检索为空不生成」有出入，是刻意的**：语料只有几十条，默认开严格口径会把
    绝大多数咨询变成固定话术（ADR-0025 对「不熔断」的同一取向）。严格模式由运营开关控制，
    下面两条用例分别验两种口径。
    """
    monkeypatch.setattr(
        main.knowledge, "search",
        lambda *_a, **_k: knowledge.Context(status="empty", detail="召回为空"),
    )
    stub_model(monkeypatch, causes=["可能：饮食不当"])

    body = consult()

    assert body["degraded"] is False
    assert body["retrieval_check"] == "empty"
    assert body["citations"] == []


def test_strict_switch_degrades_on_empty_retrieval(monkeypatch):
    """运营打开严格口径：召回为空 → 降级为固定话术（#101 的「检索为空不生成」）。"""
    monkeypatch.setattr(
        main.knowledge, "search",
        lambda *_a, **_k: knowledge.Context(status="empty", detail="召回为空"),
    )
    monkeypatch.setattr(
        main.ops, "load",
        lambda force=False: ops.OpsConfig(
            prompt=ops.code_baseline_prompt(),
            switches={ops.SWITCH_RETRIEVAL_STRICT: True},
            # 开关表是**读到了**的（否则 retrieval_check 会被报成 unavailable 而不是 disabled）
            switches_available=True,
            available=True,
        ),
    )
    stub_model(monkeypatch, causes=[])

    body = consult()

    assert body["degraded"] is True
    assert body["degrade_code"] == "knowledge_empty"
    assert body["citations"] == []
    assert body["retrieval_check"] == "empty"
    # 用户看到的是保守建议，不是错误码（ADR-0026 的口径）
    assert body["need_hospital"] is True


def test_force_rule_only_switch_skips_the_model(monkeypatch, stub_retrieval):
    """运营把电闸拉了：不调模型，只走规则通道（ADR-0010 的降级开关）。"""
    monkeypatch.setattr(
        main.ops, "load",
        lambda force=False: ops.OpsConfig(
            prompt=ops.code_baseline_prompt(),
            grading_rules=(ops.GradingRule(code="GR-001", name="幼宠呕吐", terms=("呕吐",), min_level=2,
                                           advice="幼年动物脱水快，建议尽快就医。"),),
            switches={ops.SWITCH_FORCE_RULE_ONLY: True},
            switches_available=True,
            available=True,
        ),
    )
    calls = []

    async def fake_assess(**_kwargs):  # pragma: no cover —— 被调用就是失败
        calls.append(1)
        raise AssertionError("纯规则通道不该调模型")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = consult()

    assert calls == []
    assert body["degraded"] is False
    assert body["model_name"] == "rule:switch"
    assert body["grading_rule_hits"] == []  # 柯基 3 岁，不落幼宠规则
    assert body["risk_level"] == 2


# ---------------------------------------------------------------- 分级规则（#103）


def test_grading_rule_escalates_and_puts_advice_first(monkeypatch, stub_retrieval):
    """分级规则命中：结论抬到规则下限，且规则给的建议排在 care_tips 第一条。

    抬到红尤其要这样：模型可能说「再观察一天」，而规则说这属于急症——
    用户先看到的那一句必须是「立即送医」（宁严勿松）。
    """
    monkeypatch.setattr(
        main.ops, "load",
        lambda force=False: ops.OpsConfig(
            prompt=ops.code_baseline_prompt(),
            grading_rules=(ops.GradingRule(code="GR-003", name="老年宠精神与食欲变化", terms=("没精神",),
                                           min_level=3, age_stage_scope="senior",
                                           advice="老年动物耐受差，建议立即就医。"),),
            switches={}, switches_available=True, available=True),
    )
    stub_model(monkeypatch, risk_level=1, action="先观察一天。", care=["记录饮水"])

    body = consult("最近没精神，吃得也少", birth_date="2014-03-01")  # 11 岁 → senior，规则才该命中

    assert body["risk_level"] == 3
    assert body["need_hospital"] is True
    assert body["care_tips"][0] == "老年动物耐受差，建议立即就医。"
    assert body["grading_rule_hits"] == ["GR-003"]


def test_grading_rule_advice_goes_through_the_guardrail(monkeypatch, stub_retrieval):
    """运营写的规则建议也要过输出护栏：越界表述会被改写（运营文案不是法外之地）。"""
    monkeypatch.setattr(
        main.ops, "load",
        lambda force=False: ops.OpsConfig(
            prompt=ops.code_baseline_prompt(),
            grading_rules=(ops.GradingRule(code="GR-009", name="坏规则", terms=("没精神",), min_level=2,
                                           advice="建议用药三天。"),),
            switches={}, switches_available=True, available=True),
    )
    stub_model(monkeypatch, risk_level=1, action="观察。", care=[])

    body = consult("最近没精神", birth_date="2014-03-01")

    assert body["risk_level"] == 2
    assert body["care_tips"] == [], "越界建议被整句改写后不留空条目"
    assert any(hit.startswith("phrase:") for hit in body["guard_hits"])


def test_prompt_version_comes_from_the_loaded_template(monkeypatch, stub_retrieval):
    """留痕里的 prompt_version 来自**实际用的那版提示词**（库里的版本号），不是代码常量。"""
    monkeypatch.setattr(
        main.ops, "load",
        lambda force=False: ops.OpsConfig(
            prompt=ops.PromptTemplate(version="p7-gray", system_prompt="系统提示词", tool_schema={},
                                      origin="db"),
            switches={}, switches_available=True, available=True),
    )
    stub_model(monkeypatch)

    body = consult()

    assert body["prompt_version"] == "p7-gray"
    assert body["prompt_version"] != settings.prompt_version


def test_baseline_prompt_is_used_when_db_is_unavailable(monkeypatch, stub_retrieval):
    """读不到运营配置：退回代码基线（否则库里读不到就没有可用提示词）。"""
    seen = {}

    async def fake_assess(**kwargs):
        seen.update(kwargs)
        return TriageResult(risk_level=1, action_suggestion="继续观察", model_name="stub")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)

    body = consult()

    assert seen["system_prompt"] == prompts.SYSTEM_PROMPT
    assert body["prompt_version"] == settings.prompt_version
