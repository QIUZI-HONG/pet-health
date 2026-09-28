"""发布门槛：真打模型跑评测集，算准确率与红色召回率，落一份报告（ADR-0021）。

ADR-0021 写死过门槛——**红色召回率 100%、准确率 ≥70%**，任一不达标即拦发布——
但仓库里一直没有可执行的实现（`pytest -m eval` 收集 0 个用例，测试报告 D8 的一部分）。
这个文件把那个门槛变成能跑的东西。

跑法（需要 key 与网络，默认不跑）：

    AI_LIVE_TEST=1 .venv/bin/python -m pytest -m eval -s

结果同时落到 `ai/reports/eval-<日期>.md`（逐条明细 + 汇总），供发布前人工过目与留档。
门槛可用环境变量放宽/收紧：`EVAL_MIN_ACCURACY`（默认 0.70）。

**为什么红色召回是硬门槛、准确率不是**：红判绿的代价（急症被劝回家）远高于绿判红
（白跑一趟医院）——这条口径来自 ADR-0021，不是这里的发明。
"""

from __future__ import annotations

import asyncio
import os
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
import yaml

from app import guardrails, model_client, prompts, red_flags
from app.config import settings

EVAL_DIR = Path(__file__).parent / "eval_set"
REPORT_DIR = EVAL_DIR
#: 提示词里的「本次附带图片」是按张数写的，评测集只有文本，所以固定 0
IMAGE_COUNT = 0


