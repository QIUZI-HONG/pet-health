"""提示词与工具定义。

⚠️ **临时位置**：按 [ADR-0010](../../docs/adr/0010-ai-config-layering.md)，提示词模板属于
「业务可调项」，应当入库、由运营后台改、即时生效（票 #103）。表还没建，所以先放在代码里，
并把 `PROMPT_VERSION` 手工维护起来——它是留痕字段，缺了就无法归因「分级漂移是提示词改的还是模型改的」。

搬进 DB 时：把 `SYSTEM_PROMPT` 与 `REPORT_TOOL` 的 JSON 一起入库，版本号随之自增，
本文件只保留读配置的代码。
"""

import json

#: 提示词版本。改动 SYSTEM_PROMPT 或工具 schema 时必须同步 +1，否则留痕对不上。
PROMPT_VERSION = "p0-code"

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
6. 最后必须调用 report_triage 工具上报结果，不要在正文里输出 JSON。"""

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
                    "description": "可能原因，用「可能」措辞，最多 3 条",
                },
                "action_suggestion": {"type": "string", "description": "下一步该做什么，2–3 句白话"},
                "need_hospital": {"type": "boolean", "description": "是否建议就医"},
                "care_tips": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "居家照护要点，最多 3 条",
                },
            },
            "required": ["risk_level", "action_suggestion", "need_hospital"],
        },
    },
}


def build_user_prompt(*, pet: dict, text: str, history: list[dict], image_count: int) -> str:
    """把宠物档案与本次症状拼成一段给模型的输入。

    尽量结构化地给背景（物种/年龄/品种/慢病/近期记录）——同一种症状在幼宠与老年宠身上
    紧迫程度完全不同，缺了这些模型只能瞎猜。
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
        lines.append(f"\n【本次附带图片】{image_count} 张（当前模型看不见图片，已被忽略）")

    if history:
        lines.append("\n【对话上文】")
        for item in history[-4:]:
            role = "主人" if item.get("role") == "user" else "助手"
            lines.append(f"- {role}：{str(item.get('content', ''))[:200]}")

    lines.append("\n【本次症状描述】")
    lines.append(text)
    lines.append("\n请按铁律判断风险等级，并调用 report_triage 上报。")
    return "\n".join(lines)
