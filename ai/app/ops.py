"""运营可调项的读取（切片 #103；分层见 ADR-0010 的「业务可调项」那一层）。

这里只管**从库里读、带缓存、读不到就回落到代码基线**。三件事都是刻意的：

- **带 TTL 缓存**：运营改完最多滞后一个 TTL 生效，换来的是每次咨询不多几次查库。
  要更即时就把 TTL 调小（技术参数，在 `config.py`）。
- **读不到就回落代码基线，不抛错**：迁移没跑、库抖动、运营误删，都不该让咨询变成 500。
  但**回落这件事必须留痕**（`OpsConfig.available=False`）——「服务在跑但配置没生效」
  与「配置生效了」是两件事，混起来事后查不出问题（与 `red_flags` 对 available 的口径一致）。
- **开关的「读不到」还要能被调用方看见**：开关按关闭兜底（fail-closed 不变），但
  「运营把闸拉了」与「配置读不到所以闸是关的」是**两种事实**，对外上报的语义正好相反
  （前者不用告警，后者是故障）。`switch()` 只回值，要上报的用 `switch_state()`
  （ADR-0050 §三），留痕用 `switch_unreadable_marker()`。
  **兜底方向由调用方按开关的性质定，不是一律 fail-closed**：`retrieval_enabled` 读不到
  就不启用检索（少一层输入，是安全方向）；`force_rule_only` 读不到**照样调模型**——
  把它读成「运营拉了闸」等于用一次配置故障静默撤掉整条模型通道（判断与代价写在
  `main.consult` 那一段）。
- **代码基线与库里的种子同一份文本**（`prompts.SYSTEM_PROMPT` 对 `knowledge_prompt_template` 的 p1 行）：
  两边不一致时会出现「同一个版本号下两种行为」，那种问题没法查。

**不在这里的东西**：剂量正则（代码常量，改它要发版与测试）、模型名与超时（环境变量）。
理由都写在 ADR-0010 的分层表里。
"""

from __future__ import annotations

import json
import logging
import random
from dataclasses import dataclass, field

from . import prompts
from .config import settings
from .db import query
from .ttl_cache import TtlCache

logger = logging.getLogger("pet_health_ai")

#: 开关代码。集中在这里，免得三处各写一遍字符串。
SWITCH_FORCE_RULE_ONLY = "force_rule_only"
SWITCH_RETRIEVAL_ENABLED = "retrieval_enabled"
SWITCH_RETRIEVAL_STRICT = "retrieval_strict"


def switch_unreadable_marker(code: str) -> str:
    """开关「读不到」的留痕标记：`switch:<开关代码>:unreadable`。

    与 `citation:` / `kb:flag:` 同一套写法，进 `guard_hits`——那一列**已经随响应落库**，
    所以「这一轮的开关值没读到」不用等 Java 侧加字段就能被事后查到（ADR-0050 §三）。
    **开关确实为关时不要用它**：那等于把「运营关的」与「读不到」又混起来，正是这轮要修的缺陷。
    """
    return f"switch:{code}:unreadable"


@dataclass(frozen=True)
class PromptTemplate:
    """一份可用的提示词：版本号 + 正文 + 工具定义。"""

    version: str
    system_prompt: str
    tool_schema: dict
    #: 来源：db / code。留痕里能看出这次用的是库里那版还是代码基线
    origin: str = "code"


@dataclass(frozen=True)
class GradingRule:
    """一条分级规则：命中 `terms` 里任一词，风险**至少**给到 `min_level`。"""

    code: str
    name: str
    terms: tuple[str, ...]
    min_level: int
    species_scope: str = "all"
    age_stage_scope: str = "all"
    advice: str = ""

    def applies_to(self, species: int | None, age_stage: str) -> bool:
        """物种与年龄段过滤。与红线词表同一套取值（`red_flags._applies_to` 的同构实现）。"""
        if self.species_scope != "all" and species is not None:
            want = "dog" if species == 1 else "cat" if species == 2 else None
            if want is not None and self.species_scope != want:
                return False
        return self.age_stage_scope == "all" or self.age_stage_scope == age_stage


@dataclass(frozen=True)
class SwitchState:
    """一个开关的**取值**与**这个值是怎么来的**（ADR-0050 §三）。

    `enabled=False` 有两个来源，对运维的意义正好相反，所以两个字段必须一起看：

    - `known=True`：值是从库里读到的 → 关着就是**运营关的**（人为动作）；
    - `known=False`：开关表读不到，`enabled=False` 只是 fail-closed 的兜底 → **故障**。
    """

    enabled: bool
    #: 这个值读到了吗。False 表示配置读不到，`enabled` 不代表任何人的选择
    known: bool


