"""硬红线预检（切片 #98 / ADR-0021）的测试。

分两层：
- **纯匹配逻辑**（脱离数据库）：中文子串、变体、物种/年龄过滤、无命中；
- **与咨询链路的关系**：命中即判红且**不调模型**、词表不可用时不静默放行。

红线是「红色 100% 召回」这条验收标准的机械保障，所以这里的用例按「能不能拦住」写，
而不是按「函数返回什么」写。
"""

import pytest

from app import main, red_flags
from app.red_flags import RedFlag

from .conftest import TEST_INTERNAL_TOKEN

TOKEN = {"X-Internal-Token": TEST_INTERNAL_TOKEN}


def rule(code="RF-001", pattern="中毒", variants=(), species="all", age="all", level=3) -> RedFlag:
    return RedFlag(
        code=code,
        pattern=pattern,
        variants=tuple(variants),
        species_scope=species,
        age_stage_scope=age,
        level=level,
        action_hint=f"{pattern}：立即送医",
    )


# ---------------------------------------------------------------- 匹配


def test_chinese_substring_is_matched():
    """中文不做分词：词表里写「中暑」，正文里「好像中暑了」必须命中。"""
    hits = red_flags.match("今天遛狗回来好像中暑了，一直喘", (rule(pattern="中暑"),))

    assert [hit.code for hit in hits] == ["RF-001"]
    assert hits[0].term == "中暑"


@pytest.mark.parametrize(
    "text",
    [
        "吐了一整天，喝水都吐",
        "从昨晚开始反复呕吐，精神很差",
        "呕吐不止，已经四次了",
    ],
)
def test_variants_cover_colloquial_phrasings(text):
    """口语变体靠词表覆盖（ADR-0021 选了关键词而不是向量近似，代价就用变体补）。"""
    deworm = rule(code="RF-007", pattern="持续呕吐", variants=("呕吐不止", "吐了一整天", "反复呕吐"))

    assert red_flags.match(text, (deworm,))


def test_no_match_returns_empty():
    assert red_flags.match("今天精神不错，食欲正常", (rule(),)) == []


def test_species_scoped_rule_does_not_fire_for_other_species():
    """猫尿闭是急症，但不能拿它去判一只狗——犬猫混答是这类产品最常见的错误来源。"""
    cat_only = rule(code="RF-012", pattern="尿不出", species="cat")

    assert red_flags.match("尿不出", (cat_only,), species=1) == []          # 犬
    assert red_flags.match("尿不出", (cat_only,), species=2)                 # 猫


def test_age_scoped_rule_only_fires_for_that_stage():
    puppy = rule(code="RF-013", pattern="幼犬不吃", age="puppy_kitten")

    assert red_flags.match("幼犬不吃", (puppy,), age_stage="adult") == []
    assert red_flags.match("幼犬不吃", (puppy,), age_stage="puppy_kitten")


def test_one_rule_counts_once_even_if_several_terms_hit():
    """留痕要的是「哪条规则触发」，不是「命中几个词」——同一条重复出现会把留痕淹掉。"""
    hits = red_flags.match("又吐血又呕血", (rule(code="RF-005", pattern="呕血", variants=("吐血",)),))

    assert len(hits) == 1


# ---------------------------------------------------------------- 年龄段


@pytest.mark.parametrize(
    "birth_date,species,expected",
    [
        ("2026-08-01", 1, "puppy_kitten"),   # 2 个月的犬
        ("2023-05-01", 1, "adult"),          # 3 岁
        ("2015-01-01", 1, "senior"),         # 11 岁（犬 > 7 岁）
        ("2018-01-01", 2, "adult"),          # 8 岁（猫 > 10 岁才算老年）
        ("2010-01-01", 2, "senior"),         # 16 岁的猫
        (None, 1, "all"),                    # 没填生日：不过滤，但不能报错
        ("不是日期", 1, "all"),
    ],
)
def test_age_stage_of(birth_date, species, expected):
    assert red_flags.age_stage_of(birth_date, species) == expected


# ---------------------------------------------------------------- 词表加载


def test_load_rules_marks_unavailable_instead_of_raising(monkeypatch):
    """读不到词表时**不能抛**（会让整次咨询 500），但必须标成不可用——
    红线是安全网，悄悄少一层比少一层本身更危险。"""

    def boom():
        raise RuntimeError("连不上 MySQL")

    monkeypatch.setattr(red_flags, "_query", boom)
    result = red_flags.load_rules(force=True)

    assert result.available is False
    assert result.rules == ()
    assert "连不上" in result.detail


# ---------------------------------------------------------------- 与咨询链路的关系


