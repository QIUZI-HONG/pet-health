"""输出层护栏（切片 #98，决策见 ADR-0021 第四条）。

提示词里的铁律是**第一层**：它降低概率，但堵不住「模型这次没听话」。
这里做**第二层**：把模型返回的文本过一遍禁用词、剂量模式与药名表，命中就改写该条并留痕。

为什么必须有第二层：交付文档 9.5 列了三个绝不——**绝不确诊、绝不开处方、绝不给出用药剂量**，
只靠提示词意味着**出了事没人知道是哪一次漏的**——留痕字段 `guard_hits` 就是为这件事设的。

**词表从哪来**（切片 #103 之后）：药名表与越界表述表由**运营维护、存在库里**
（`knowledge_guard_term`），下面那两个 `CODE_*` 常量是**读不到库时的基线**，也是库里种子
的同一份内容（ADR-0010 把「提示词/红线词/分级规则」这一类归为业务可调项；ADR-0026 第三节
记过「护栏词表暂时留在代码里」是一条有期限的例外，这里就是它的归宿）。

**剂量正则仍然留在代码里**：它是代码常量那一层——正则改错的代价是「该拦的没拦」，
而运营无从验证，改它应该走发版与测试（ADR-0033 记录这条边界）。
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger("pet_health_ai")

#: 剂量模式：数字 + 单位。宠物用药剂量与体重强相关，任何剂量数字都不该由 AI 给出。
DOSE_PATTERN = re.compile(
    r"\d+(?:\.\d+)?\s*(?:mg|g|ml|cc|iu|毫克|微克|克|毫升|片|粒|支|单位|mg/kg)",
    re.IGNORECASE,
)

#: 中文数字剂量。**配方写成中文数字时一个字都不含阿拉伯数字**，上面那条整段漏掉：
#: 「每次喂半片」「一天两次，每次两粒」「口服一片即可」实测 0 命中（2026-09-28 深测轮）。
#:
#: 为什么**必须带上给药语境**（每次/口服/喂…）才对量词动手：只按「数字 + 量词」匹配的话，
#: 「喂两片鸡胸肉」这类正常照护建议也会被换掉。要求前面有给药动词/频次词，误伤面才收得住
#: ——残余的误伤（「喂两片鸡胸肉」仍会被替）是有意接受的成本，见 test_guardrail_evasion.py。
CN_NUMERAL_DOSE_PATTERN = re.compile(
    r"(?:每次|每日|每天|一天|一日|口服|服用|喂|吃)\s*[半一二两三四五六七八九十]+\s*"
    r"(?:片|粒|支|袋|包|丸|毫升|毫克|微克|克|单位)",
)

#: 判断「有没有剂量」用的全部模式。以后再加数字写法，往这里加一条即可（判断与替换共用它）。
DOSE_PATTERNS = (DOSE_PATTERN, CN_NUMERAL_DOSE_PATTERN)

#: 只替掉剂量片段时留下的占位。不清空整句：前后那些观察建议对用户是有用的。
DOSE_REPLACEMENT = "（剂量请遵医嘱）"

#: 越界表述：确诊 / 处方 / 直接推荐用药。**代码基线**，生效值见 `_effective_terms`。
CODE_BANNED_PHRASES = ("确诊", "处方", "剂量", "开药", "可以用药", "建议用药")

#: 药物名称表。**「不开处方」这一档原先只靠提示词**：实测「建议喂点阿莫西林，一天两次」
#: 在护栏里 0 命中、原样透出（2026-09-28 测试报告 D18）。推荐某种药本身就是处方行为，
#: 与有没有剂量数字无关，所以命中即整句改写。
#:
#: 这份表是**代码基线**（读不到 `knowledge_guard_term` 时的回落值，内容与库里的种子一致）。
#: 取值原则：人用常见药里**对猫狗有明确毒性或剂量陷阱**的优先——对乙酰氨基酚对猫剧毒、
#: 布洛芬对狗伤肾，模型把人用量搬过来的后果最重。
CODE_DRUG_TERMS = (
    "阿莫西林", "阿司匹林", "布洛芬", "对乙酰氨基酚", "扑热息痛", "头孢", "阿奇霉素",
    "红霉素", "土霉素", "甲硝唑", "伊维菌素", "阿维菌素", "地塞米松", "泼尼松",
    "氯霉素", "氟哌酸", "诺氟沙星", "左氧氟沙星", "奥美拉唑", "蒙脱石散", "泻药",
    "感冒药", "感康", "泰诺", "芬必得",
    # ---- 英文名（深测轮补）----
    # 只列中文名的漏法实测过：**中英混写是模型的常态**，「give it ibuprofen for pain」
    # 「吃点 Tylenol 就行」全部 0 命中、原样透出。换成英文名不改变「这是推荐用药」这件事，
    # 所以词表要同时覆盖两种写法。匹配前统一转小写（`Amoxicillin` 也是阿莫西林）。
    # 注意：这份表是**子串**匹配，「advil」「motrin」这类商品名单独列，不要写短到会误撞的缩写。
    "amoxicillin", "aspirin", "ibuprofen", "acetaminophen", "paracetamol",
    "tylenol", "advil", "motrin", "cephalexin", "azithromycin", "erythromycin",
    "metronidazole", "ivermectin", "dexamethasone", "prednisone", "prednisolone",
    "chloramphenicol", "norfloxacin", "levofloxacin", "omeprazole",
)

#: 改写后的兜底话术。宁严勿松（docs/conventions.md）——不解释「模型本来想说什么」。
REPLACEMENT = "具体处理与用药请由兽医面诊决定。"

#: 否定语境：药名出现在这些词后面时，模型是在**警告别用**，不是在推荐。
#: 判据只需要看药名往前一小段（同一分句内），因为中文的否定词紧贴动词：
#: 「千万别给猫用对乙酰氨基酚」「不要喂布洛芬」「避免使用伊维菌素」。
#:
#: 为什么必须区分：把「别用 X」改写成「请由兽医决定」会**丢掉一条正确且重要的警告**
#: （对乙酰氨基酚对猫是剧毒）——误伤好回答与漏掉坏回答一样糟。
NEGATION_CUES = ("别", "不要", "不能", "不可", "切勿", "禁止", "避免", "严禁", "不得", "千万不要")

#: 英文的否定词。英文把否定放在动词前（"do not give your cat X"），离药名比中文远，
#: 所以窗口要放大，但**只在同一分句内有效**（见 CLAUSE_BOUNDARIES）。
EN_NEGATION_CUES = ("not", "never", "avoid", "don't", "dont", "without")

#: 「推荐」的**正向线索**（建议/给药动词）。只在 `require_cue=True` 时要求它出现——
#: 那条路径是「药物自己的说明条目」，里面药名常出现在陈述句里（「X 对猫剧毒」「X 对犬猫
#: 都可能造成损伤」），只按「没有否定词」判断会把安全提醒误判成推荐，而误判的代价是
#: 把最该说的话整段改写掉。
RECOMMENDATION_CUES = (
    "建议", "可以", "可用", "推荐", "服用", "口服", "喂", "吃", "使用", "用它", "买", "开点", "给",
    "give", "use", "take", "recommend",
)

#: 药名前多少字符内出现否定词就算「在警告」（同分句内一般不超过这个距离）
NEGATION_WINDOW = 12
#: 英文的窗口：一个分句内 "do not give your cat " 就已经 22 个字符
EN_NEGATION_WINDOW = 40

#: 分句边界：否定词跨过它就管不到这一句的药名。
#: **逗号也算边界**——「别担心，建议喂点蒙脱石散」里的「别」属于前半句，
#: 不该给后半句的推荐洗白（评审提的用例）
CLAUSE_BOUNDARIES = ("。", "；", ";", "！", "？", "\n", "，", "、", ",")

#: 英文的转折/因果连词同样算边界：「Do not give ibuprofen, but aspirin is fine」
#: 后半句是推荐，不能被前半句的否定洗白。**必须列出**——英文没有「，」也能组织分句。
EN_CLAUSE_BOUNDARIES = (
    " but ", " however ", " because ", " although ", " though ", " yet ",
    " instead ", " rather ", " while ",
)


def _clause_before(lowered: str, start: int, window: int) -> str:
    """药名之前、同一分句内的一小段文本（用来找否定词）。

    为什么要切分句：否定词只管到它所在的那一句，「别担心，建议喂点蒙脱石散」里的「别」
    属于前半句，不该给后半句的推荐洗白。英文那组转折连词是**必须**的——英文没有「，」
    也能组织分句。`window` 由调用方按中/英文给（中文否定词紧贴动词，英文的离药名远）。

    只在下标空间里做切片与 rsplit，所以调用方传进来的必须是**已经小写化**的整句：
    大小写转换在某些语言里会改变长度（如 'İ'），一边转一边用下标会错位。
    """
    text = lowered[max(0, start - window):start]
    for boundary in (*CLAUSE_BOUNDARIES, *EN_CLAUSE_BOUNDARIES):
        if boundary in text:
            text = text.rsplit(boundary, 1)[-1]
    return text


def _is_warning(lowered: str, start: int) -> bool:
    """药名出现在否定语境里吗——中文看紧邻的一小段，英文看同一分句的一大段。

    两个窗口不能合并：中文的「不要」紧贴动词，英文的否定离药名有二十来个字符，
    拿中文那个窗口去看英文等于永远判不出警告。**判成警告就是不改写**，所以偏向认警告
    是安全的一侧：代价只是漏改写一次，反过来会把一条正确的警告（「别用对乙酰氨基酚」）删掉。
    """
    if any(cue in _clause_before(lowered, start, NEGATION_WINDOW) for cue in NEGATION_CUES):
        return True
    return any(cue in _clause_before(lowered, start, EN_NEGATION_WINDOW) for cue in EN_NEGATION_CUES)


def _effective_terms() -> dict[str, tuple[str, ...]]:
    """生效的词表：优先运营在库里维护的那份，读不到就用代码基线。

    判据是「drug 与 phrase **两类都非空**」：只读回一类时整份退回代码基线，免得出现
    「药名表是库里的、越界表述是代码的」这种半新半旧、出了事说不清是哪份在生效的组合。

    **不在这里再存一份缓存**：`ops.load()` 自己带 TTL 缓存（`ops_cache_seconds`），
    多一层缓存就多一处「改了没生效」的排查面。
    """
    try:
        from . import ops

        terms = ops.load().guard_terms
        if terms.get("drug") and terms.get("phrase"):
            return terms
    except Exception as exc:  # noqa: BLE001 —— 配置读不到不该让输出护栏失效（它是最外层的一道闸）
        logger.warning("护栏词表读不到，用代码基线：%s", exc)
    return {"drug": CODE_DRUG_TERMS, "phrase": CODE_BANNED_PHRASES}


def is_recommended_mention(lowered: str, term: str, *, require_cue: bool = False) -> bool:
    """这个词在文本里**有至少一次是「推荐」语境**吗（不是「别用」那种警告）。

    抽成公开函数是因为有两个调用方，而它们必须用同一套判据：
    输出护栏（改写模型的话）与知识检索的安全门（剔除条目）——两处对「推荐 vs 警告」的判断
    不一致时，会出现「护栏放行的东西被检索侧丢掉」这种查不出原因的行为差。

    `require_cue=True` 再加一道：要求药名附近（同一分句内）出现**正向推荐线索**。
    它给「药物自己的说明条目」用——那类文本里药名大量出现在陈述句里，只按「没有否定词」
    判断会把「对乙酰氨基酚对猫剧毒」当成推荐用药，而那条恰恰是最该保留的安全提醒。
    两边代价不对称，所以判据也不对称：模型输出误伤只是多改写一句，知识条目误伤是把
    整条安全提醒丢掉。
    """
    start = lowered.find(term)
    while start >= 0:
        if not _is_warning(lowered, start) and (
            not require_cue or _has_recommendation_cue(lowered, start)
        ):
            return True
        start = lowered.find(term, start + 1)
    return False


def _has_recommendation_cue(lowered: str, start: int) -> bool:
    """药名之前、同一分句内有没有推荐动词（「建议喂」「可以吃」）。

    只服务 `require_cue=True` 那条路径（药物自己的说明条目）：那类文本里药名大量出现在
    陈述句里，「没有否定词」不足以判定推荐。窗口与否定词同一个，且 `_clause_before`
    已经把它切到分句内，所以跨分句的「可以」不会给这一句的药名背书。
    """
    window = _clause_before(lowered, start, NEGATION_WINDOW)
    return any(cue in window for cue in RECOMMENDATION_CUES)


def _recommended_drug(lowered: str, exempt: frozenset[str]) -> str | None:
    """返回第一个**被推荐**（存在一次非否定语境的提及）的药名；全是警告则返回 None。

    为什么遍历**每一处出现**：只看第一次会漏掉「先警告、后推荐」的写法——
    「不要给猫喂布洛芬。退烧可以用布洛芬。」实测后半句的推荐被整体放过（深测轮抓到的绕过）。
    一个药名只要有一次是在推荐，它就已经是处方行为了。

    返回的是**词表里第一个**命中的药名（顺序即 `_effective_terms` 里那份）：留痕只把它当
    「这轮出现了推荐用药」的证据，所以增删词表让同一条文本报出不同的药名不算缺陷。

    `exempt` 是**不参与这条扫描**的词（见 `review` 的说明）：它只给「知识条目文本」这一路用，
    用来豁免「药物自己的说明条目」，普通文本传空集。
    """
    for drug in _effective_terms().get("drug", ()):
        if drug in exempt:
            continue
        if is_recommended_mention(lowered, drug):
            return drug
    return None


def review(text: str, *, exempt_drugs: frozenset[str] = frozenset()) -> tuple[str, list[str]]:
    """检查一段文本，返回 (改写后的文本, 命中的护栏标记)。

    命中的条目不静默删除：删除会让用户以为模型没提过这件事，
    改写则保留「这里本来有个建议、但越界了」这个信息量。

    `exempt_drugs` 只服务一个场景：**知识条目里在讲某个药本身**（药物类条目说
    「对乙酰氨基酚对猫剧毒」）。那类陈述句按「出现药名即命中」会被整段改写，
    而它恰恰是最该保留的安全提醒。豁免名单由调用方按 `knowledge_node.entry_code` 给，
    是**数据驱动的例外**，不是「某些词网开一面」的硬编码名单。
    """
    if not text:
        return text, []

    # 药名一律在**小写化**的副本上找：「Amoxicillin」与「amoxicillin」是同一个药。
    # 中文不受影响；大小写转换与下标错位的问题见 _clause_before。
    lowered = text.lower()
    terms = _effective_terms()

    hits: list[str] = []
    if any(pattern.search(text) for pattern in DOSE_PATTERNS):
        hits.append("dose")
    for phrase in terms.get("phrase", ()):
        if phrase in text:
            hits.append(f"phrase:{phrase}")
    recommended = _recommended_drug(lowered, exempt_drugs)
    if recommended:
        hits.append(f"drug:{recommended}")

    if not hits:
        return text, []

    # 药名与越界表述：整句换成兜底话术。推荐药名没有「只删掉药名」这种半句话的说法——
    # 剩下的半句仍然是建议，而建议的对象已经没了
    if any(hit.startswith(("phrase:", "drug:")) for hit in hits):
        return REPLACEMENT, hits

    # 只剩剂量：只替掉剂量片段，保留句子其余部分——整句删掉会把有用的观察建议一起丢掉
    out = text
    for pattern in DOSE_PATTERNS:
        out = pattern.sub(DOSE_REPLACEMENT, out)
    return out, hits


def review_list(items: list[str], *, exempt_drugs: frozenset[str] = frozenset()) -> tuple[list[str], list[str]]:
    """对一组文本逐条过护栏。条目为空或只剩改写话术时丢弃，避免出现空条目。"""
    out: list[str] = []
    hits: list[str] = []
    for item in items:
        rewritten, item_hits = review(item, exempt_drugs=exempt_drugs)
        hits.extend(item_hits)
        if rewritten.strip() and rewritten.strip() != REPLACEMENT:
            out.append(rewritten.strip())
    return out, hits


def drug_terms() -> tuple[str, ...]:
    """当前生效的药名表（库里的那份，读不到时是代码基线）。

    给知识检索的安全门用：它要遍历药名做「推荐 vs 警告」判断，词表必须与护栏同源。
    """
    return tuple(_effective_terms().get("drug", ()))
