"""运营可调项（切片 #103）：提示词带版本号与灰度、护栏词表入库、读不到回落代码基线。

这些用例全部用手工造的行喂给查库接缝（`ops._query_*`），所以不需要数据库——
真库上的种子与 SQL 由 `server/ph-boot` 的集成测试覆盖（那里有真 MySQL）。
"""

import pytest

from app import guardrails, ops
from app.config import settings


def prompt_row(version: str, gray: int, *, prompt: str = "系统提示词", tool: str | None = None) -> dict:
    return {
        "version": version,
        "system_prompt": prompt,
        "tool_schema": tool or '{"type":"function","function":{"name":"report_triage"}}',
        "gray_ratio": gray,
    }


@pytest.fixture
def db_rows(monkeypatch):
    """把四条查库接缝换成可控的数据；用例只改自己关心的那一组。

    接缝的返回形状与真实实现一致：提示词与分级规则给**行**，护栏词表给**按 kind 分好组的 dict**，
    开关给 dict（形状不一致时 `dict.update` 会把行的键当成配置项——这个坑踩过一次）。
    """
    state = {"prompts": [], "rules": [], "guard": {}, "switches": {}}

    monkeypatch.setattr(ops, "_query_prompts", lambda: state["prompts"])
    monkeypatch.setattr(ops, "_query_grading_rules", lambda: state["rules"])
    monkeypatch.setattr(ops, "_query_guard_terms", lambda: state["guard"])
    monkeypatch.setattr(ops, "_query_switches", lambda: state["switches"])
    return state


# ---------------------------------------------------------------- 灰度分流


def test_gray_routing_picks_version_by_ratio(db_rows):
    """灰度按比例分流，**roll 作为参数**（随机数藏在函数里就没法测）。"""
    rows = [prompt_row("p2", 30), prompt_row("p1", 20)]

    assert ops._pick_prompt(rows, roll=0.0).version == "p2"
    assert ops._pick_prompt(rows, roll=0.29).version == "p2"
    assert ops._pick_prompt(rows, roll=0.31).version == "p1"
    assert ops._pick_prompt(rows, roll=0.49).version == "p1"


def test_gray_remainder_falls_back_to_the_code_baseline(db_rows):
    """比例之和不到 100：剩下的落到**代码基线**（而不是「没人想到会走到的那条路」）。"""
    rows = [prompt_row("p2", 30)]

    assert ops._pick_prompt(rows, roll=0.9).version == settings.prompt_version
    assert ops._pick_prompt([], roll=0.0).origin == "code"


def test_unusable_prompt_row_falls_back_to_baseline(db_rows):
    """缺正文或缺工具定义的行直接用不了：回落到基线，而不是拿半份配置去调模型。"""
    assert ops._pick_prompt([prompt_row("p2", 100, prompt="")], roll=0.0).origin == "code"
    assert ops._pick_prompt([prompt_row("p2", 100, tool="不是 JSON")], roll=0.0).origin == "code"


# ---------------------------------------------------------------- 加载与回落


def test_load_reads_everything_from_the_db(db_rows):
    """四条都读得到：提示词、分级规则、护栏词表、开关一起生效，available=True。"""
    db_rows["prompts"] = [prompt_row("p9", 100, prompt="库里的提示词")]
    db_rows["rules"] = [{"code": "GR-1", "name": "规则", "match_terms": '["呕吐"]', "min_level": 2,
                         "species_scope": "all", "age_stage_scope": "all", "advice": "尽快就医。"}]
    db_rows["guard"] = {"drug": ("某某新药",), "phrase": ("开点药",)}
    db_rows["switches"] = {ops.SWITCH_RETRIEVAL_ENABLED: True}

    config = ops.load(force=True)

    assert config.available is True
    assert config.prompt.version == "p9"
    assert config.prompt.origin == "db"
    assert config.grading_rules[0].terms == ("呕吐",)
    assert config.guard_terms["drug"] == ("某某新药",)
    assert config.switch(ops.SWITCH_RETRIEVAL_ENABLED) is True
    # 没配的开关一律按关闭算（安全方向：开关是运营主动打开的闸门）
    assert config.switch(ops.SWITCH_RETRIEVAL_STRICT) is False

def test_load_falls_back_to_code_baseline_when_db_is_down(monkeypatch):
    """读不到库：全部回落代码基线并如实标 available=False（**不抛异常**——咨询还得跑）。"""
    def boom(*_args, **_kwargs):
        raise RuntimeError("连不上 MySQL")

    for name in ("_query_prompts", "_query_grading_rules", "_query_guard_terms", "_query_switches"):
        monkeypatch.setattr(ops, name, boom)

    config = ops.load(force=True)

    assert config.available is False
    assert config.prompt.origin == "code"
    assert config.prompt.version == settings.prompt_version
    assert config.prompt.system_prompt.startswith("你是一只宠物")
    assert config.grading_rules == ()
    assert set(config.guard_terms["drug"]) == set(guardrails.CODE_DRUG_TERMS)
    assert config.switches == {}


def test_guard_terms_from_db_actually_apply(db_rows):
    """库里加一个药名，护栏立刻按它拦（这是「运营改完即时生效」的最小验证）。"""
    db_rows["guard"] = {"drug": ("某某新药",), "phrase": ("开点药",)}

    rewritten, hits = guardrails.review("建议喂点某某新药")

    assert rewritten == guardrails.REPLACEMENT
    assert "drug:某某新药" in hits


def test_empty_guard_table_keeps_the_code_baseline(db_rows):
    """库里词表为空（迁移没跑完/运营清空）时用代码基线，**不能退化成「什么词都不拦」**。"""
    db_rows["guard"] = {}

    rewritten, hits = guardrails.review("可以喂点阿莫西林")

    assert rewritten == guardrails.REPLACEMENT
    assert any(hit.startswith("drug:") for hit in hits)


def test_grading_rules_without_terms_are_ignored(db_rows):
    """没有命中词的规则永远不会命中，留着只会让运营以为它生效了——加载时直接丢掉。"""
    db_rows["rules"] = [{"code": "GR-EMPTY", "name": "空规则", "match_terms": "[]", "min_level": 3}]

    config = ops.load(force=True)

    assert config.grading_rules == ()
