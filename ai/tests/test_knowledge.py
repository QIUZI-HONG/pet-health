"""三层知识检索的测试（切片 #100 的验收标准：三层各自的检索各有一条测试，含中文子串用例）。

这一份测的是**算法与过滤**，用假行喂给查库接缝（`_query_entries` / `_query_facts` /
`_query_relations` / `_static`），所以不需要数据库；真库上的行为（ngram 能不能命中中文子串、
L1/L2 的 SQL 跑不跑得通）在 `test_knowledge_db.py` 里，那一份连不上库就跳过。

为什么分两层：算法（排序、安全门、引用校验）是本切片真正的逻辑，它必须**永远**能测；
而「MySQL 的 ngram 解析器认不认中文」是环境事实，它在 CI 上有库时才算数（#63 已实测可用）。
"""

import pytest

from app import knowledge, ops
from app.db import DbUnavailable

VETTED = knowledge.STATUS_VETTED
PENDING = knowledge.STATUS_PENDING


def entry_row(code: str = "K-0010", **overrides) -> dict:
    """一条 `_query_entries` 形状的行（字段与 SQL 的 SELECT 对齐）。"""
    row = {
        "code": code,
        "title": "犬猫呕吐的家庭观察要点",
        "summary": "记录次数、性状与时间，单次呕吐可先观察",
        "body": "呕吐后先做三件事：记录次数与时间、观察呕吐物性状、观察精神与饮水情况。",
        "category_code": "triage",
        "species_scope": "all",
        "age_stage_scope": "all",
        "source_title": "合作兽医审核稿（工程整理，待复核）",
        "source_version": "待复核稿 v1",
        "source_url": None,
        "review_status": PENDING,
        "confidence": "medium",
        "risk_hint": "yellow",
        "structured_payload": None,
    }
    row.update(overrides)
    return row


def fact_row(code: str = "K-0001", payload: str | None = None) -> dict:
    return entry_row(
        code,
        title="犬核心疫苗的接种时间表",
        summary="幼犬核心疫苗一般从 6–8 周龄开始，每隔 2–4 周接种一次",
        category_code="vaccine",
        structured_payload=payload or '{"kind":"vaccine_schedule","interval_weeks":"2-4"}',
    )


@pytest.fixture
def stub_layers(monkeypatch):
    """按层的可控接缝：默认三层都空，用例只改自己关心的那一层。"""
    state = {"entries": [], "facts": [], "relations": [], "symptom_entries": [], "nodes": [], "edges": ()}

    monkeypatch.setattr(knowledge, "_static", lambda: (state["nodes"], state["edges"]))
    monkeypatch.setattr(knowledge, "_query_entries", lambda *args, **kwargs: state["entries"])
    monkeypatch.setattr(knowledge, "_query_facts", lambda *args, **kwargs: state["facts"])
    monkeypatch.setattr(knowledge, "_query_relations", lambda *args, **kwargs: state["relations"])
    monkeypatch.setattr(knowledge, "_query_entries_by_codes", lambda *args, **kwargs: state["symptom_entries"])
    return state


# ---------------------------------------------------------------- 检索词与布尔查询串


def test_boolean_query_quotes_terms_and_strips_syntax():
    """每个词加引号成短语（ngram 下短语 = 子串匹配），并洗掉布尔模式的语法字符。

    `-呕吐` 洗不干净就会变成「排除呕吐」，与用户的意图正好相反——这类输入不该有语法意义。
    单字直接丢掉：ngram_token_size 默认 2，单字落不进索引。
    """
    assert knowledge.build_boolean_query(["呕吐", "精神沉郁"]) == '"呕吐" "精神沉郁"'
    assert knowledge.build_boolean_query(["-呕吐", "a", "+狗狗"]) == '"呕吐" "狗狗"'
    assert knowledge.build_boolean_query([]) == ""


def test_terms_prefer_dictionary_over_bigram_fallback(stub_layers):
    """词典命中优先，且长词吃掉短词：「持续呕吐」命中时不再单独算「呕吐」。

    两种策略各有用处（词典覆盖同义说法、二字窗口接住词典外的词），但**同时生效**会让
    同一件事被计两次分——排序就失真了。
    """
    stub_layers["nodes"] = [
        {"node_type": "symptom", "name": "持续呕吐", "alias": ("呕吐不止",), "code": "SYM-V", "entry_code": ""},
    ]
    terms = knowledge._terms("我家狗持续呕吐了一天")

    assert "持续呕吐" in terms
    assert "呕吐不止" not in terms  # 没在文本里出现
    # 二字窗口仍然兜住词典外的部分（「我家」「家狗」…），这是召回率的兜底
    assert any(len(term) == 2 for term in terms)


# ---------------------------------------------------------------- L3 关键词检索


