"""三层知识检索（切片 #100；架构见 63 号调研，决策见 ADR-0022）。

三层各司其职，检索时按这个顺序用：

- **L1 结构化事实**（`knowledge_entry.structured_payload`）：疫苗/驱虫周期、毒物清单、急救步骤。
  **走结构化查询，不走检索**（交付文档的明确分工）。它给的是可判定的答案，也是「能不能/多久」
  这类问题的唯一权威——63 号调研 §1.2 的反向约束：L3 的文本只能解释，不能当判定依据。
- **L2 关系层**（`knowledge_node` / `knowledge_edge`）：两件事。一是**召回补齐**——用户说
  「不吃东西」而条目写「食欲下降」，靠 `may_indicate` 边把症状节点引到疾病条目上；二是
  **安全门**——`contraindicated_for` / `toxic_to` 命中的条目**整条剔除**，不给模型看、也不引用。
- **L3 文本检索**（`MATCH(...) AGAINST (? IN BOOLEAN MODE)` + ngram 解析器）：中文子串检索，
  实现见 `search` 与 `_query_entries`。

**为什么不做向量**（ADR-0022 已定，这里只复述要点）：当前供应商没有 `/embeddings` 端点
（ADR-0017 实测 404），#63 的调研也把「语料不到 5000 条时向量增益无法证明」写清楚了。
`knowledge_chunk` 表建了但检索侧**不回落到向量**——留接口不实现，重开条件写在 ADR-0022 里。

**排序不依赖 MySQL 的 relevance**：布尔模式下它不构成可比的排序依据。这里按**可解释的词覆盖度**
（词典命中 + 命中词占比）与 L2 边的 `weight` 打分，出问题时能一眼看出「为什么这条排前面」。

失败一律降级：`Context.status` 的取值就是留痕字段 `retrieval_check` 的取值
（ok / empty / unavailable / disabled），**检索失败绝不让咨询失败**（ADR-0033）。
`unavailable` 有两种来源（知识域读不到、检索开关的配置读不到），`detail` 里写明是哪一种；
`disabled` **只表示运营确实把开关关了**——读不到不算（ADR-0050 §三）。
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, replace

from . import guardrails
from .config import settings
from .db import DbUnavailable, query, query_one
from .ttl_cache import TtlCache

logger = logging.getLogger("pet_health_ai")

LAYER_L1 = "l1"
LAYER_L2 = "l2"
LAYER_L3 = "l3"

#: 复核状态（两级，项目所有者 2026-09-29 定的口径，见 ADR-0033）：
#: `vetted` = 兽医复核过，`pending_review` = 没复核过。
STATUS_VETTED = "vetted"
STATUS_PENDING = "pending_review"

#: 检索侧两种状态都取。**只取 vetted 会让检索层在种子阶段空转**（种子里一条 vetted 都没有），
#: 而口径允许未复核内容进上下文——代价是它有两条硬约束：不能进 `citations`，
#: 且用到了就要明说「尚未经兽医复核」（见 `Context.unvetted_codes` 与 `main` 的组装）。
RETRIEVABLE_STATUSES = (STATUS_VETTED, STATUS_PENDING)

#: L1 的结构化类别 + 触发词。命中触发词才去查结构化载荷，避免每次咨询都把周期表塞进上下文。
_L1_INTENTS: dict[str, tuple[str, ...]] = {
    "vaccine": ("疫苗", "接种", "打针", "免疫", "几针", "加强针"),
    "antiparasitic": ("驱虫", "体内虫", "体外虫", "跳蚤", "蜱虫", "蛔虫", "绦虫", "滴虫"),
    "nutrition": ("误食", "吃了", "中毒", "能不能吃", "可以吃", "葡萄", "巧克力", "百合", "木糖醇", "洋葱"),
}

#: 布尔模式里这些字符有语法含义（`+ - > < ( ) ~ * " @`）。术语在拼 SQL 前先洗一遍，
#: 洗不干净就会变成语法错误或语义漂移（例如用户输入 `-呕吐` 会变成「排除呕吐」）。
_BOOLEAN_UNSAFE = re.compile(r'[+\-><()~*"@\\]')

#: 兜底切词用：按标点与空白把问题切开（中文没有词边界，靠标点与词典两级）
_SPLIT = re.compile(r"[\s,，。；;！!？?、：:（）()\[\]【】]+")

#: 引用编号的写法：`[K-0010]`。模型允许写成全角方括号，这里一起认。
REF_PATTERN = re.compile(r"[\[【]\s*(K-\d{3,6})\s*[\]】]")

#: L3 排序的两个权重（代码常量，ADR-0010 的第三层）：引擎 relevance 与词覆盖度各占一半。
#: 只用覆盖度会栽在「词出现在正文而不是标题」上——实测「今天呕吐了两次」把最贴题的
#: 《犬猫呕吐的家庭观察要点》排到了第 6（它的「精神」在正文里，只算半分），
#: 而它正是该排第一的那条；只用 relevance 则无法解释、也无法在测试里复现。
COVERAGE_WEIGHT = 0.5
RELEVANCE_WEIGHT = 0.5

#: 症状节点自己的说明条目的基础分（介于「命中一条疾病边」与「完全没命中边」之间）。
#: 它是**直接回答用户这句话**的那条（「不吃东西怎么办」），所以不该被压到候选末尾。
SYMPTOM_ENTRY_SCORE = 0.65


@dataclass(frozen=True)
class KnowledgeItem:
    """一条可以进模型上下文、也可以被引用的条目。"""

    code: str
    title: str
    summary: str
    body: str
    category_code: str
    species_scope: str
    age_stage_scope: str
    source_title: str
    source_version: str | None
    source_url: str | None
    review_status: str
    confidence: str
    risk_hint: str | None = None
    payload: dict | None = None
    layers: tuple[str, ...] = ()
    score: float = 0.0
    #: 从关系层带过来的一句话解释（如「大型犬腹胀伴干呕需警惕胃扭转」），进上下文能提高原因排序质量
    note: str = ""
    #: 分类中文名：**只有 C 端浏览（`browse`）会填**——检索链路不需要它（那要多一次 join 词典表），
    #: 而 C 端不认分类编码。放在末尾是因为带默认值的字段不能排在无默认值的字段之前。
    category_name: str | None = None

    def citation(self) -> dict:
        """给 C 端的引用结构。

        **只给 vetted 条目用**（`Context.citations` 就只挑 vetted）：`review_status` 带出去是
        让前端能标「已复核」，而没复核的条目压根不该走到这里——那是「没经兽医复核的内容不许
        被当作依据」这条口径的落点（ADR-0033）。
        """
        return {
            "entry_id": self.code,
            "title": self.title,
            "category": self.category_code,
            "source_title": self.source_title,
            "source_version": self.source_version,
            "source_url": self.source_url,
            "review_status": self.review_status,
        }

    @property
    def vetted(self) -> bool:
        """兽医复核过吗。**只有它为真的条目能进 `citations`**（ADR-0033 的口径）。

        判定只认 `STATUS_VETTED`：把 `pending_review` 也算进来，等于让未复核内容冒充依据
        ——「用未复核内容生成回答可以，假装它复核过不行」正是这条口径要钉住的那条线。
        """
        return self.review_status == STATUS_VETTED

    def text_for_guardrail(self) -> str:
        """进模型上下文的那部分文本（护栏要过它：检索文本进的是模型输入）。"""
        parts = [self.title, self.summary, self.body]
        if self.payload:
            parts.append(json.dumps(self.payload, ensure_ascii=False))
        if self.note:
            parts.append(self.note)
        return "\n".join(part for part in parts if part)


@dataclass(frozen=True)
class Context:
    """一次检索的结果。`status` 直接就是留痕字段 `retrieval_check` 的取值。"""

    items: tuple[KnowledgeItem, ...] = ()
    #: 被安全门剔除 / 被护栏清洗的条目（如 `K-0070:contraindicated_for:对乙酰氨基酚`），进留痕
    flags: tuple[str, ...] = ()
    status: str = "empty"
    detail: str = ""

    def codes(self) -> tuple[str, ...]:
        """本轮**全部**条目编号（含未复核）。它就是引用校验的**允许集**。

        `main.consult` 拿它当 `allowed` 喂给 `strip_invalid_refs`，模型引了集合之外的编号
        一律当编造剔除。所以这里**不能只回 vetted**：未复核但确实注入了的条目会被判成编造，
        连带把一条本来正常的结论整条删掉。
        """
        return tuple(item.code for item in self.items)

    def vetted_codes(self) -> tuple[str, ...]:
        """本轮上下文里的**已复核**条目。只有这些能进 citations。"""
        return tuple(item.code for item in self.items if item.vetted)

    def unvetted_codes(self) -> tuple[str, ...]:
        """本轮上下文里**未复核**的条目编号。

        非空就要在回答里明说「该建议尚未经兽医复核」（项目所有者 2026-09-29 的口径）：
        用未复核内容生成回答本身是允许的，**假装它是复核过的**不行。
        """
        return tuple(item.code for item in self.items if not item.vetted)

    def citations(self) -> list[dict]:
        """本轮**可引用**的来源列表：vetted 过滤在这儿，别的过滤不在这儿。

        线上组装 `ConsultResponse.citations` 走的是 `main._cited_citations`——那里还要叠加
        「真被引用」（行内编号或工具参数里出现过，ADR-0028）。这一层只回答「哪些条目有资格
        被当来源」，所以**别在这里加「被引用」的判断**：最终文案在这一层还不存在。
        """
        return [item.citation() for item in self.items if item.vetted]


#: 词典与安全边的缓存（与问题无关，可以跨请求复用）。TTL 用与检索同一条技术参数。
_static_cache: TtlCache[tuple[list[dict], tuple[dict, ...]]] = TtlCache(
    lambda: settings.knowledge_cache_seconds
)


def search(
    text: str,
    *,
    species: int | None,
    age_stage: str,
    enabled: bool = True,
    enabled_known: bool = True,
) -> Context:
    """跑一次三层检索。**任何一层读不到都不抛给调用方**，按降级返回。

    `enabled` 与 `enabled_known` 是两件事，**必须分开传**（调用方从
    `ops.OpsConfig.switch_state()` 拿）：

    - 开关读到了、值是关（`enabled=False, enabled_known=True`）→ `disabled`：
      **运营把闸拉了**，人为动作，不用告警；
    - 开关**读不到**（`enabled_known=False`）→ `unavailable`：配置/库故障，要告警。
      报成 `disabled` 等于「知识库挂了却没人告警」，而这个字段存在的意义就是分清这两件事
      （ADR-0050 §三）。

    两种情况都**不查库、不启用检索**——fail-closed 的行为不变，改的只是上报语义。
    """
    if not enabled_known:
        # 与「知识域读不到」同一个值（都对调用方是故障），detail 里写明是哪一种
        return Context(status="unavailable", detail="检索开关读不到（运营配置不可用）")
    if not enabled:
        return Context(status="disabled", detail="retrieval_enabled 关（运营开关）")
    if not text or not text.strip():
        return Context(status="empty", detail="问题为空")

    species_scope = "dog" if species == 1 else "cat" if species == 2 else None
    try:
        dictionary = _dictionary()
        l1_items = _retrieve_l1(text, species_scope, age_stage)
        l3_items, terms = _retrieve_l3(text, species_scope, age_stage)
        l2_items = _retrieve_l2(text, dictionary, species_scope, age_stage)
    except DbUnavailable as exc:
        # 读不到知识域：**不抛**。上层据此把 retrieval_check 记成 unavailable 并照常回答
        logger.warning("知识检索不可用：%s", exc)
        return Context(status="unavailable", detail=str(exc))

    merged = _merge(l1_items, l3_items, l2_items)
    accepted, flags = _filter(merged, species_scope)
    if not accepted:
        return Context(
            items=(),
            flags=tuple(flags),
            status="empty",
            detail=f"召回为空（候选 {len(merged)} 条，剔除 {len(flags)} 条）" if merged else "召回为空",
        )
    return Context(items=tuple(accepted), flags=tuple(flags), status="ok", detail=f"词={','.join(terms[:6])}")


# ---------------------------------------------------------------- L1 结构化


def _retrieve_l1(text: str, species_scope: str | None, age_stage: str) -> list[KnowledgeItem]:
    """疫苗/驱虫/毒物这类问题走结构化查询：命中触发词才查，返回带 `payload` 的条目。"""
    intents = [name for name, words in _L1_INTENTS.items() if any(word in text for word in words)]
    if not intents:
        return []
    rows = _query_facts(intents, species_scope, age_stage)
    return [_to_item(row, layers=(LAYER_L1,), score=1.0) for row in rows]


# ---------------------------------------------------------------- L3 关键词


def _retrieve_l3(text: str, species_scope: str | None, age_stage: str) -> tuple[list[KnowledgeItem], list[str]]:
    """关键词检索：术语 → 布尔查询 → MySQL ngram 命中 → 按词覆盖度排序。"""
    terms = _terms(text)
    if not terms:
        return [], []
    rows = _query_entries(terms, species_scope, age_stage)
    if not rows:
        return [], terms
    # MySQL 的 relevance 只在**批内**可比（不是绝对分），所以按本批最大值归一后再用。
    # 两个信号都要：引擎的 relevance 反映词频与位置的综合权重（实测能把最贴题的那条顶上来），
    # 我们的覆盖度反映「用户问的词命中了几个」，且是可解释、可测的那一个。
    top_relevance = max((float(row.get("relevance") or 0.0) for row in rows), default=0.0) or 1.0
    items = []
    for row in rows:
        item = _with_score(_to_item(row, layers=(LAYER_L3,)), terms)
        relevance = float(row.get("relevance") or 0.0) / top_relevance
        items.append(_replace(
            item,
            score=round(COVERAGE_WEIGHT * item.score + RELEVANCE_WEIGHT * relevance, 4),
        ))
    items.sort(key=lambda item: (-item.score, item.code))
    return items[: settings.knowledge_max_candidates], terms


def _terms(text: str) -> list[str]:
    """从问题里取检索词：**先走受控词典（节点的 name 与 alias），再用二字窗口兜底**。

    单一策略都不够用：只靠词典，词典外的词一个都召不回；只靠二字窗口，一篇长问题会切出十几个
    词，覆盖度被稀释、排序失去意义（ADR-0022 承认关键词检索的弱点，这里是缓解而不是解决）。
    """
    lowered = text.lower()
    dictionary_terms = [name for name in _dictionary_terms() if name and name.lower() in lowered]
    # 长词优先：命中「持续呕吐」时不要再把「呕吐」也算一遍（那两条边会重复计分）
    dictionary_terms.sort(key=len, reverse=True)
    picked: list[str] = []
    for term in dictionary_terms:
        if any(term in longer for longer in picked):
            continue
        picked.append(term)

    for segment in _SPLIT.split(text):
        segment = segment.strip()
        if len(segment) < 2:
            continue
        if any(segment in term for term in picked):
            continue
        # 二字窗口：ngram_token_size 默认 2，切成二字才能落进索引的 token 粒度
        for index in range(len(segment) - 1):
            bigram = segment[index:index + 2]
            if bigram not in picked:
                picked.append(bigram)
    return picked[: settings.knowledge_max_terms]


def build_boolean_query(terms: list[str]) -> str:
    """检索词 → MySQL 布尔模式查询串。每个词加双引号成**短语**：ngram 下短语=子串匹配。

    不加引号的话，多字词会被 ngram 拆成「与」语义的多个 bigram——那比子串匹配宽，
    会把「呕吐」与「呕…吐」分开的文本也召回（#63 记录的布尔模式转短语行为正是要利用的那条）。
    """
    cleaned = []
    for term in terms:
        safe = _BOOLEAN_UNSAFE.sub("", term).strip()
        if len(safe) >= 2:  # 单字落不进 ngram（token_size=2）
            cleaned.append(f'"{safe}"')
    return " ".join(cleaned)


def _with_score(item: KnowledgeItem, terms: list[str]) -> KnowledgeItem:
    """词覆盖度打分：标题/摘要命中算 1 分，只在正文命中算半分。取值 0–1，可解释。"""
    title_summary = f"{item.title}\n{item.summary}".lower()
    body = item.body.lower()
    hit = 0.0
    for term in terms:
        lowered = term.lower()
        if lowered in title_summary:
            hit += 1.0
        elif lowered in body:
            hit += 0.5
    return _replace(item, score=round(hit / len(terms), 4) if terms else 0.0)


# ---------------------------------------------------------------- L2 关系层


def _retrieve_l2(
    text: str,
    dictionary: dict,
    species_scope: str | None,
    age_stage: str,
) -> list[KnowledgeItem]:
    """关系层召回：命中的症状节点 → 两条路取条目（带 weight 与 note）。

    1. **`may_indicate` 边**：症状 → 疾病条目。它给的是「可能是什么」的**可审核排序**
       （`weight` 人工维护），也是回答里「可能原因」的排序依据（63 号调研 §5.3 收益 2）。
    2. **症状节点自己的说明条目**（`knowledge_node.entry_code`）：用户说「不吃东西」，
       该看的是《精神不振与食欲下降怎么判断》那条——它写的是「食欲下降」，关键词那一层
       永远召不回（ADR-0022 承认的弱点），靠受控词典归一才拿得到。

    不去重关键词那一路已经召回到的编号：去重交给 `_merge`——它顺带把层标记并起来，
    这样「这条既是关键词命中、又是关系层召回」这件事在留痕里看得见。
    """
    hit_nodes = [node for node in dictionary["symptoms"] if _node_hits(node, text)]
    if not hit_nodes:
        return []

    items = [
        _to_item(row, layers=(LAYER_L2,), score=float(row.get("weight") or 0.0),
                 note=str(row.get("note") or ""))
        for row in _query_relations([node["code"] for node in hit_nodes], species_scope, age_stage)
        if row.get("entry_code")
    ]

    # 症状自己的说明条目：按节点上登记的编号取（种子里的症状节点都登记了）
    own_codes = [node["entry_code"] for node in hit_nodes if node.get("entry_code")]
    if own_codes:
        for row in _query_entries_by_codes(own_codes, species_scope, age_stage):
            items.append(_to_item(row, layers=(LAYER_L2,), score=SYMPTOM_ENTRY_SCORE))

    items.sort(key=lambda item: (-item.score, item.code))
    return items


def _node_hits(node: dict, text: str) -> bool:
    """症状节点命中吗：`name` 与 `alias` 里任一词是这句话的**子串**即算命中。

    刻意不做分词：节点词表是运营维护的受控词典，别名怎么写在表里看得见；改成分词反而会
    漏掉整串匹配能命中的写法。词典只有几十条，所以每次重算 `text.lower()` 的代价可忽略。
    """
    lowered = text.lower()
    return any(term and term.lower() in lowered for term in (node["name"], *node["alias"]))


# ---------------------------------------------------------------- 症状识别（F011 规则版）


@dataclass(frozen=True)
class SymptomHit:
    """一次症状识别命中：规范名 + 它的分诊条目摘要。

    `entry_code` / `title` / `risk_hint` 可能为 None——**词表命中但条目读不到**是正常情况
    （条目还没灌、或它的复核状态变了），此时仍然把症状报出去：「你说的是腹泻」这件事成立，
    只是拿不到分诊条目里的就医紧迫程度。
    """

    symptom: str
    entry_code: str | None
    title: str | None
    risk_hint: str | None


def match_symptoms(text: str, *, species: int | None = None, age_stage: str = "all") -> tuple[list[SymptomHit], str]:
    """从一句话里认出**受控词典里的症状**（F011 规则版，ADR-0050 第二节）。

    **不调模型**：用已被检索层复用的症状节点词表（`knowledge_node` 的 name + alias）做子串匹配，
    所以推荐理由说得出来——「因为你说腹泻」里的「腹泻」就是这里给出来的。

    :return: (命中的症状, 知识层状态)；状态取值与检索一致：`ok` / `unavailable`。
             读不到词典时返回空列表与 `unavailable`，**不抛异常**（调用方按降级处理：
             告诉用户「暂时认不出症状，可以直接浏览服务目录」）。

    :param species: 宠物的物种（1 犬 / 2 猫）；None 表示不限——F011 不要求带宠物。
    :param age_stage: 年龄段；F011 没有宠物上下文时用 `all`（与检索的默认口径一致）。
    """
    try:
        dictionary = _dictionary()
    except DbUnavailable:
        return [], "unavailable"
    except Exception as exc:  # noqa: BLE001 —— 与检索同一条纪律：读不到都不该让请求 500
        logger.warning("症状词表读不到，本次不识别症状：%s", exc)
        return [], "unavailable"

    nodes = [node for node in dictionary["symptoms"] if _node_hits(node, text)]
    if not nodes:
        return [], "ok"

    entries: dict[str, KnowledgeItem] = {}
    codes = [node["entry_code"] for node in nodes if node["entry_code"]]
    if codes:
        try:
            entries = {
                item.code: item
                for item in (
                    # 与症状「自己的说明条目」那条路径同一层标记（由词典命中带出，见 _retrieve_l2）
                    _to_item(row, layers=(LAYER_L2,))
                    for row in _query_entries_by_codes(codes, species or "all", age_stage)
                )
            }
        except DbUnavailable:
            # 词表读到了、条目读不到：症状照报，只是没有分诊摘要（见 SymptomHit 的说明）
            entries = {}
        except Exception as exc:  # noqa: BLE001 —— 同上，条目摘要是增强而不是前提
            logger.warning("症状的说明条目读不到，只报症状：%s", exc)
            entries = {}

    hits: list[SymptomHit] = []
    for node in nodes:
        code = node["entry_code"] or None
        entry = entries.get(code) if code else None
        hits.append(
            SymptomHit(
                symptom=node["name"],
                entry_code=code,
                title=entry.title if entry else None,
                risk_hint=entry.risk_hint if entry else None,
            )
        )
    return hits, "ok"


# ---------------------------------------------------------------- 合并与过滤


def _merge(*groups: list[KnowledgeItem]) -> list[KnowledgeItem]:
    """同一编号在多路出现时合并层标记，保留最高分。"""
    best: dict[str, KnowledgeItem] = {}
    layers: dict[str, list[str]] = {}
    for group in groups:
        for item in group:
            layers.setdefault(item.code, [])
            for layer in item.layers:
                if layer not in layers[item.code]:
                    layers[item.code].append(layer)
            current = best.get(item.code)
            if current is None or item.score > current.score:
                best[item.code] = item
    merged = [
        _replace(item, layers=tuple(layers[item.code]))
        for item in best.values()
    ]
    # L1 的确定性答案排前面：它是「唯一权威」，不该被文本条目挤掉（63 号调研 §1.2）
    merged.sort(key=lambda item: (-_layer_priority(item), -item.score, item.code))
    return merged


def _layer_priority(item: KnowledgeItem) -> int:
    """`_merge` 的首个排序键：L1 > L2 > L3（取值 2/1/0）。

    命中了结构化事实的条目**不管分数多少**都排最前：L1 给的是可判定的答案，是「能不能/多久」
    这类问题唯一的权威（63 号调研 §1.2）。三个取值必须互不相同——一旦并列，L1 就会退化成
    和 L3 一起按覆盖度/编号排，这条排序规则也就白写了。
    """
    if LAYER_L1 in item.layers:
        return 2
    if LAYER_L2 in item.layers:
        return 1
    return 0


def _filter(items: list[KnowledgeItem], species_scope: str | None) -> tuple[list[KnowledgeItem], list[str]]:
    """两道关：**安全门**（对本物种禁忌的药物/毒物）→ **输出护栏**（检索文本同样要过护栏）。

    为什么检索文本也要过护栏：它进的是**模型上下文**，一句「可以用布洛芬」放在上下文里，
    模型顺着复述出来的概率很高——护栏只查模型输出等于让这条绕着走。

    命中后的取舍**分两种**，不是一刀切地丢：

    - **安全门命中**（推荐了本物种禁忌的药/毒物）→ **整条剔除**。这类内容一旦进上下文，
      模型把它当事实复述的后果最重（「推荐一次就是事故」，63 号调研 §5.3 收益 1）。
    - **护栏命中**（越界表述 / 剂量 / 非禁忌药名）→ **只把命中的那段换成兜底话术**，
      条目留着继续用。理由：知识条目里的药物说明常常是**警告**语境（「不要给猫用 X」），
      整条丢掉等于把最关键的那条警告也丢了；而清洗过的段落对模型无害。
      清洗得只剩一句兜底话术时，这条也就没有信息量了，按剔除处理。
    """
    accepted: list[KnowledgeItem] = []
    flags: list[str] = []
    for item in items:
        blocked = _contraindication_of(item, species_scope)
        if blocked:
            flags.append(f"{item.code}:contraindicated_for:{blocked}")
            continue
        cleaned, hits, headline_rewritten = _sanitize(item)
        if hits:
            flags.append(f"{item.code}:guard:" + ",".join(hits[:2]))
        if not cleaned or headline_rewritten:
            # 标题/摘要（条目的主张）被整段换掉：这条的知识内容本身就是越界的，不能拿它当上下文
            flags.append(f"{item.code}:guard_dropped")
            continue
        accepted.append(cleaned)
        if len(accepted) >= settings.knowledge_top_k:
            break
    return accepted, flags


def _sanitize(item: KnowledgeItem) -> tuple[KnowledgeItem, list[str], bool]:
    """把条目里越界的段落过一遍护栏。

    返回（清洗后的条目, 命中标记, 标题或摘要是否被整段改写）。第三个返回值是**剔除判据**：
    标题与摘要是条目的「主张」，主张被改写说明整条不能用；只有正文里的一句越界，
    清洗那一段就够了（其余内容仍然有用）。

    `exempt_drugs` 是这条路径特有的豁免：**这条条目就是某个药物的说明条目**时（判据是
    `knowledge_node.entry_code`），不把它对该药的陈述句当推荐处理——那正是待复核种子里
    最该保留的安全提醒（「对乙酰氨基酚对猫剧毒」）。剂量与越界表述照旧一律拦。
    """
    hits: list[str] = []
    fields: dict[str, object] = {}
    exempt = _own_drug_terms(item)
    for name in ("title", "summary", "body"):
        cleaned, field_hits = guardrails.review(getattr(item, name), exempt_drugs=exempt)
        hits.extend(field_hits)
        fields[name] = cleaned
    if item.note:
        note, note_hits = guardrails.review(item.note, exempt_drugs=exempt)
        hits.extend(note_hits)
        fields["note"] = note
    headline = str(fields["title"]).strip() == guardrails.REPLACEMENT or (
        str(fields["summary"]).strip() == guardrails.REPLACEMENT
    )
    return _replace(item, **fields), hits, headline


def _own_drug_terms(item: KnowledgeItem) -> frozenset[str]:
    """这条条目「自己讲的那个药」的词表（`knowledge_node.entry_code` 指过来的）。"""
    terms: set[str] = set()
    for edge in _safety_edges():
        if item.code and item.code == edge["entry_code"]:
            terms.add(edge["name"])
            terms.update(edge["alias"])
    return frozenset(term for term in terms if term)


def _contraindication_of(item: KnowledgeItem, species_scope: str | None) -> str | None:
    """条目文本里有没有**推荐使用**对本物种禁忌的药物/毒物。有则整条剔除（安全门）。

    三个判断层次，都是为了让「宁严勿松」不与「别把警告删掉」打架：

    1. **只认「推荐」语境**：知识条目里出现药名常常正是在警告别用（对乙酰氨基酚对猫剧毒那条
       就是这个写法），把警告一并丢掉等于把最该说的话删了。判据与输出护栏共用
       （`guardrails.is_recommended_mention`），两处不一致会出现查不出原因的行为差。
    2. **药物自己的说明条目用更严的判据**：判据是条目的 code 等于该药物节点在
       `knowledge_node.entry_code` 里登记的说明条目。这类文本里药名大量出现在陈述句里
       （「对乙酰氨基酚对猫剧毒」），要求出现**正向推荐线索**才算推荐，否则整条会被自己拦掉。
    3. 命中即整条剔除：这类内容一旦进上下文，模型把它当事实复述的后果最重
       （「推荐一次就是事故」，63 号调研 §5.3 收益 1）。
    """
    if not species_scope:
        return None
    text = item.text_for_guardrail()
    lowered = text.lower()
    for edge in _safety_edges():
        if species_scope not in (edge["species_scope"], "all"):
            continue
        own_entry = bool(item.code) and item.code == edge["entry_code"]
        for term in (edge["name"], *edge["alias"]):
            if term and guardrails.is_recommended_mention(lowered, term.lower(), require_cue=own_entry):
                return f"{term}:{species_scope}"
    return None


# ---------------------------------------------------------------- 引用校验


def strip_invalid_refs(text: str, allowed: set[str]) -> tuple[str, list[str]]:
    """剔除不在召回集里的引用编号；返回（清理后的文本, 被剔除的编号）。

    只洗编号、不动句子：编号是「来源」，而句子是结论。**句子级别的剔除**由调用方按业务判断
    （见 `main._review_citations` 对可能原因的处理），因为那是产品口径而不是文本处理。
    """
    removed = [code for code in REF_PATTERN.findall(text) if code not in allowed]
    if not removed:
        return text, []
    cleaned = REF_PATTERN.sub(lambda match: match.group(0) if match.group(1) in allowed else "", text)
    return cleaned.strip(), removed


# ---------------------------------------------------------------- 查库（测试的接缝）


def _dictionary() -> dict:
    """受控词典（缓存）：症状/疾病/药物/毒物等节点的名称与别名。与具体问题无关。"""
    nodes, edges = _static()
    return {"nodes": nodes, "symptoms": [node for node in nodes if node["node_type"] == "symptom"], "edges": edges}


def _dictionary_terms() -> list[str]:
    """受控词典里全部节点的 `name` + `alias`，拍平成一份**新列表**给 `_terms` 选词。

    必须每次新建：`_terms` 会在拿到之后原地 `sort`，把缓存里那份交出去等于让第一个调用方
    的排序结果留在缓存里。底层 `_dictionary()` 已经带 TTL 缓存，这里够用就行，不必再缓存一层。
    """
    terms = []
    for node in _dictionary()["nodes"]:
        terms.append(node["name"])
        terms.extend(node["alias"])
    return terms


def _safety_edges() -> tuple[dict, ...]:
    """安全门的边（`contraindicated_for` / `toxic_to`）：对本物种禁忌的药物 / 毒物。

    元素形如 `{name, alias, entry_code, species_scope}`，其中 `entry_code` 是「这个药**自己**
    的说明条目」——豁免判据（`_own_drug_terms` / `_contraindication_of`）全靠它，缺了它
    「对乙酰氨基酚对猫剧毒」这种安全提醒会被自己的安全门拦掉。
    与节点词典同一份快照，所以不会出现「边是新的、节点是旧的」这种组合。
    """
    return _static()[1]


def _static() -> tuple[list[dict], tuple[dict, ...]]:
    """节点词典 + 安全边，带 TTL 缓存。**只读 `knowledge_*`**（ADR-0009）。"""
    return _static_cache.get(_load_static)


def _load_static() -> tuple[list[dict], tuple[dict, ...]]:
    """真正读库那一段（缓存未命中时才走到这里）。

    **两样一起读、一起进缓存**：节点词典与安全边分开缓存会出现「节点是新的、边是旧的」
    这种无法解释的召回结果，而两张表都很小，一次读完不多花什么。

    注意节点的复核状态取值是 `approved` / `pending_review`，与条目的 `vetted` 是**两套词**
    （节点是运营维护的词典，条目才是要引用、要复核的知识）——别把 `RETRIEVABLE_STATUSES`
    照抄到这里。
    """
    node_rows = query(
        "SELECT node_type, name, alias, code, entry_code FROM knowledge_node "
        "WHERE enabled = 1 AND is_deleted = 0 AND review_status IN ('approved', 'pending_review')"
    )
    nodes = [
        {
            "node_type": str(row.get("node_type") or ""),
            "name": str(row.get("name") or ""),
            "alias": _as_list(row.get("alias")),
            "code": str(row.get("code") or ""),
            "entry_code": str(row.get("entry_code") or ""),
        }
        for row in node_rows
    ]
    edge_rows = query(
        "SELECT n.name, n.alias, n.entry_code, e.relation, e.species_scope "
        "FROM knowledge_edge e JOIN knowledge_node n ON n.code = e.src_code "
        "WHERE e.relation IN ('contraindicated_for', 'toxic_to') "
        "AND e.enabled = 1 AND e.is_deleted = 0 AND n.is_deleted = 0"
    )
    edges = tuple(
        {
            "name": str(row.get("name") or ""),
            "alias": _as_list(row.get("alias")),
            "entry_code": str(row.get("entry_code") or ""),
            "relation": str(row.get("relation") or ""),
            "species_scope": str(row.get("species_scope") or "all"),
        }
        for row in edge_rows
    )
    return nodes, edges


def _query_entries(terms: list[str], species_scope: str | None, age_stage: str) -> list[dict]:
    """L3：MySQL 8.4 ngram 全文检索（中文子串已实测可用，#63）。

    过滤条件全在 SQL 里（类别外的条目根本不进候选）：复核状态、物种、年龄段、未删除。
    `LIMIT` 由 `knowledge_max_candidates` 控制——排序在 Python 做，这里只要够多的候选。
    """
    boolean = build_boolean_query(terms)
    if not boolean:
        return []
    sql = (
        "SELECT code, title, summary, body, category_code, species_scope, age_stage_scope, "
        "source_title, source_version, source_url, review_status, confidence, risk_hint, "
        "structured_payload, MATCH(title, summary, body) AGAINST (%s IN BOOLEAN MODE) AS relevance "
        "FROM knowledge_entry "
        "WHERE MATCH(title, summary, body) AGAINST (%s IN BOOLEAN MODE) "
        f"AND review_status IN {RETRIEVABLE_STATUSES} AND is_deleted = 0 "
        "AND species_scope IN ('all', %s) AND age_stage_scope IN ('all', %s) "
        "ORDER BY relevance DESC, code ASC LIMIT %s"
    )
    params = (boolean, boolean, species_scope or "all", age_stage, settings.knowledge_max_candidates)
    return query(sql, params)


def _query_facts(intents: list[str], species_scope: str | None, age_stage: str) -> list[dict]:
    """L1：按类目取带结构化载荷的条目。**不走全文索引**——这是「结构化查询」那一支。"""
    placeholders = ", ".join(["%s"] * len(intents))
    sql = (
        "SELECT code, title, summary, body, category_code, species_scope, age_stage_scope, "
        "source_title, source_version, source_url, review_status, confidence, risk_hint, "
        "structured_payload FROM knowledge_entry "
        "WHERE category_code IN (" + placeholders + ") AND structured_payload IS NOT NULL "
        f"AND review_status IN {RETRIEVABLE_STATUSES} AND is_deleted = 0 "
        "AND species_scope IN ('all', %s) AND age_stage_scope IN ('all', %s) "
        "ORDER BY code ASC LIMIT %s"
    )
    params = (*intents, species_scope or "all", age_stage, settings.knowledge_l1_top_k)
    return query(sql, params)


def _query_relations(node_codes: list[str], species_scope: str | None, age_stage: str) -> list[dict]:
    """L2：症状节点 → `may_indicate` → 疾病节点的条目（带 weight 与 note，按 weight 排序）。"""
    placeholders = ", ".join(["%s"] * len(node_codes))
    sql = (
        "SELECT dst.entry_code AS entry_code, e.weight, e.urgency, e.note, "
        "dst.name AS node_name, e.evidence_entry_code, "
        "kb.title, kb.summary, kb.body, kb.category_code, kb.species_scope, kb.age_stage_scope, "
        "kb.source_title, kb.source_version, kb.source_url, kb.review_status, kb.confidence, "
        "kb.risk_hint, kb.structured_payload "
        "FROM knowledge_edge e "
        "JOIN knowledge_node dst ON dst.code = e.dst_code "
        "JOIN knowledge_entry kb ON kb.code = dst.entry_code "
        "WHERE e.relation = 'may_indicate' AND e.src_code IN (" + placeholders + ") "
        "AND e.enabled = 1 AND e.is_deleted = 0 AND dst.is_deleted = 0 "
        f"AND kb.is_deleted = 0 AND kb.review_status IN {RETRIEVABLE_STATUSES} "
        "AND e.species_scope IN ('all', %s) AND e.age_stage_scope IN ('all', %s) "
        "AND kb.species_scope IN ('all', %s) AND kb.age_stage_scope IN ('all', %s) "
        "ORDER BY e.weight DESC, kb.code ASC LIMIT %s"
    )
    params = (*node_codes, species_scope or "all", age_stage,
              species_scope or "all", age_stage, settings.knowledge_max_candidates)
    return query(sql, params)


def _query_entries_by_codes(codes: list[str], species_scope: str | None, age_stage: str) -> list[dict]:
    """按编号取条目（症状节点自己的说明条目走这一条路）。过滤条件与其它查询一致。"""
    placeholders = ", ".join(["%s"] * len(codes))
    sql = (
        "SELECT code, title, summary, body, category_code, species_scope, age_stage_scope, "
        "source_title, source_version, source_url, review_status, confidence, risk_hint, "
        "structured_payload FROM knowledge_entry "
        "WHERE code IN (" + placeholders + ") AND is_deleted = 0 "
        f"AND review_status IN {RETRIEVABLE_STATUSES} "
        "AND species_scope IN ('all', %s) AND age_stage_scope IN ('all', %s) "
        "ORDER BY code ASC"
    )
    return query(sql, (*codes, species_scope or "all", age_stage))



# ---------------------------------------------------------------- C 端浏览（F024 / F025）


def browse(*, category_code: str | None = None, keyword: str | None = None, code: str | None = None,
           page: int = 1, page_size: int = 20) -> tuple[list[KnowledgeItem], int, str]:
    """按分类 / 关键词分页读条目（C 端知识库浏览），或取单条（给了 `code`）。

    **与检索（`search`）的区别**：检索回答「这次咨询该引用什么」，要排序、要安全过滤、要关系层召回；
    浏览回答「知识库里有什么」，只要**可读的条目按分类列出来**。所以这里不做打分、不做禁忌过滤——
    安全门管的是「不要把禁忌项推荐给这个物种」，而浏览是用户自己翻，不是系统推荐。

    `keyword` 走与检索同一套 MySQL 布尔模式短语（ngram 下短语 = 中文字串匹配，#63 实测），
    所以「犬瘟」能命中「犬瘟热」——浏览与检索在同一张表上搜，不该有两种中文匹配行为。

    :return: (条目, 总数, 知识层状态)；状态 `ok` / `unavailable`（读不到就返回空 + unavailable，不抛）
    """
    try:
        rows, total = _browse_rows(category_code=category_code, keyword=keyword, code=code,
                                   page=page, page_size=page_size)
    except DbUnavailable:
        return [], 0, "unavailable"
    except Exception as exc:  # noqa: BLE001 —— 与检索同一条纪律：读不到都不该让请求 500
        logger.warning("知识库浏览读不到，返回空：%s", exc)
        return [], 0, "unavailable"

    # 浏览与检索共用一个层标记：条目本身不在「几层命中」的意义上，给 l3（正文层）即可
    items = [_to_item(row, layers=(LAYER_L3,)) for row in rows]
    return items, total, "ok"


def _browse_rows(*, category_code: str | None, keyword: str | None, code: str | None,
                 page: int, page_size: int) -> tuple[list[dict], int]:
    """真正查库那一段。**只读 `knowledge_*`**（ADR-0009 的例外就是本模块）。

    过滤条件与检索一致（复核状态、未删除），另外两件浏览独有的事：
    **分类精确筛**（浏览是按分类翻的）与**总数**（分页器要它）。
    """
    where = [f"e.review_status IN {RETRIEVABLE_STATUSES}", "e.is_deleted = 0"]
    params: list[object] = []
    boolean = build_boolean_query([keyword] if keyword else [])
    if code:
        where.append("e.code = %s")
        params.append(code)
    if category_code:
        where.append("e.category_code = %s")
        params.append(category_code)
    if boolean:
        where.append("MATCH(e.title, e.summary, e.body) AGAINST (%s IN BOOLEAN MODE)")
        params.append(boolean)

    clause = " AND ".join(where)
    # 分类名一并取回（C 端不认编码；这张表是词典，不是要引用/复核的条目，不走 RETRIEVABLE_STATUSES）
    select = (
        "SELECT e.code, e.title, e.summary, e.body, e.category_code, c.name AS category_name, "
        "e.species_scope, e.age_stage_scope, e.source_title, e.source_version, e.source_url, "
        "e.review_status, e.confidence, e.risk_hint, e.structured_payload "
        "FROM knowledge_entry e LEFT JOIN knowledge_category c ON c.code = e.category_code "
        f"WHERE {clause} "
    )
    total_row = query_one(f"SELECT COUNT(*) AS total FROM knowledge_entry e WHERE {clause}", tuple(params))
    total = int(total_row.get("total") or 0) if total_row else 0

    offset = max(0, (page - 1) * page_size)
    rows = query(
        # 排序：分类顺序（运营维护的词典顺序）→ 编号。不按时间：知识不是内容流，编号即稳定顺序
        select + "ORDER BY c.sort_order IS NULL, c.sort_order, e.code LIMIT %s OFFSET %s",
        (*params, page_size, offset),
    )
    return rows, total

# ---------------------------------------------------------------- 行 → 条目


def _to_item(row: dict, *, layers: tuple[str, ...], score: float = 0.0, note: str = "") -> KnowledgeItem:
    """查库行 → 条目。**每个字段都有兜底**：缺列、None、空串都不该让一条召回到的知识进不了上下文。

    两个默认值是安全方向的，别改成「看起来更合理」的值：

    - `review_status` 缺省 `pending_review`：缺省成 vetted 等于把没复核的内容当依据（ADR-0033）；
    - `entry_code` 优先于 `code`：L2 那几路的 `code` 是**节点**的编号，条目编号在 `entry_code`
      里——取错就会把节点编号当引用编号带出去，模型引它时被判成编造、整条结论被删。
    """
    return KnowledgeItem(
        code=str(row.get("entry_code") or row.get("code") or ""),
        title=str(row.get("title") or ""),
        summary=str(row.get("summary") or ""),
        body=str(row.get("body") or ""),
        category_code=str(row.get("category_code") or ""),
        category_name=_none_or_str(row.get("category_name")),
        species_scope=str(row.get("species_scope") or "all"),
        age_stage_scope=str(row.get("age_stage_scope") or "all"),
        source_title=str(row.get("source_title") or ""),
        source_version=_none_or_str(row.get("source_version")),
        source_url=_none_or_str(row.get("source_url")),
        review_status=str(row.get("review_status") or "pending_review"),
        confidence=str(row.get("confidence") or "medium"),
        risk_hint=_none_or_str(row.get("risk_hint")),
        payload=_as_payload(row.get("structured_payload")),
        layers=layers,
        score=score,
        note=note,
    )


def _replace(item: KnowledgeItem, **changes) -> KnowledgeItem:
    """frozen dataclass 的「改几个字段的副本」。抽一层只是让调用点读起来一致。"""
    return replace(item, **changes)


def _none_or_str(raw: object) -> str | None:
    """可空文本列 → `None` / 去空白的字符串。**空串按 `None` 算**。

    留成空串会让 `citation()` 里多一个没有意义的空字段，而 C 端分不清「没有来源」
    与「来源是空串」——两者的展示该不一样。
    """
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None


def _as_list(raw: object) -> tuple[str, ...]:
    """JSON 数组列（如 `knowledge_node.alias`）→ 字符串元组，空白项丢掉。

    三种写法都认（bytes / JSON 字符串 / 已经是数组，取决于列型与驱动），**解析失败返回空元组、
    不抛错**：别名读不出来最多少几个召回写法，不该让一整次检索变成 `unavailable`。
    """
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return ()
    if not isinstance(raw, list):
        return ()
    return tuple(str(item).strip() for item in raw if str(item).strip())


def _as_payload(raw: object) -> dict | None:
    """JSON 对象列（`knowledge_entry.structured_payload`）→ dict。

    读法与 `_as_list` 同源（bytes / JSON 字符串 / 已经是对象），但**失败返回 `None` 而不是 `{}`**：
    `None` 是「这条没有结构化事实」的表达，L1 与普通文本条目的分界就靠它；空 dict 会让
    「没有载荷」与「载荷是空对象」混成一种。
    """
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return None
    return raw if isinstance(raw, dict) else None


__all__ = [
    "LAYER_L1",
    "LAYER_L2",
    "LAYER_L3",
    "Context",
    "KnowledgeItem",
    "build_boolean_query",
    "search",
    "strip_invalid_refs",
]
