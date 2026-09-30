"""F011 规则版的**症状识别**（`POST /internal/symptom-match`，ADR-0050 第二节）。

这一层只做一件事：把一句话里的**受控症状词**认出来（含口语别名），并带上该症状的分诊条目摘要。
症状到目录项的映射在 Java 侧（`catalog_symptom_rule`），所以这里不测推荐、只测识别与降级。

四条用例对应四件必须成立的事：

1. **口语别名要认**（「拉稀」→ 腹泻）：知识侧已经有一份受控词典，口语词写在 alias 里，
   这一层复用它——再维护第二份口语表是重复；
2. **多症状都报**（「吐了还拉稀」→ 呕吐 + 腹泻）；
3. **认不出不是错**：空列表 + `knowledge_check=ok`（用户说了句无关的话）；
4. **读不到词典也不报错**：空列表 + `unavailable`——与检索同一条降级口径（ADR-0026）。
   另外两条边界：词表命中但条目读不到时**症状照样报出来**；鉴权与文本长度按内部契约拦截。
"""

import pytest
from fastapi.testclient import TestClient

from app import knowledge, main
from app.db import DbUnavailable

TOKEN = {"X-Internal-Token": "test-internal-token"}


def symptom_node(name: str, entry_code: str, *aliases: str) -> dict:
    """一个症状节点（与 `_dictionary()["nodes"]` 的形状一致）。"""
    return {
        "node_type": "symptom",
        "name": name,
        "alias": list(aliases),
        "code": f"SYM-{name}",
        "entry_code": entry_code,
    }


def entry_row(code: str, title: str, risk_hint: str | None) -> dict:
    """一条 `_query_entries_by_codes` 形状的行。"""
    return {
        "code": code,
        "title": title,
        "summary": "家庭观察要点",
        "body": "正文",
        "category_code": "triage",
        "species_scope": "all",
        "age_stage_scope": "all",
        "source_title": "合作兽医审核稿（工程整理，待复核）",
        "source_version": "待复核稿 v1",
        "source_url": None,
        "review_status": knowledge.STATUS_PENDING,
        "confidence": "medium",
        "risk_hint": risk_hint,
        "structured_payload": None,
    }


@pytest.fixture
def dictionary(monkeypatch):
    """把词典与条目接缝换成可控的假数据（本层不连库，与 test_knowledge.py 同一手法）。"""
    state = {
        "nodes": [
            symptom_node("呕吐", "K-0010", "吐", "吐了", "干呕"),
            symptom_node("腹泻", "K-0011", "拉稀", "拉肚子"),
            symptom_node("跛行", "K-0015", "瘸"),
        ],
        "entries": [
            entry_row("K-0010", "犬猫呕吐的家庭观察要点", "yellow"),
            entry_row("K-0011", "犬猫腹泻的家庭观察要点", "yellow"),
            entry_row("K-0015", "跛行与不愿走动的观察", "yellow"),
        ],
    }
    monkeypatch.setattr(knowledge, "_static", lambda: (state["nodes"], ()))
    monkeypatch.setattr(knowledge, "_query_entries_by_codes", lambda codes, *a, **k: [
        row for row in state["entries"] if row["code"] in codes
    ])
    return state


def match(text: str, **payload):
    return TestClient(main.app).post("/internal/symptom-match", json={"text": text, **payload}, headers=TOKEN)


# ---------------------------------------------------------------- 识别


def test_recognizes_colloquial_alias(dictionary):
    body = match("我家猫今天拉稀两次，精神还行").json()

    assert [item["symptom"] for item in body["matches"]] == ["腹泻"]
    assert body["matches"][0]["entry_code"] == "K-0011"
    assert body["matches"][0]["title"] == "犬猫腹泻的家庭观察要点"
    assert body["matches"][0]["risk_hint"] == "yellow"
    assert body["knowledge_check"] == "ok"


def test_reports_every_symptom_it_finds(dictionary):
    body = match("吐了还拉稀，一直趴着不动").json()

    assert [item["symptom"] for item in body["matches"]] == ["呕吐", "腹泻"]


def test_plain_sentence_matches_nothing(dictionary):
    body = match("今天天气不错，带它出去玩了").json()

    assert body["matches"] == []
    # 认不出不是故障：知识层是好的，只是这句话里没有受控症状词
    assert body["knowledge_check"] == "ok"


def test_symptom_still_reported_when_its_entry_is_unreadable(dictionary, monkeypatch):
    # 词表读得到、条目读不到：症状照报，只是没有分诊摘要（摘要是增强，不是前提）
    monkeypatch.setattr(knowledge, "_query_entries_by_codes", lambda *a, **k: [])

    body = match("一瘸一拐的").json()

    assert [item["symptom"] for item in body["matches"]] == ["跛行"]
    assert body["matches"][0]["title"] is None
    assert body["matches"][0]["risk_hint"] is None
    assert body["knowledge_check"] == "ok"


# ---------------------------------------------------------------- 降级与边界


def test_dictionary_unavailable_degrades_without_error(monkeypatch):
    def unavailable(*_args, **_kwargs):
        raise DbUnavailable("测试环境没有知识库")

    monkeypatch.setattr(knowledge, "_static", unavailable)

    response = match("拉稀两天了")

    # 与检索同一条口径：读不到不报错，只把「这次认不出」如实说出来
    assert response.status_code == 200
    assert response.json() == {"matches": [], "knowledge_check": "unavailable"}


def test_requires_internal_token():
    response = TestClient(main.app).post("/internal/symptom-match", json={"text": "拉稀两天了"})

    assert response.status_code == 401


@pytest.mark.parametrize("text", ["", "吐", "长" * 501])
def test_text_length_is_enforced(text):
    response = match(text)

    assert response.status_code == 422