def load_cases() -> list[dict]:
    cases: list[dict] = []
    for path in sorted(EVAL_DIR.glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        cases.extend(data.get("cases", []))
    return cases


def pet_context_of(case: dict) -> dict:
    """按样本的物种与年龄阶段造一份宠物上下文（评测集就是按这两维构造的）。"""
    return {
        "id": 1,
        "species": case["species"],
        "breed": None,
        "birth_date": None,
        "weight": None,
        "chronic": [],
        "recent_records": [],
    }


@pytest.mark.eval
def test_eval_gate():
    if not settings.ai_api_key:
        # 不 skip：`-m eval` 是**显式点名**要跑发布门槛，此时缺 key 必须红——
        # 跳过会让退出码是 0，CI 上「拦发布」就成了没跑（评审提出）
        pytest.fail("发布门槛要在真模型上跑，但没有配置 AI_API_KEY——缺 key 时它不该静默通过")

    cases = load_cases()
    assert cases, "评测集是空的，门槛没有意义"
    assert red_flags.load_rules(force=True).available, (
        "红线词表这一层不可用（读不到库或词表为空）：红色召回率失去机械保障，"
        "此时跑出来的召回率不能用来判断能不能发布"
    )

    results: list[dict] = []
    for case in cases:
        verdict = evaluate_case(case)
        results.append(verdict)
        mark = "✓" if verdict["hit"] else "✗"
        note = verdict.get("path") if verdict["path"] != "model" else ""
        print(f"  {case['id']} 期望={case['expected_level']} 实际={verdict['actual']} {mark} {note}")

    summary = summarize(results)
    report_path = write_report(results, summary)
    print(f"\n报告：{report_path}")
    print(
        f"准确率 {summary['accuracy']:.0%}（全部 {summary['total']} 条，门槛 {summary['accuracy_threshold']:.0%}）"
        f" · 其中模型路径 {summary['model_accuracy']:.0%}"
        f"（{summary['model_matched']}/{summary['model_total']}，其余走红线短路）"
        f" · 红色召回 {summary['red_recall']:.0%}（门槛 100%）"
        f" · 平均延迟 {summary['avg_latency_ms']}ms"
    )

    # 硬门槛一：红色一条都不许漏（红判绿的代价最高）
    missed = summary["missed_red"]
    assert not missed, f"红色召回不达标，漏判：{missed}"

    # 硬门槛二：准确率 ≥ EVAL_MIN_ACCURACY
    assert summary["accuracy"] >= summary["accuracy_threshold"], (
        f"准确率 {summary['accuracy']:.0%} 低于门槛 {summary['accuracy_threshold']:.0%}；"
        f"逐条明细见 {report_path}"
    )


def evaluate_case(case: dict) -> dict:
    """一条样本走一遍**与线上同一条路**：红线预检（能连库时）→ 模型 → 输出护栏。"""
    rule_set = red_flags.load_rules(force=True)
    hits = red_flags.match(
        case["text"],
        rule_set.rules,
        species=case["species"],
        age_stage=case["age_stage"],
    )
    if hits:
        # 红线短路：判红且不调模型（与线上一致）。这是最便宜也最该命中的一条路径
        return {
            "id": case["id"],
            "text": case["text"],
            "expected": case["expected_level"],
            "actual": 3,
            "hit": case["expected_level"] == 3,
            "path": "red_flag",
            "guard_hits": [f"{hit.code}:{hit.term}" for hit in hits],
            "latency_ms": 0,
            "levels": [hit.rule.level for hit in hits],
        }

    try:
        result = asyncio.run(
            model_client.assess(
                system_prompt=prompts.SYSTEM_PROMPT,
                user_prompt=prompts.build_user_prompt(
                    pet=pet_context_of(case),
                    text=case["text"],
                    history=[],
                    image_count=IMAGE_COUNT,
                ),
            )
        )
        causes, cause_hits = guardrails.review_list(list(result.possible_causes))
        action, action_hits = guardrails.review(result.action_suggestion)
        return {
            "id": case["id"],
            "text": case["text"],
            "expected": case["expected_level"],
            "actual": result.risk_level,
            "hit": result.risk_level == case["expected_level"],
            "path": "model",
            "guard_hits": cause_hits + action_hits,
            "latency_ms": result.latency_ms,
            "answer": action,
            "causes": causes,
            "model": result.model_name,
        }
    except (model_client.ModelUnavailable, model_client.ModelOutputInvalid) as exc:
        # 降级要计入报告：它说明这一次没能拿到分级（门槛不看它，但人要看）
        return {
            "id": case["id"],
            "text": case["text"],
            "expected": case["expected_level"],
            "actual": None,
            "hit": False,
            "path": "degraded",
            "guard_hits": [],
            "detail": f"{type(exc).__name__}: {exc}"[:200],
        }


def summarize(results: list[dict]) -> dict:
    total = len(results)
    matched = sum(1 for r in results if r["hit"])
    # 模型路径单独统计：走红线短路的样本**没经过模型**，把它们混进分母会美化模型的准确率
    model_results = [r for r in results if r["path"] == "model"]
    model_matched = sum(1 for r in model_results if r["hit"])
    reds = [r for r in results if r["expected"] == 3]
    missed_red = [r["id"] for r in reds if r["actual"] != 3]
    latencies = [r["latency_ms"] for r in results if r.get("latency_ms")]
    return {
        "total": total,
        "matched": matched,
        "accuracy": matched / total if total else 0.0,
        "accuracy_threshold": float(os.getenv("EVAL_MIN_ACCURACY", "0.70")),
        "model_total": len(model_results),
        "model_matched": model_matched,
        "model_accuracy": model_matched / len(model_results) if model_results else 0.0,
        "red_total": len(reds),
        "red_recall": (len(reds) - len(missed_red)) / len(reds) if reds else 1.0,
        "missed_red": missed_red,
        "degraded": [r["id"] for r in results if r["path"] == "degraded"],
        "guard_case_ids": [r["id"] for r in results if r["guard_hits"]],
        "guarded_via_rules": sum(1 for r in results if r["path"] == "red_flag"),
            "avg_latency_ms": int(sum(latencies) / len(latencies)) if latencies else 0,
        # P95 只对**模型路径**有意义：红线短路是 0ms（没调模型），混进来会把 P95 稀释成假的
        "model_latency_p95_ms": sorted(latencies)[min(len(latencies) - 1, int(len(latencies) * 0.95))]
        if latencies else 0,
        "model_calls": len(latencies),
    }


def write_report(results: list[dict], summary: dict) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    # 用应用统一时区（docs/conventions.md）：报告日期与留痕里的业务日期要对得上
    today = datetime.now(ZoneInfo(settings.timezone)).strftime("%Y-%m-%d")
    # 路径与文件名按 ADR-0021 第 5 节：`ai/tests/eval_set/report-<日期>.md`，供人工核对
    path = REPORT_DIR / f"report-{today}.md"

    lines = [
        f"# 分级评测报告（{today}）",
        "",
        # 模型名取第一条**真的调过模型**的样本：首条若走红线短路，它的 model 是空的
        "模型：" + next((r["model"] for r in results if r.get("model")), "—")
        + f" · 提示词：{settings.prompt_version}",
        "",
        "| 指标 | 结果 | 门槛 |",
        "| --- | --- | --- |",
        f"| 准确率（全部样本） | {summary['accuracy']:.0%}（{summary['matched']}/{summary['total']}） | ≥ {summary['accuracy_threshold']:.0%} |",
        f"| 其中模型路径 | {summary['model_accuracy']:.0%}（{summary['model_matched']}/{summary['model_total']}） | — |",
        f"| 红色召回率 | {summary['red_recall']:.0%}（{summary['red_total']} 条红） | 100% |",
        f"| 红线短路 | {summary['guarded_via_rules']} 条（未经模型） | — |",
        f"| 降级 | {len(summary['degraded'])} 条 | — |",
        f"| 护栏命中 | {len(summary['guard_case_ids'])} 条 | — |",
        f"| 模型路径延迟（均值 / P95，{summary['model_calls']} 次真调用） "
        f"| {summary['avg_latency_ms']} ms / {summary['model_latency_p95_ms']} ms | — |",
        "",
        "> 标注为 `provisional` 的样本**未经兽医复核**（评测集首版的口径），"
        + "门槛结论与它们一起看时要留出这个不确定性。",
        "",
        "## 逐条",
        "",
        "| id | 期望 | 实际 | 路径 | 延迟 | 命中 | 护栏 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        lines.append(
            f"| {r['id']} | {r['expected']} | {r['actual'] if r['actual'] is not None else '降级'} "
            f"| {r['path']} | {r.get('latency_ms', '—')} | {'✓' if r['hit'] else '✗'} "
            f"| {', '.join(r['guard_hits']) or '—'} |"
        )
    lines += ["", "## 明细（文本与回答）", ""]
    for r in results:
        lines.append(f"### {r['id']}（期望 {r['expected']} / 实际 {r['actual']}）")
        lines.append("")
        lines.append(f"- 输入：{r['text']}")
        if r.get("answer"):
            lines.append(f"- 建议：{r['answer']}")
        if r.get("causes"):
            lines.append(f"- 可能原因：{'；'.join(r['causes'])}")
        if r.get("detail"):
            lines.append(f"- 降级明细：{r['detail']}")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
    return path