def test_l3_hit_ranks_by_term_coverage(stub_layers):
    """两条都命中时按词覆盖度排序：标题/摘要命中的排前面。"""
    stub_layers["entries"] = [
        entry_row("K-0010", title="猫的饮水与湿粮偏好", summary="猫天生饮水少", body="与泌尿系统健康相关。"),
        entry_row("K-0011", title="犬猫呕吐的家庭观察要点", summary="呕吐后先记录次数与性状", body="观察精神与饮水。"),
    ]
    result = knowledge.search("今天呕吐了两次，精神还行", species=1, age_stage="adult")

    assert result.status == "ok"
    assert result.items[0].code == "K-0011", "标题命中「呕吐」的那条应排前面"
    assert result.items[0].layers == (knowledge.LAYER_L3,)


def test_l3_no_hit_is_empty_not_unavailable(stub_layers):
    """召回为空与检索层不可用是两件事：前者是「没有覆盖」，后者是故障（留痕要分得开）。"""
    result = knowledge.search("今天心情不错", species=1, age_stage="adult")

    assert result.status == "empty"
    assert result.items == ()
    assert result.detail


def test_unavailable_when_knowledge_db_is_down(monkeypatch):
    """读不到知识域：状态是 unavailable（不是 empty），**不抛异常**——咨询必须照常回答。"""

    def boom(*_args, **_kwargs):
        raise DbUnavailable("连不上知识库")

    monkeypatch.setattr(knowledge, "_static", boom)

    result = knowledge.search("今天吐了两次", species=1, age_stage="adult")

    assert result.status == "unavailable"
    assert "连不上" in result.detail
    assert result.items == ()


def test_disabled_by_operator_switch(stub_layers):
    """运营把检索开关关掉：状态是 disabled，一次库都不查（与 unavailable 分开记）。"""
    result = knowledge.search("今天吐了两次", species=1, age_stage="adult", enabled=False)

    assert result.status == "disabled"


# ---------------------------------------------------------------- L1 结构化


def test_l1_facts_come_first_and_carry_payload(stub_layers):
    """疫苗这类问题走结构化查询：命中触发词才查，**L1 排在最前**（它是唯一权威）。"""
    stub_layers["facts"] = [fact_row()]
    stub_layers["entries"] = [entry_row("K-0011")]

    result = knowledge.search("幼犬疫苗要打几针，间隔多久", species=1, age_stage="puppy_kitten")

    assert result.items[0].code == "K-0001", "L1 的确定性答案要排在最前（它是唯一权威）"
    assert result.items[0].layers == (knowledge.LAYER_L1,)
    assert result.items[0].payload["kind"] == "vaccine_schedule"


def test_l1_not_queried_without_intent(stub_layers, monkeypatch):
    """没有 L1 触发词就不查结构化载荷——每次咨询都塞周期表等于给上下文灌噪音。"""
    called = []
    monkeypatch.setattr(knowledge, "_query_facts", lambda *a, **k: called.append(1) or [])

    knowledge.search("今天精神不太好", species=1, age_stage="adult")

    assert called == []


# ---------------------------------------------------------------- L2 关系层


def test_l2_adds_entries_the_keyword_layer_missed(stub_layers):
    """关系层的价值就在这里：用户说「不吃东西」，条目写「食欲下降」，关键词召不回。"""
    stub_layers["nodes"] = [
        {"node_type": "symptom", "name": "食欲下降", "alias": ("不吃东西",), "code": "SYM-EAT", "entry_code": ""},
    ]
    stub_layers["relations"] = [
        {**entry_row("K-0012"), "entry_code": "K-0012", "weight": 0.6, "urgency": "yellow",
         "note": "短期食欲下降常见于胃肠道不适"},
    ]

    result = knowledge.search("我家猫不吃东西了", species=2, age_stage="adult")

    assert [item.code for item in result.items] == ["K-0012"]
    assert result.items[0].layers == (knowledge.LAYER_L2,)
    assert "胃肠道" in result.items[0].note, "边上的一句话解释要进上下文：它是审核过的理由"


def test_l2_deduplicates_against_keyword_hits(stub_layers):
    """同一编号被两路召回时只出现一次，层标记合并（出现两次会让模型以为是两条不同知识）。"""
    stub_layers["nodes"] = [
        {"node_type": "symptom", "name": "呕吐", "alias": (), "code": "SYM-V", "entry_code": ""},
    ]
    stub_layers["entries"] = [entry_row("K-0010")]
    stub_layers["relations"] = [{**entry_row("K-0010"), "entry_code": "K-0010", "weight": 0.5, "note": ""}]

    result = knowledge.search("今天呕吐了两次", species=1, age_stage="adult")

    assert [item.code for item in result.items] == ["K-0010"]
    assert set(result.items[0].layers) == {knowledge.LAYER_L2, knowledge.LAYER_L3}


