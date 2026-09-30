"""评测集的读取（`tests/eval_set/*.yaml`）。

**为什么单独一个模块**：评测集是「分级准不准」这件事的唯一量尺，而读它的代码原先在
`test_eval_set.py`（结构校验）与 `test_eval.py`（实弹门槛）里**各写了一份**。
两份一旦有一份改了过滤条件，结构校验守的就不是门槛跑的那批样本——
量尺与校验量尺的代码必须是同一份。
"""

from __future__ import annotations

from pathlib import Path

import yaml

EVAL_DIR = Path(__file__).parent / "eval_set"


def load_cases() -> list[dict]:
    """按文件名顺序读全部样本。多个 yaml 时合并成一份列表。"""
    cases: list[dict] = []
    for path in sorted(EVAL_DIR.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        cases.extend(data.get("cases", []))
    return cases
