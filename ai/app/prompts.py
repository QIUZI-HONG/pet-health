"""提示词与工具定义（**代码基线**）。

按 [ADR-0010](../../docs/adr/0010-ai-config-layering.md)，提示词模板属于「业务可调项」，
应当入库、由运营后台改、即时生效——切片 #103 已经把它落成 `knowledge_prompt_template` 表，
运行时由 `ops.load()` 读取，**版本号也随行读出来**（v1 表里的 `version`）。

所以本文件现在的角色是**代码基线**：
* 库里读不到（迁移没跑、库故障、运营把版本全停用）时的回落值；
* 与库里的第一版种子（`V20__ai_ops_config.sql` 的 p1 行）**刻意保持同一份文本**——
  两边不一致时会出现「以为在用同一个版本号、其实是两种行为」这种查不出的问题。

改动这里的正文时，同时做两件事：新增一行 `knowledge_prompt_template`（新版本号，不要原地改），
以及把这个常量同步过来（它仍是回落值）。**版本号的唯一来源是行里的 `version` 字段**
（不再是 `settings.prompt_version`——那个值现在只用于基线回落）。
"""

import json

SYSTEM_PROMPT = """你是一只宠物（犬、猫）的健康分诊助手，服务于宠物主人。
你的唯一产出是**风险分级**，不是诊断。

铁律：
1. 只输出绿 / 黄 / 红三档风险等级，表示「就医的紧迫程度」，不是疾病名称。
2. **绝不给确诊、绝不开处方、绝不给用药剂量**。需要用药时只说「请由兽医判断」。
3. 信息不足时按更严的一档给（宁严勿松）。出现以下任一情况一律红色：
   呼吸困难、抽搐、大量出血、无法站立、持续呕吐超过 24 小时、误食毒物或异物、
   幼宠（3 个月以下）或老年宠精神沉郁、腹部胀大、产后异常。
4. 红色风险必须在 action_suggestion 里明确写「立即送医」，并提示 24 小时医院。
5. action_suggestion 用 2–3 句白话讲清下一步做什么；care_tips 给居家观察要点；
   possible_causes 只列可能方向，用「可能」措辞，不超过 3 条。
6. 用户可能附了图片（皮肤、耳道、排泄物等）。图片只用于**辅助观察**：看到什么就据此判断，
   看不清或与症状无关时如实说明「图片不足以判断，建议现场检查」，**不要**凭想象描写图片内容。
   图片不能替代兽医诊断，任何用药仍由兽医判断。
7. 消息里可能带一段【知识条目】，每条以编号开头（形如 K-0010）。可能原因**必须**来自这些条目，
   并在该条末尾标出来源编号，如「可能：饮食不当 [K-0010]」。**只许引用给出的编号，绝不编造编号**；
   知识条目没有覆盖的方向不要写进 possible_causes。条目里没写到的用药、剂量、诊断一律不写。
8. 最后必须调用 report_triage 工具上报结果，不要在正文里输出 JSON。"""

#: 结构化输出的载体。为什么用工具调用而不是 json_object：
#: 工具 schema 能表达类型与取值范围，且模型返回的是参数对象，客户端校验一次即可
#: （#61 的结论：没有任何厂商支持在图片输入下做 json_schema 强约束，只能自己兜）。
#: ⚠️ 实测限制：这家模型在思考模式下**拒绝强制 tool_choice**（HTTP 400），只能用 auto，
#: 所以「模型不调用工具」是必须处理的正常分支，不能当成异常。
REPORT_TOOL = {
    "type": "function",
    "function": {
        "name": "report_triage",
        "description": "上报本次咨询的风险分级结果",
        "parameters": {
            "type": "object",
            "properties": {
                "risk_level": {
                    "type": "integer",
                    "enum": [1, 2, 3],
                    "description": "1 绿（居家观察）/ 2 黄（尽快就医）/ 3 红（立即就医）",
                },
                "possible_causes": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "可能原因，用「可能」措辞，最多 3 条；每条末尾标出知识条目编号，如 [K-0010]",
                },
                "action_suggestion": {"type": "string", "description": "下一步该做什么，2–3 句白话"},
                "need_hospital": {"type": "boolean", "description": "是否建议就医"},
                "care_tips": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "居家照护要点，最多 3 条",
                },
                "citations": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "本次结论引用的知识条目编号（K-xxxx），只能引用给定的编号",
                },
            },
            "required": ["risk_level", "action_suggestion", "need_hospital"],
        },
    },
}


