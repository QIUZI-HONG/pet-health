"""红线预检的精确性成本（2026-09-28 深测轮）。

红线用纯字符串包含判红（ADR-0021 的取舍：宁可多召回，不能漏），所以**否定表达照样判红**：
「精神很好，没有便血」会命中「便血」→ 红色 + 建议立即送医。

这一组用例把这个成本**钉住并量化**，因为有两个后果：

1. 用户体验：一句安抚性的描述换来「立即送医」；
2. **口径一致性**：同一份代码里的输出护栏**做了**否定语境处理（`guardrails._is_warning`），
   红线没做。两处标准不一致，读代码的人会以为红线也做了。

**这里断言的是现状，不是期望。** 要改就得动 ADR-0021 的取舍（用召回换精确），
而且必须用一个「否定词紧邻症状词」的窄窗口，否则会制造更危险的漏判——
`test_emergency_phrasing_with_a_nearby_negation_word_must_still_fire` 就是给
未来的改动划的那条安全线。
"""

import pytest

from app import red_flags


def rule(code: str = "RF-BLOOD", pattern: str = "便血", **kwargs) -> red_flags.RedFlag:
    """手搓一条规则：不依赖数据库词表，用例的结论才不会随词表内容漂移。"""
    params = {
        "variants": (),
        "species_scope": "all",
        "age_stage_scope": "all",
        "level": 3,
        "action_hint": "立即送医",
    }
    params.update(kwargs)
    return red_flags.RedFlag(code=code, pattern=pattern, **params)


# ---------------------------------------------------------------- 现状：否定照样判红


@pytest.mark.parametrize(
    "text",
    [
        "精神很好，没有便血，吃喝都正常",
        "大便正常，无便血",
        "已经不拉血了",
    ],
)
def test_negated_symptom_still_fires_red_flag_documented_cost(text: str):
    """已知成本：用户说「没有 X」也会判红。

    安全的那个方向（误报红 → 用户白跑一趟）比漏判红（急症被放过）代价低，
    所以 ADR-0021 选了这个取舍。这里把行为写下来，是为了让「改不改」成为一个明面上的决定。
    """
    hits = red_flags.match(text, (rule(variants=("拉血",)),))

    assert hits, f"「{text}」按现状会判红——这是要记录的成本，不是通过"
    assert hits[0].rule.code == "RF-BLOOD"


# ---------------------------------------------------------------- 改动时的安全线


@pytest.mark.parametrize(
    "text",
    [
        "没精神，便血",
        "不吃东西还便血",
        "没有食欲而且便血",
        "精神不好，不吃饭，便血两天了",
    ],
)
def test_emergency_phrasing_with_a_nearby_negation_word_must_still_fire(text: str):
    """**给未来的否定语境处理划的安全线**：「没精神」「不吃东西」里的否定管不到后面的症状词。

    这几句都是真实的急症主诉，任何「看到否定词就降级」的实现都会把它们漏掉——
    这正是 ADR-0021 不肯为精确率动红线的原因。谁要加否定处理，先让这几条继续绿。
    """
    assert red_flags.match(text, (rule(),)), f"「{text}」是急症主诉，必须判红"


# ---------------------------------------------------------------- 物种与年齡的过滤方向


def test_unknown_species_applies_every_rule_fail_open():
    """物种**未知或值越界**时不过滤（宁可多召回）：猫咪专属红线在 species=None/3 时也会命中。

    方向是刻意的——少一层过滤最多让犬多跑一趟医院，反过来则是把猫的急症放过。
    """
    cat_only = rule(code="RF-CAT", pattern="排尿困难", species_scope="cat")

    assert red_flags.match("猫犬都适用：排尿困难", (cat_only,), species=None), "物种没传时不该过滤"
    assert red_flags.match("猫犬都适用：排尿困难", (cat_only,), species=3), "物种值越界同样不过滤"
    assert red_flags.match("猫犬都适用：排尿困难", (cat_only,), species=2), "猫本来就会命中"
    assert not red_flags.match("犬：排尿困难", (cat_only,), species=1), "明确了是犬才过滤"


def test_age_stage_boundaries_are_exact():
    """年龄段分界按 365.25 天/年折算（占位阈值，见 #63）：**整一年落在第 366 天**。

    365 天前算 0.9993 岁 → 幼年，366 天前才算成年。这是换算方式的副作用，方向是安全的
    （多算一天幼年 → 红线更严），这里记录而不修正——阈值本身等兽医定稿（#63）。
    #102 的档案时间轴会直接读它，边界必须先钉住。
    """
    from datetime import datetime, timedelta
    from zoneinfo import ZoneInfo

    from app.config import settings

    today = datetime.now(ZoneInfo(settings.timezone)).date()

    def days_ago(n: int) -> str:
        return (today - timedelta(days=n)).isoformat()

    assert red_flags.age_stage_of(days_ago(364), 1) == "puppy_kitten"
    assert red_flags.age_stage_of(days_ago(366), 1) == "adult", "满一年（按 365.25 天折算）即成年"
    assert red_flags.age_stage_of(days_ago(2556), 1) == "adult"
    assert red_flags.age_stage_of(days_ago(2557), 1) == "senior", "犬 > 7 岁"
    assert red_flags.age_stage_of(days_ago(3652), 2) == "adult", "猫的老年线是 10 岁"
    assert red_flags.age_stage_of(days_ago(3653), 2) == "senior"
    assert red_flags.age_stage_of((today + timedelta(days=1)).isoformat(), 1) == "puppy_kitten", (
        "未来生日在红线过滤里按幼年处理（Java 侧建档会拒绝未来生日，这里是防御性记录）"
    )
    assert red_flags.age_stage_of("2026-02-31", 1) == "all", "不存在的日期不该让整次咨询炸掉"