# ---------------------------------------------------------------- 安全门与护栏


def test_safety_gate_drops_entry_that_recommends_a_contraindicated_drug(stub_layers):
    """「推荐了本物种禁忌的药物」的条目整条剔除，并留痕（63 号调研 §5.3 收益 1）。"""
    stub_layers["entries"] = [
        entry_row("K-0099", title="家庭常备药清单", summary="猫发烧可以喂对乙酰氨基酚", body="按人用量减半。"),
    ]
    stub_layers["edges"] = ({"name": "对乙酰氨基酚", "alias": (), "relation": "contraindicated_for",
                             "species_scope": "cat", "entry_code": "K-0070"},)

    result = knowledge.search("猫发烧了怎么办", species=2, age_stage="adult")

    assert result.items == ()
    assert result.status == "empty"
    assert any("contraindicated_for" in flag for flag in result.flags)


def test_safety_gate_keeps_a_warning_about_the_same_drug(stub_layers):
    """同一条药物被**警告**时不能剔除：知识条目里出现药名常常正是在说「别用」。

    丢掉警告等于把最该说的话删了——判据是「推荐 vs 警告」，与输出护栏共用同一套逻辑。
    """
    stub_layers["entries"] = [
        entry_row("K-0070", title="人用药为什么不能给宠物吃",
                  summary="对乙酰氨基酚对猫剧毒", body="千万不要给猫喂对乙酰氨基酚，误食按急症处理。"),
    ]
    stub_layers["edges"] = ({"name": "对乙酰氨基酚", "alias": (), "relation": "contraindicated_for",
                             "species_scope": "cat", "entry_code": "K-0070"},)

    result = knowledge.search("能不能给猫吃退烧药", species=2, age_stage="adult")

    assert [item.code for item in result.items] == ["K-0070"]


def test_safety_gate_exempts_the_drug_own_reference_entry(stub_layers):
    """药物**自己的说明条目**的陈述句不算推荐，条目保留。

    判据是 `knowledge_node.entry_code`：K-0070 讲的就是「人用药为什么不能给宠物吃」，
    按「提到禁忌药就剔除」办，最有价值的安全条目会被自己拦掉。豁免之后仍要求出现正向
    推荐线索，所以「建议给猫用对乙酰氨基酚」这种写法照样会被拦下（见上一条用例）。
    """
    stub_layers["entries"] = [
        entry_row("K-0070", title="人用药为什么不能给宠物吃",
                  summary="对乙酰氨基酚对猫剧毒，布洛芬对犬猫都可能造成损伤",
                  body="任何用药必须由兽医决定。"),
    ]
    stub_layers["edges"] = (
        {"name": "对乙酰氨基酚", "alias": (), "relation": "contraindicated_for",
         "species_scope": "cat", "entry_code": "K-0070"},
        {"name": "布洛芬", "alias": ("芬必得",), "relation": "contraindicated_for",
         "species_scope": "cat", "entry_code": "K-0070"},
    )

    result = knowledge.search("人用药能给猫吃吗", species=2, age_stage="adult")

    assert [item.code for item in result.items] == ["K-0070"]
    assert "contraindicated_for" not in " ".join(result.flags)


def test_retrieved_text_goes_through_the_output_guardrail(stub_layers):
    """检索文本进的是模型上下文，所以它同样要过输出护栏（命中即清洗/剔除 + 留痕）。"""
    stub_layers["entries"] = [
        # 剂量：只替换片段，条目保留
        entry_row("K-0010", body="呕吐后可以按 5ml 少量喂水，其余照常观察。"),
        # 药名推荐：整段改写，条目没有信息量了 → 剔除
        entry_row("K-0011", title="居家照护", summary="建议服用布洛芬止痛", body="一天两次。"),
    ]

    result = knowledge.search("今天吐了两次", species=1, age_stage="adult")

    codes = [item.code for item in result.items]
    assert "K-0010" in codes
    assert "5ml" not in result.items[0].body, "剂量片段要被换掉"
    assert "K-0011" not in codes, "整段被改写的条目不能拿它当上下文"
    assert any("guard" in flag for flag in result.flags)


# ---------------------------------------------------------------- 引用口径（vetted / pending）


def test_only_vetted_entries_can_be_cited():
    """只有 vetted 能进 citations；未复核条目走 unvetted_codes，必须被明说（ADR-0033）。"""
    vetted = knowledge._to_item(entry_row("K-0001", review_status=VETTED), layers=(knowledge.LAYER_L3,))
    pending = knowledge._to_item(entry_row("K-0002"), layers=(knowledge.LAYER_L3,))
    context = knowledge.Context(items=(vetted, pending), status="ok")

    assert [c["entry_id"] for c in context.citations()] == ["K-0001"]
    assert context.vetted_codes() == ("K-0001",)
    assert context.unvetted_codes() == ("K-0002",)


