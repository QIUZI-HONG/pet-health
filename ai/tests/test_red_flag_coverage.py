"""「不许漏」的样本必须有规则覆盖（ADR-0021 的核心保障）。

ADR-0021 的原话是：把「红色 100% 召回」押在模型偶发漏判上等于没有保障——所以高危症状要靠
词表**机械命中**。可这条保障一直没人验：跑起发布门槛才发现 9 条 `must_not_miss` 里有 4 条
根本没有规则覆盖（词表写书面短词、用户说带插入语的口语），落到模型手上就看它这次的心情
（第二次跑门槛时 EV-006「便里带血」真的漏了，召回率 89%）。

这个测试把那条保障钉住：评测集里每一条 `must_not_miss` 都要能被某条规则命中。
**给评测集加红线样本时，必须同时补词表**——忘了就会红在这里，而不是在发布之后。

需要能连上 MySQL（词表在库里）。连不上就跳过：这条是本地/带库 CI 的检查，
不是「没有库就不许跑测试」的闸门。
"""

from pathlib import Path

import pytest
import yaml

from app import red_flags

EVAL_DIR = Path(__file__).parent / "eval_set"


def must_not_miss_cases() -> list[dict]:
    cases: list[dict] = []
    for path in sorted(EVAL_DIR.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        cases.extend(c for c in data.get("cases", []) if c.get("must_not_miss"))
    return cases


def test_every_must_not_miss_case_is_rule_covered():
    rule_set = red_flags.load_rules(force=True)
    if not rule_set.available:
        pytest.skip("红线词表读不到（没连库或词表为空）：这条检查需要真实词表")

    uncovered: list[str] = []
    for case in must_not_miss_cases():
        hits = red_flags.match(
            case["text"],
            rule_set.rules,
            species=case["species"],
            age_stage=case["age_stage"],
        )
        if not hits:
            uncovered.append(f"{case['id']}（{case['text']}）")

    assert not uncovered, (
        "这些「不许漏」的样本没有规则覆盖，红色召回会退化成靠模型：\n  "
        + "\n  ".join(uncovered)
        + "\n补词表（variants 里加用户真实会说的整块说法），或在评测集里说明为什么它只能靠模型"
    )
