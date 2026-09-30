"""三层检索在**真 MySQL** 上的行为（切片 #100 的验收标准里「中文子串检索走 MySQL ngram」）。

`test_knowledge.py` 用假行测算法，这一份测的是环境事实与种子数据：ngram 解析器认不认中文子串、
L1 的结构化载荷取不取得到、L2 的边能不能把「不吃东西」这类口语说法引到条目上、
以及**种子（全部 pending_review）与引用口径的相互关系**。

需要能连上 MySQL 且 V18/V19 已迁移。连不上就跳过——它是「本地/带库 CI 的检查」，
不是「没有库就不许跑测试」的闸门（与 test_red_flag_coverage.py 同一口径）。
连上但一条都没召回时会失败而不是跳过：那说明迁移没跑或种子丢了，是**真的坏了**。
"""

import pytest

from app import knowledge

from .conftest import restore_real_seams


@pytest.fixture(autouse=True)
def real_database(monkeypatch):
    """把查库接缝换回真实现（conftest 的 autouse 夹具默认让所有用例都不连库）。

    必须是**函数级**夹具：模块级的会在 conftest 的函数级 autouse 夹具**之前**建立，
    接着就被它换回假实现——那样这一整个文件会静默地全部跳过（实测踩到）。
    函数级的顺序正好相反：本夹具后跑，换回真实现。
    """
    restore_real_seams(monkeypatch, "knowledge")
    knowledge._static_cache.clear()
    assert knowledge._static.__module__ == "app.knowledge", "接缝没被换回真实现"


@pytest.fixture(scope="module")
def seeded():
    """确认知识库可用（表在、种子在），否则跳过。"""
    knowledge._static_cache.clear()
    result = knowledge.search("呕吐", species=1, age_stage="adult")
    if result.status in ("unavailable", "disabled"):
        pytest.skip(f"知识库读不到（没连库或 V18/V19 没跑）：{result.detail}")
    assert result.items, "库里一条都没召回到：迁移没跑或种子丢了"
    return True


def test_l3_chinese_substring_search_finds_the_seeded_entry(seeded):
    """中文子串检索：问「呕吐」，要能命中条目（ngram 解析器 + 布尔模式短语）。"""
    result = knowledge.search("今天呕吐了两次，精神还行", species=1, age_stage="adult")

    assert result.status == "ok"
    codes = [item.code for item in result.items]
    assert "K-0010" in codes, f"「呕吐」应当召回症状分诊条目，实际 {codes}"
    assert any(knowledge.LAYER_L3 in item.layers for item in result.items)


def test_l1_structured_facts_come_from_a_query_not_from_search(seeded):
    """疫苗/驱虫这类问题走结构化查询：拿到的条目要带 `structured_payload`（可判定的答案）。"""
    result = knowledge.search("幼犬疫苗要打几针，间隔多久", species=1, age_stage="puppy_kitten")

    l1 = [item for item in result.items if knowledge.LAYER_L1 in item.layers]
    assert l1, "疫苗问题应当命中 L1 的结构化事实"
    assert l1[0].payload, "L1 条目必须带结构化载荷（否则「几针、间隔多久」只能靠模型猜）"
    assert l1[0].category_code in ("vaccine", "antiparasitic", "nutrition")


def test_l2_relation_layer_expands_spoken_variants(seeded):
    """关系层补齐口语说法：用户说「不吃东西」，条目写「食欲下降」，关键词召不回，边能。

    这正是 ADR-0022 说的「关键词检索覆盖不了换一种说法」的缓解手段——它必须真的生效，
    否则那句缓解就只是纸面上的。
    """
    result = knowledge.search("我家猫这两天不吃东西", species=2, age_stage="adult")

    hit = [item for item in result.items if item.code == "K-0012"]
    assert hit, f"「不吃东西」应当经关系层召回到「食欲下降」那条，实际 {[i.code for i in result.items]}"


def test_seed_entries_are_pending_review_and_therefore_not_citable(seeded):
    """种子全是 pending_review：**能进上下文，但不能进 citations**（ADR-0033 的引用口径）。

    这条同时是「工程不假造复核状态」的守卫：哪天有人为了让 citations 有内容给种子盖上
    vetted，这里会红——盖章只能由兽医复核产出。
    """
    result = knowledge.search("今天呕吐了两次", species=1, age_stage="adult")

    assert result.items, "先要有召回才谈得上引用口径"
    assert result.citations() == [], "未复核条目不许出现在 citations 里"
    assert result.unvetted_codes(), "种子应当被记为「未复核」而不是被丢掉"


def test_contraindication_gate_keeps_the_warning_entry_on_real_data(seeded):
    """真种子上的安全门：K-0070（讲人用药为什么不能给猫吃）不能被自己的药名拦掉。

    它会被拦掉的两种写法都验到了：`对乙酰氨基酚对猫剧毒` 是**陈述句**（没有否定词在附近），
    而 `布洛芬等非甾体抗炎药对犬猫都可能造成损伤` 连推荐线索都没有。安全门要的是
    「推荐」，不是「提到」——否则最有价值的那条安全提醒会被自己删除。
    """
    result = knowledge.search("猫发烧能不能吃人用药", species=2, age_stage="adult")

    hit = [item for item in result.items if item.code == "K-0070"]
    assert hit, f"K-0070 被误剔了：{[i.code for i in result.items]} / flags={result.flags}"
    assert "剂量" not in hit[0].body, "种子正文不该带护栏禁用词（带了会被整段替换）"


def test_seed_bodies_survive_the_guardrail(seeded):
    """种子的标题与摘要不该在护栏里被整段改写——那样条目会被剔除，检索白做。

    这条是内容纪律的守卫：写种子时用「要确认需要检查」「用多少由兽医决定」这类说法，
    不要出现「确诊」「处方」「剂量」这些护栏禁用词。
    """
    result = knowledge.search("幼犬疫苗要打几针", species=1, age_stage="puppy_kitten")

    dropped = [flag for flag in result.flags if "guard_dropped" in flag]
    assert not dropped, f"种子内容触发了护栏整段改写：{dropped}"
