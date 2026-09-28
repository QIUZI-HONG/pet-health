"""评测集的结构校验（切片 #98，ADR-0021）。

**默认跑**：只校验文件本身合不合规——不需要网络、不需要 key。
实弹评测（真打模型、跑准确率与召回率）标了 `eval`，默认跳过，见 `-m eval` 的说明。

为什么值得单独有一个结构测试：评测集是「分级准不准」这件事的**唯一量尺**。量尺本身写错了
（比如红色样本漏了 `must_not_miss`），跑出来的召回率就是假的，而且没人会发现。
"""

from pathlib import Path

import pytest
import yaml

EVAL_DIR = Path(__file__).parent / "eval_set"

REQUIRED_FIELDS = {"id", "text", "species", "age_stage", "expected_level", "must_not_miss", "provisional"}
LEVELS = {1, 2, 3}
AGE_STAGES = {"puppy_kitten", "adult", "senior", "all"}


def load_cases() -> list[dict]:
    cases: list[dict] = []
    for path in sorted(EVAL_DIR.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        cases.extend(data.get("cases", []))
    return cases


def test_eval_set_is_not_empty():
    assert len(load_cases()) >= 10, "首版至少十条，否则门槛（召回率 100%）没有意义"


@pytest.mark.parametrize("case", load_cases(), ids=lambda c: c.get("id", "?"))
def test_case_shape(case: dict):
    missing = REQUIRED_FIELDS - case.keys()
    assert not missing, f"{case.get('id')} 缺字段：{missing}"
    assert case["expected_level"] in LEVELS
    assert case["age_stage"] in AGE_STAGES
    assert case["species"] in (1, 2)
    assert len(case["text"]) >= 2, "文本太短，模型没法判"
    assert isinstance(case["must_not_miss"], bool)


def test_ids_are_unique():
    ids = [case["id"] for case in load_cases()]
    assert len(ids) == len(set(ids)), "评测样本的 id 必须唯一，否则报告对不上号"


def test_must_not_miss_only_on_red_cases():
    """「不许漏」只对红色有定义——把绿级标成 must_not_miss 会让门槛失去意义。"""
    for case in load_cases():
        if case["must_not_miss"]:
            assert case["expected_level"] == 3, f"{case['id']} 不是红级却标了 must_not_miss"


def test_red_coverage_is_meaningful():
    """红色样本要覆盖交付文档 9.5 点名的四类高危症状，否则「100% 召回」只测了个别字。

    判据用「文本 + 注释」：样本里有些是**用户报症状**（「舌头都发紫了」），
    有些是**用户给结论**（「感觉它中暑了」），两种说法在真实对话里各占一半——
    注释里写的是这条样本对应哪一类，所以覆盖检查看两者。
    """
    red_text = " ".join(
        case["text"] + " " + case.get("note", "")
        for case in load_cases() if case["expected_level"] == 3
    )
    for keyword, label in [
        ("中暑", "中暑"),
        ("老鼠药", "中毒"),
        ("呕吐不止", "持续呕吐"),
        ("尿不出", "猫尿闭"),
    ]:
        assert keyword in red_text, f"红色样本缺少「{label}」（交付文档 9.5 点名的高危症状）"


def test_标注状态被记录():
    """首版标注是工程按公开共识起草的（ADR-0021）——**未经兽医复核这件事必须留在数据里**。"""
    for case in load_cases():
        assert "provisional" in case
        if case["provisional"]:
            assert case.get("note"), f"{case['id']} 标了 provisional 就要写一句出处或理由"
