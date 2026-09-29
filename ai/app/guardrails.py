"""输出层护栏（切片 #98，决策见 ADR-0021 第四条）。

提示词里的铁律是**第一层**：它降低概率，但堵不住「模型这次没听话」。
这里做**第二层**：把模型返回的文本过一遍禁用词、剂量模式与药名表，命中就改写该条并留痕。

为什么必须有第二层：交付文档 9.5 列了三个绝不——**绝不确诊、绝不开处方、绝不给出用药剂量**，
只靠提示词意味着**出了事没人知道是哪一次漏的**——留痕字段 `guard_hits` 就是为这件事设的。
"""

from __future__ import annotations

import re

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

#: 越界表述：确诊 / 处方 / 直接推荐用药。
BANNED_PHRASES = ("确诊", "处方", "剂量", "开药", "可以用药", "建议用药")

#: 药物名称表。**「不开处方」这一档原先只靠提示词**：实测「建议喂点阿莫西林，一天两次」
#: 在护栏里 0 命中、原样透出（2026-09-28 测试报告 D18）。推荐某种药本身就是处方行为，
#: 与有没有剂量数字无关，所以命中即整句改写。
#:
#: 这份表是**临时的**：按 ADR-0021，词表最终要和红线词表一样入 DB、由运营维护（#103）。
#: 取值原则：人用常见药里**对猫狗有明确毒性或剂量陷阱**的优先——对乙酰氨基酚对猫剧毒、
#: 布洛芬对狗伤肾，模型把人用量搬过来的后果最重。
DRUG_TERMS = (
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

    只在下标空间里做切片与 rsplit，所以调用方传进来的必须是**已经小写化**的整句：
    大小写转换在某些语言里会改变长度（如 'İ'），一边转一边用下标会错位。
    """
    text = lowered[max(0, start - window):start]
    for boundary in (*CLAUSE_BOUNDARIES, *EN_CLAUSE_BOUNDARIES):
        if boundary in text:
            text = text.rsplit(boundary, 1)[-1]
    return text


def _is_warning(lowered: str, start: int) -> bool:
    """药名出现在否定语境里吗——中文看紧邻的一小段，英文看同一分句的一大段。"""
    if any(cue in _clause_before(lowered, start, NEGATION_WINDOW) for cue in NEGATION_CUES):
        return True
    return any(cue in _clause_before(lowered, start, EN_NEGATION_WINDOW) for cue in EN_NEGATION_CUES)


def _recommended_drug(lowered: str) -> str | None:
    """返回第一个**被推荐**（存在一次非否定语境的提及）的药名；全是警告则返回 None。

    为什么遍历**每一处出现**：只看第一次会漏掉「先警告、后推荐」的写法——
    「不要给猫喂布洛芬。退烧可以用布洛芬。」实测后半句的推荐被整体放过（深测轮抓到的绕过）。
    一个药名只要有一次是在推荐，它就已经是处方行为了。
    """
    for drug in DRUG_TERMS:
        start = lowered.find(drug)
        while start >= 0:
            if not _is_warning(lowered, start):
                return drug
            start = lowered.find(drug, start + 1)
    return None


def review(text: str) -> tuple[str, list[str]]:
    """检查一段文本，返回 (改写后的文本, 命中的护栏标记)。

    命中的条目不静默删除：删除会让用户以为模型没提过这件事，
    改写则保留「这里本来有个建议、但越界了」这个信息量。
    """
    if not text:
        return text, []

    # 药名一律在**小写化**的副本上找：「Amoxicillin」与「amoxicillin」是同一个药。
    # 中文不受影响；大小写转换与下标错位的问题见 _clause_before。
    lowered = text.lower()

    hits: list[str] = []
    if any(pattern.search(text) for pattern in DOSE_PATTERNS):
        hits.append("dose")
    for phrase in BANNED_PHRASES:
        if phrase in text:
            hits.append(f"phrase:{phrase}")
    recommended = _recommended_drug(lowered)
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


def review_list(items: list[str]) -> tuple[list[str], list[str]]:
    """对一组文本逐条过护栏。条目为空或只剩改写话术时丢弃，避免出现空条目。"""
    out: list[str] = []
    hits: list[str] = []
    for item in items:
        rewritten, item_hits = review(item)
        hits.extend(item_hits)
        if rewritten.strip() and rewritten.strip() != REPLACEMENT:
            out.append(rewritten.strip())
    return out, hits