@dataclass(frozen=True)
class OpsConfig:
    """一次加载拿到的全部运营可调项。"""

    prompt: PromptTemplate
    grading_rules: tuple[GradingRule, ...] = ()
    guard_terms: dict[str, tuple[str, ...]] = field(default_factory=dict)
    switches: dict[str, bool] = field(default_factory=dict)
    #: **开关表**读到没有。与 `available`（四条来源的汇总）分开：只要它是 False，
    #: `switches` 里就没有任何一项能当作「运营的选择」看（见 `switch_state`）。
    #: 默认 False 与 `available` 同一口径：没说读到，就按没读到算。
    switches_available: bool = False
    #: 批次里的**读取状态**：四条来源里有没有读不到的。读不到就用代码基线，并留痕
    available: bool = False
    detail: str = ""

    def switch(self, code: str) -> bool:
        """开关值。缺失按关闭算——**默认关**是安全方向：开关是运营主动打开的闸门。

        这里**只看值**（内部到处在用，`force_rule_only` 这类只看闸门开没开）：要对外
        上报、需要分清「运营关的」与「读不到」的调用方用 `switch_state`。
        """
        return bool(self.switches.get(code, False))

    def switch_state(self, code: str) -> SwitchState:
        """开关的取值 + 这个取值**是不是读到的**。

        开关表读不到时仍按关闭算（fail-closed 不变），但**上报语义**必须与「运营关了」
        分开：把故障报成人为关闭，等于「知识库挂了却没人告警」——而这个字段存在的全部
        意义就是让运维分得清这两件事（ADR-0050 §三）。

        `known=False` 时拿到的 `enabled=False` **只是兜底值，不是运营的选择**：调用方
        不许据此执行任何「看起来像人为决定」的动作（`force_rule_only` 尤其——见 `main.consult`）。
        """
        if not self.switches_available:
            return SwitchState(enabled=False, known=False)
        return SwitchState(enabled=bool(self.switches.get(code, False)), known=True)

    def switch_unreadable_marks(self, *codes: str) -> list[str]:
        """这些开关里**读不到**的那几个，翻成留痕标记（读到的——无论值是开还是关——不占位置）。"""
        return [switch_unreadable_marker(code) for code in codes if not self.switch_state(code).known]


#: 代码基线（读不到库时的回落值）。护栏词表与分级规则在库里没有种子时同样用它。
#: 药名表与越界表述表**从 `guardrails` 现取**（见文件末尾两个函数），不复制第二份——
#: 两份词表迟早会不一致，而那时没有任何机制会告诉你哪份在生效。
_cache: TtlCache[OpsConfig] = TtlCache(lambda: settings.ops_cache_seconds)


def load(force: bool = False) -> OpsConfig:
    """读全部运营可调项，带 TTL 缓存。任何一项读不到都回落到代码基线。"""
    return _cache.get(_load, force=force)