def make_request(text: str) -> dict:
    from app.models import ConsultInput, ConsultRequest, PetContext

    return ConsultRequest(
        trace_id="trace-red-flag",
        user_id=1,
        pet=PetContext(id=1, species=1, breed="柯基", birth_date="2023-05-01", weight=12.5),
        input=ConsultInput(type="text", text=text, media_urls=[]),
    ).model_dump()


def test_red_flag_short_circuits_without_calling_model(monkeypatch):
    """命中红线即判红，**模型一次都不调**——这是 ADR-0021 的核心决定。"""
    calls = []

    async def fake_assess(**kwargs):
        calls.append(kwargs)
        raise AssertionError("红线命中时不该调模型")

    monkeypatch.setattr(main.model_client, "assess", fake_assess)
    monkeypatch.setattr(
        main.red_flags, "load_rules",
        lambda force=False: red_flags.LoadResult(
            rules=(rule(code="RF-007", pattern="呕吐不止"),), available=True),
    )

    from fastapi.testclient import TestClient

    body = TestClient(main.app).post(
        "/internal/consult", json=make_request("从昨晚开始呕吐不止"), headers=TOKEN).json()

    assert calls == []
    assert body["risk_level"] == 3
    assert body["need_hospital"] is True
    assert body["red_flag_hits"] == ["RF-007"]
    assert body["model_name"] == "rule:red_flag"
    assert body["guard_hits"] == ["RF-007:呕吐不止"]


def test_rule_check_unavailable_is_visible_in_response(monkeypatch):
    """词表不可用时照样给答复，但响应里要写明这一层没生效（Java 侧据此告警）。"""
    async def fake_assess(**kwargs):
        from app.model_client import TriageResult

        return TriageResult(
            risk_level=1, possible_causes=["饮食不当"], action_suggestion="观察一天",
            need_hospital=False, care_tips=[], model_name="stub", model_version="stub",
            latency_ms=1,
        )

    monkeypatch.setattr(main.model_client, "assess", fake_assess)
    monkeypatch.setattr(
        main.red_flags, "load_rules",
        lambda force=False: red_flags.LoadResult(rules=(), available=False, detail="db down"),
    )

    from fastapi.testclient import TestClient

    body = TestClient(main.app).post(
        "/internal/consult", json=make_request("今天精神不错"), headers=TOKEN).json()

    assert body["red_flag_check"] == "unavailable"
    assert body["risk_level"] == 1


def test_guardrail_rewrites_model_output(monkeypatch):
    """输出层护栏：模型给出剂量时被改写并留痕（交付文档 9.5 的三个绝不）。"""
    async def fake_assess(**kwargs):
        from app.model_client import TriageResult

        return TriageResult(
            risk_level=2,
            possible_causes=["确诊为急性肠胃炎"],
            action_suggestion="可以口服蒙脱石散 2.5mg，一天两次",
            need_hospital=True,
            care_tips=["禁食 4 小时"],
            model_name="stub", model_version="stub", latency_ms=1,
        )

    monkeypatch.setattr(main.model_client, "assess", fake_assess)
    monkeypatch.setattr(
        main.red_flags, "load_rules",
        lambda force=False: red_flags.LoadResult(rules=(), available=True),
    )

    from fastapi.testclient import TestClient

    body = TestClient(main.app).post(
        "/internal/consult", json=make_request("今天吐了一次，精神还行"), headers=TOKEN).json()

    assert "剂量请遵医嘱" in body["action_suggestion"]
    assert "2.5mg" not in body["action_suggestion"]
    assert body["guard_hits"], "护栏命中必须留痕，否则事后无法归因"
    assert body["possible_causes"] == [], "确诊类表述被整句剔除"
    assert body["care_tips"] == ["禁食 4 小时"], "正常的照护建议不受影响"


def test_empty_rule_table_is_reported_as_unavailable(monkeypatch):
    """词表查得到、但一条都没有 —— 同样是「这一层没生效」，不能报 ok。

    真实缺陷（D5）：表被清空、迁移没跑、运营把 enabled 全关，都会走到这里；
    原先 `available=True` 写死，于是红线层实际不存在、对外却报「已检查」，
    而红线正是「红色 100% 召回」那条验收标准的机械保障。
    """
    monkeypatch.setattr(red_flags, "_query", list)

    result = red_flags.load_rules(force=True)

    assert result.rules == ()
    assert result.available is False
    assert "空" in result.detail


def test_non_empty_rule_table_reports_available(monkeypatch):
    """对照：有规则时才是 available。"""
    monkeypatch.setattr(
        red_flags,
        "_query",
        lambda: [RedFlag("RF-001", "中毒", (), "all", "all", 3, "立即送医")],
    )

    result = red_flags.load_rules(force=True)

    assert len(result.rules) == 1
    assert result.available is True