def build_user_prompt(*, pet: dict, text: str, history: list[dict], image_count: int,
                      knowledge: list | None = None) -> str:
    """把宠物档案、本次症状与**检索到的知识条目**拼成一段给模型的输入。

    尽量结构化地给背景（物种/年龄/品种/慢病/近期记录）——同一种症状在幼宠与老年宠身上
    紧迫程度完全不同，缺了这些模型只能瞎猜。

    `knowledge` 是 `knowledge.KnowledgeItem` 的列表，由调用方传入（**必须已经过安全门与
    输出护栏**，见 `knowledge._filter`）。这里只负责排版：每条给编号、标题、摘要与一段正文，
    并明确「可能原因必须来自它们、只能引用这些编号」——引用校验（`main._review_citations`）
    会把编造的编号剔除掉，所以提示词与校验是同一套口径的两端。
    """
    lines = ["【宠物】"]
    lines.append(f"- 物种：{'猫' if pet.get('species') == 2 else '犬'}")
    if pet.get("breed"):
        lines.append(f"- 品种：{pet['breed']}")
    if pet.get("birth_date"):
        lines.append(f"- 生日：{pet['birth_date']}")
    if pet.get("weight"):
        lines.append(f"- 体重：{pet['weight']} kg")
    chronic = pet.get("chronic") or []
    if chronic:
        lines.append(f"- 已知慢病：{'、'.join(str(c) for c in chronic)}")
    recent = pet.get("recent_records") or []
    if recent:
        lines.append(f"- 近期记录：{json.dumps(recent[:5], ensure_ascii=False)}")

    if image_count:
        lines.append(f"\n【本次附带图片】{image_count} 张，随本条消息一起提供，请结合图片与文字判断。")

    if knowledge:
        lines.append(render_knowledge(knowledge))

    if history:
        lines.append("\n【对话上文】")
        for item in history[-4:]:
            role = "主人" if item.get("role") == "user" else "助手"
            lines.append(f"- {role}：{str(item.get('content', ''))[:200]}")

    lines.append("\n【本次症状描述】")
    lines.append(text)
    if knowledge:
        lines.append("\n请结合【知识条目】判断风险等级，可能原因标注来源编号，并调用 report_triage 上报。")
    else:
        lines.append("\n请按铁律判断风险等级，并调用 report_triage 上报。")
    return "\n".join(lines)


def render_knowledge(items: list, body_limit: int | None = None) -> str:
    """把检索到的条目排成模型可读的一段。

    **结构化事实（L1）单独标出来**：疫苗/驱虫周期、毒物清单这类是「唯一权威」，
    模型可以照着说，但不能改口（63 号调研 §1.2：L3 文本只能解释，不能当判定依据）。
    正文按 `body_limit` 截断——检索是「取够用的那几段」，不是把整篇塞进上下文。
    """
    from .config import settings

    limit = body_limit or settings.knowledge_body_chars
    lines = [
        (
            "\n【知识条目】以下是平台知识库中与本问题相关的条目。可能原因必须来自它们，"
            "并在该条末尾标出编号（如 [K-0010]）；只能引用这些编号。"
        )
    ]
    for item in items:
        species = {"dog": "犬", "cat": "猫"}.get(item.species_scope, "犬猫通用")
        lines.append(f"- {item.code}｜{item.title}（{species}）")
        if item.summary:
            lines.append(f"  摘要：{item.summary}")
        if item.payload:
            # L1 的确定性答案：结构化给出，避免模型从散文里「理解」出周期与频次
            lines.append(f"  结构化事实：{json.dumps(item.payload, ensure_ascii=False)}")
        body = (item.body or "").strip()
        if body:
            lines.append(f"  正文：{body[:limit]}")
        if item.note:
            lines.append(f"  关联说明：{item.note}")
    return "\n".join(lines)