def _load() -> OpsConfig:
    """真正读库那一段（缓存未命中时才走到这里）。

    四条来源（提示词 / 分级规则 / 护栏词表 / 开关）**各自 try/except**：任何一条读不到都回落
    代码基线，并往 `missing` 里记一条；`available` 是「这批有没有读不到的」的汇总，
    也就是留痕出口（`ops_config_check=unavailable`）的来源。

    这一段在请求路径上，所以**不抛异常**：迁移没跑、库抖动、运营误删，都不该让咨询变成 500。
    开关那一路多带一个 `switches_available`——值与「这个值是不是读到的」是两件事，
    后者决定调用方能不能把它当运营的选择看（ADR-0050 §三）。
    """
    missing: list[str] = []
    try:
        prompt = _pick_prompt(_query_prompts(), roll=random.random())
    except Exception as exc:  # noqa: BLE001 —— 这一层在请求路径上：读不到（含意外的解析错误）都得回落基线，不能让咨询 500
        logger.warning("提示词模板读不到，用代码基线：%s", exc)
        prompt = code_baseline_prompt()
        missing.append(f"prompt:{exc}")

    rules: tuple[GradingRule, ...] = ()
    try:
        rules = tuple(rule for rule in (_to_rule(row) for row in _query_grading_rules()) if rule)
    except Exception as exc:  # noqa: BLE001 —— 同上：分级规则是「抬档」的兜底，读不到就不抬，不影响回答
        logger.warning("分级规则读不到，本次不生效：%s", exc)
        missing.append(f"grading_rules:{exc}")

    # 护栏词表：库里有就用库里的，没有或读不到就用代码基线（`guardrails` 里那份）
    terms: dict[str, tuple[str, ...]] = {
        "drug": guardrails_drug_terms(),
        "phrase": guardrails_banned_phrases(),
    }
    try:
        from_db = _query_guard_terms()
        if from_db:
            terms.update(from_db)
    except Exception as exc:  # noqa: BLE001 —— 同上；护栏词表读不到时回落基线，绝不放任它变空
        logger.warning("护栏词表读不到，用代码基线：%s", exc)
        missing.append(f"guard_terms:{exc}")

    switches: dict[str, bool] = {}
    switches_available = False
    try:
        switches = _query_switches()
        switches_available = True
    except Exception as exc:  # noqa: BLE001 —— 同上；开关读不到按「关闭」算（安全方向：开关是运营主动拉的闸）
        # 但**读到没有要带出去**（`switches_available`）：光留一条日志的话，调用方没法把
        # 「运营关了」与「读不到」分开上报（ADR-0050 §三）
        logger.warning("运行时开关读不到，全部按关闭算：%s", exc)
        missing.append(f"switches:{exc}")

    config = OpsConfig(
        prompt=prompt,
        grading_rules=rules,
        guard_terms={k: tuple(v) for k, v in terms.items() if v},
        switches=switches,
        switches_available=switches_available,
        available=not missing,
        detail="; ".join(missing),
    )
    return config


def code_baseline_prompt() -> PromptTemplate:
    """代码基线提示词（`prompts.py` 里那份）。读不到库、或库里没有启用版本时用它。"""
    return PromptTemplate(
        version=settings.prompt_version,
        system_prompt=prompts.SYSTEM_PROMPT,
        tool_schema=prompts.REPORT_TOOL,
        origin="code",
    )


def _pick_prompt(rows: list[dict], roll: float) -> PromptTemplate:
    """按灰度比例从候选版本里挑一个。

    `roll` 是 [0,1) 的随机数，**作为参数传进来**：随机分流如果藏在函数里就没法测
    （测试要能说「roll=0.3 落到 v1、roll=0.9 落到 v2」）。

    比例之和不一定是 100：剩余的概率落到**代码基线**（这是「没有容器就回落」的自然延伸，
    也避免运营把两个版本都设成 30% 时出现「谁都没想到会走到的那条路」）。
    """
    if not rows:
        return code_baseline_prompt()
    cumulative = 0.0
    for row in rows:
        cumulative += max(0, min(100, int(row.get("gray_ratio") or 0))) / 100
        if roll < cumulative:
            return _to_prompt(row)
    return code_baseline_prompt()


def _to_prompt(row: dict) -> PromptTemplate:
    schema = row.get("tool_schema")
    if isinstance(schema, (bytes, bytearray)):
        schema = schema.decode("utf-8")
    if isinstance(schema, str):
        try:
            schema = json.loads(schema)
        except json.JSONDecodeError:
            schema = None
    version = str(row.get("version") or "").strip()
    prompt = str(row.get("system_prompt") or "").strip()
    # 缺正文或缺工具定义的行直接用不了：回落到代码基线，而不是拿半份配置去调模型
    if not prompt or not isinstance(schema, dict) or not version:
        logger.warning("提示词行不可用（缺正文或工具定义），回落到代码基线 version=%s", version)
        return code_baseline_prompt()
    return PromptTemplate(version=version, system_prompt=prompt, tool_schema=schema, origin="db")


# ---------------------------------------------------------------- 查库（测试的接缝）


def _query_prompts() -> list[dict]:
    """启用的提示词版本。**按版本号倒序**给灰度用（新的在前，比例先被它吃掉）。"""
    return query(
        "SELECT version, system_prompt, tool_schema, gray_ratio FROM knowledge_prompt_template "
        "WHERE code = %s AND enabled = 1 AND is_deleted = 0 "
        "AND review_status NOT IN ('rejected', 'deprecated') AND gray_ratio > 0 "
        "ORDER BY version DESC",
        ("triage",),
    )


def _query_grading_rules() -> list[dict]:
    """启用的分级规则（原始行；解析见 `_to_rule`，接缝返回行便于测试喂数据）。"""
    return query(
        "SELECT code, name, match_terms, min_level, species_scope, age_stage_scope, advice "
        "FROM knowledge_grading_rule WHERE enabled = 1 AND is_deleted = 0"
    )