def test_strip_invalid_refs_removes_fabricated_codes():
    """引用校验：剔除不在召回集里的编号，**句子保留**（句子级别的取舍由调用方按业务判断）。"""
    cleaned, removed = knowledge.strip_invalid_refs("可能饮食不当 [K-0010] [K-9999]", {"K-0010"})

    assert cleaned == "可能饮食不当 [K-0010]"
    assert removed == ["K-9999"]


def test_strip_invalid_refs_accepts_full_width_brackets():
    """模型会写全角方括号——只认半角会把有效引用一起洗掉。"""
    cleaned, removed = knowledge.strip_invalid_refs("可能胃肠炎【K-0022】", {"K-0022"})

    assert cleaned == "可能胃肠炎【K-0022】"
    assert removed == []


# ---------------------------------------------------------------- 分级规则（#103）


def test_escalate_filters_by_species_and_age():
    """分级规则与红线同一套过滤：物种与年龄段不匹配就不该命中。"""
    rule = ops.GradingRule(code="GR-003", name="猫排尿异常", terms=("尿不出",), min_level=3,
                           species_scope="cat")
    younger = ops.GradingRule(code="GR-001", name="幼宠呕吐", terms=("呕吐",), min_level=2,
                              age_stage_scope="puppy_kitten")

    assert ops.escalate("猫尿不出来了", (rule,), species=2, age_stage="adult").level == 3
    assert ops.escalate("猫尿不出来了", (rule,), species=1, age_stage="adult").codes == ()
    assert ops.escalate("呕吐了", (younger,), species=1, age_stage="adult").level == 1
    assert ops.escalate("呕吐了", (younger,), species=1, age_stage="puppy_kitten").level == 2


def test_escalate_takes_the_highest_floor_and_keeps_advice():
    """多条命中取最高下限，建议去重拼接（不重复灌同一句话）。"""
    rules = (
        ops.GradingRule(code="GR-1", name="a", terms=("呕吐",), min_level=2, advice="尽快就医。"),
        ops.GradingRule(code="GR-2", name="b", terms=("吐",), min_level=3, advice="立即送医。"),
        ops.GradingRule(code="GR-3", name="c", terms=("吐",), min_level=1, advice="尽快就医。"),
    )

    result = ops.escalate("今天呕吐了", rules, species=1, age_stage="adult")

    assert result.level == 3
    assert result.codes == ("GR-1", "GR-2", "GR-3")
    assert result.advice == "尽快就医。立即送医。"


# ---------------------------------------------------------------- C 端浏览（F024 / F025）


def test_browse_lists_by_category_with_the_name_and_total(monkeypatch):
    """浏览按分类翻：分类名一并带回来（C 端不认编码），总数给分页器用。"""
    row = {**entry_row("K-0010", title="犬猫呕吐的家庭观察要点"), "category_name": "症状分诊"}
    monkeypatch.setattr(knowledge, "query", lambda *a, **k: [row])
    monkeypatch.setattr(knowledge, "query_one", lambda *a, **k: {"total": 1})

    items, total, state = knowledge.browse(category_code="triage", page=1, page_size=20)

    assert state == "ok"
    assert total == 1
    assert items[0].code == "K-0010"
    assert items[0].category_name == "症状分诊"
    # 浏览不做打分与关系层召回：一条就是一条，层标记固定为正文层
    assert items[0].layers == (knowledge.LAYER_L3,)


def test_browse_keyword_uses_the_same_ngram_phrase_as_retrieval(stub_layers, monkeypatch):
    """关键词与检索同一套中文匹配：浏览与检索在同一张表上搜，不该有两种行为。"""
    sent = {}

    def fake_query(sql, params=None):
        sent["sql"] = sql
        sent["params"] = params
        return []

    monkeypatch.setattr(knowledge, "query", fake_query)
    monkeypatch.setattr(knowledge, "query_one", lambda *a, **k: {"total": 0})

    knowledge.browse(keyword="犬瘟")

    assert "MATCH(e.title, e.summary, e.body) AGAINST (%s IN BOOLEAN MODE)" in sent["sql"]
    # 短语加引号：ngram 下短语 = 子串匹配（「犬瘟」能命中「犬瘟热」，见 #63）
    assert '"犬瘟"' in sent["params"]


def test_browse_degrades_when_the_knowledge_base_is_unreadable(monkeypatch):
    """读不到知识库：返回空 + unavailable，不抛（与检索同一条降级口径）。"""

    def unavailable(*_args, **_kwargs):
        raise DbUnavailable("测试环境没有知识库")

    monkeypatch.setattr(knowledge, "query_one", unavailable)

    items, total, state = knowledge.browse(page=1, page_size=20)

    assert items == []
    assert total == 0
    assert state == "unavailable"
