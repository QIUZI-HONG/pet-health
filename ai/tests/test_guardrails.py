"""输出层护栏的独立测试（交付文档 9.5 的三个绝不：不确诊 / 不开处方 / 不给剂量）。

原先护栏只有夹在 `test_consult.py` 与 `test_red_flags.py` 里的两条用例，药名那一档
（「建议喂点阿莫西林」实测 0 命中）就是从缝里漏掉的（测试报告 D18）。
"""

import pytest

from app import guardrails


@pytest.mark.parametrize(
    "text",
    [
        "可以喂点阿莫西林，一天两次",          # 无剂量的药名推荐：**原先漏的就是这一档**
        "建议服用布洛芬止痛",
        "给他吃点蒙脱石散",
        "用对乙酰氨基酚退烧就行",              # 对猫剧毒的药，后果最重
        "买点感康喂它",
    ],
)
def test_drug_recommendation_is_rewritten(text: str):
    out, hits = guardrails.review(text)

    assert out == guardrails.REPLACEMENT, f"「{text}」应被整句改写"
    assert any(hit.startswith("drug:") for hit in hits), f"要留下命中痕迹，实际 {hits}"


@pytest.mark.parametrize(
    "text",
    ["确诊为肠胃炎", "这是处方药", "按这个剂量喂", "建议用药三天"],
)
def test_banned_phrases_are_rewritten(text: str):
    out, hits = guardrails.review(text)

    assert out == guardrails.REPLACEMENT
    assert any(hit.startswith("phrase:") for hit in hits)


def test_dosage_keeps_the_rest_of_the_sentence():
    """只有剂量时**不整句删**：前后那些观察建议对用户是有用的。"""
    out, hits = guardrails.review("禁食 4 小时后，按 5ml 少量多次喂水，观察 12 小时")

    assert "dose" in hits
    assert "（剂量请遵医嘱）" in out
    assert "禁食 4 小时" in out, "句子其余部分要保留"
    assert "5ml" not in out


def test_drug_name_plus_dosage_drops_the_whole_sentence():
    """药名 + 剂量同时出现：整句改写——保留下来的半句仍是「推荐某药」，那本身就是处方。"""
    out, hits = guardrails.review("口服阿莫西林 10mg/kg 每日两次")

    assert out == guardrails.REPLACEMENT
    assert {"dose"} <= set(hits)
    assert any(hit.startswith("drug:") for hit in hits)


def test_clean_text_passes_through():
    """不含任何越界内容时一个字都不改——护栏不该把正常回答也换掉。"""
    text = "建议记录 24 小时的饮水量与排尿情况，如精神变差请尽快就医。"

    out, hits = guardrails.review(text)

    assert out == text
    assert hits == []


def test_review_list_drops_items_replaced_wholesale():
    """整句被换成兜底话术的条目要丢掉，避免回答里出现一串相同的「请由兽医决定」。"""
    out, hits = guardrails.review_list(["建议喂点阿莫西林", "多观察精神与食欲"])

    assert out == ["多观察精神与食欲"]
    assert any(hit.startswith("drug:") for hit in hits)