def _to_rule(row: dict) -> GradingRule | None:
    """行 → 规则。**没有命中词的规则直接丢掉**：它永远不会命中，留着只会让运营以为它生效了。"""
    terms = _parse_terms(row.get("match_terms"))
    if not terms:
        return None
    return GradingRule(
        code=str(row.get("code") or ""),
        name=str(row.get("name") or ""),
        terms=terms,
        min_level=int(row.get("min_level") or 2),
        species_scope=str(row.get("species_scope") or "all"),
        age_stage_scope=str(row.get("age_stage_scope") or "all"),
        advice=str(row.get("advice") or ""),
    )


def _query_guard_terms() -> dict[str, tuple[str, ...]]:
    """护栏词表：按 kind 分组。返回空 dict 表示「库里没有可用词条」→ 用代码基线。"""
    rows = query(
        "SELECT kind, term FROM knowledge_guard_term WHERE enabled = 1 AND is_deleted = 0"
    )
    grouped: dict[str, list[str]] = {}
    for row in rows:
        kind = str(row.get("kind") or "").strip()
        term = str(row.get("term") or "").strip()
        if kind and term:
            grouped.setdefault(kind, []).append(term)
    return {kind: tuple(terms) for kind, terms in grouped.items()}


def _query_switches() -> dict[str, bool]:
    """运行时开关。**「读到了但表里没有这一行」与「读不到」是两件事**：前者是运营没配
    （按关闭算，是个事实），后者是故障（`load` 据此把 `switches_available` 记成 False）。"""
    rows = query("SELECT code, enabled FROM knowledge_switch WHERE is_deleted = 0")
    return {str(row.get("code")): bool(row.get("enabled")) for row in rows if row.get("code")}


def _parse_terms(raw: object) -> tuple[str, ...]:
    """`match_terms` 的 JSON 列 → 命中词元组，空白项丢掉。

    三种写法都认（bytes / JSON 字符串 / 已经是数组），与 `knowledge._as_list` 是同一个读法，
    但**别合并**：那边读的是节点别名，这一列是分级规则的命中词，两者的归属模块不同。
    解析失败返回空元组，而空元组的规则会被 `_to_rule` 直接丢掉——所以「这一列写坏了」的表现
    是这条规则不生效，不报错。
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


@dataclass(frozen=True)
class Escalation:
    """分级规则命中的结果：一个**风险下限** + 命中的规则编号 + 要追加的一句建议。"""

    level: int = 1
    codes: tuple[str, ...] = ()
    advice: str = ""


def escalate(text: str, rules: tuple[GradingRule, ...], species: int | None, age_stage: str) -> Escalation:
    """跑一遍分级规则，得出风险下限（切片 #103）。

    **纯函数**（词表作为参数传进来）：规则是业务可调项，但「命中怎么算」是代码常量——
    把匹配写进 SQL 或塞进运营后台的配置里，改错的代价无法用测试拦住（ADR-0010 的第三层）。

    与红线的关系：红线是**短路**（命中即判红、不调模型），这里是**兜底**（模型照常调，
    只是结论不低于这一档）。两者动作不同，所以是两张表（ADR-0026 拒绝合并两类规则的理由）。
    未命中任何规则时下限是 1（绿），即「不抬档」。
    """
    if not text or not rules:
        return Escalation()
    lowered = text.lower()
    level = 1
    codes: list[str] = []
    advice: list[str] = []
    for rule in rules:
        if not rule.applies_to(species, age_stage):
            continue
        if not any(term.lower() in lowered for term in rule.terms):
            continue
        codes.append(rule.code)
        level = max(level, rule.min_level)
        if rule.advice and rule.advice not in advice:
            advice.append(rule.advice)
    # 建议用空串拼接（中文句子自带标点）：加空格会在用户看到的文案里留出「。」与下一句之间的缝
    return Escalation(level=level, codes=tuple(codes), advice="".join(advice))


def guardrails_drug_terms() -> tuple[str, ...]:
    """代码基线里的药名表。**从 `guardrails` 现取**而不是复制一份：两份词表迟早会不一致。"""
    from . import guardrails

    return tuple(guardrails.CODE_DRUG_TERMS)


def guardrails_banned_phrases() -> tuple[str, ...]:
    """代码基线里的越界表述表。"""
    from . import guardrails

    return tuple(guardrails.CODE_BANNED_PHRASES)
