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
)

#: 改写后的兜底话术。宁严勿松（docs/conventions.md）——不解释「模型本来想说什么」。
REPLACEMENT = "具体处理与用药请由兽医面诊决定。"


def review(text: str) -> tuple[str, list[str]]:
    """检查一段文本，返回 (改写后的文本, 命中的护栏标记)。

    命中的条目不静默删除：删除会让用户以为模型没提过这件事，
    改写则保留「这里本来有个建议、但越界了」这个信息量。
    """
    if not text:
        return text, []

    hits: list[str] = []
    if DOSE_PATTERN.search(text):
        hits.append("dose")
    for phrase in BANNED_PHRASES:
        if phrase in text:
            hits.append(f"phrase:{phrase}")
    for drug in DRUG_TERMS:
        if drug in text:
            hits.append(f"drug:{drug}")

    if not hits:
        return text, []

    # 药名与越界表述：整句换成兜底话术。推荐药名没有「只删掉药名」这种半句话的说法——
    # 剩下的半句仍然是建议，而建议的对象已经没了
    if any(hit.startswith(("phrase:", "drug:")) for hit in hits):
        return REPLACEMENT, hits

    # 只剩剂量：只替掉剂量片段，保留句子其余部分——整句删掉会把有用的观察建议一起丢掉
    return DOSE_PATTERN.sub("（剂量请遵医嘱）", text), hits


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
