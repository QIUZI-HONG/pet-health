"""硬红线预检（切片 #98，决策见 ADR-0021）。

判据是 `knowledge_red_flag` 表（L1 结构化事实层），匹配在这里做、**不经模型**：
命中即判红，模型连调都不调。理由写在 ADR-0021：把「红色 100% 召回」这条验收标准
押在模型偶发漏判上，等于没有保障。

三条实现上的取舍：

- **匹配是纯字符串包含**（主词 + variants），不做向量近似。红色宁可多召回也不能漏，
  而关键词命中可解释、可复核、可追责；表达差异靠词表里多写变体解决（ADR-0021 的 Considered Options）。
- **词表带缓存**（TTL 可配）。运营在后台改完红线，最多滞后一个 TTL 生效——这换来的是
  每次咨询都不查库。要立刻生效就把 TTL 调小。
- **读不到词表时不静默放行**：记录并让调用方知道「这一层没生效」。红线是安全网，
  悄悄少一层比少一层本身更危险。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass

from .config import settings

logger = logging.getLogger("pet_health_ai")


@dataclass(frozen=True)
class RedFlag:
    """一条红线规则。"""

    code: str
    pattern: str
    variants: tuple[str, ...]
    species_scope: str
    age_stage_scope: str
    level: int
    action_hint: str

    def terms(self) -> tuple[str, ...]:
        """参与匹配的所有词：主词在前，变体在后。"""
        return (self.pattern, *self.variants)


@dataclass(frozen=True)
class RedFlagHit:
    """一次命中。`term` 是真正命中的那个词，用于留痕与向用户解释。"""

    code: str
    term: str
    rule: RedFlag


@dataclass
class LoadResult:
    """词表加载结果。`available=False` 表示这一层没生效，调用方要把它记下来。"""

    rules: tuple[RedFlag, ...]
    available: bool
    detail: str = ""


_cache: tuple[float, LoadResult] | None = None


def load_rules(force: bool = False) -> LoadResult:
    """读启用的红线规则，带 TTL 缓存。"""
    global _cache
    now = time.monotonic()
    if not force and _cache is not None and now - _cache[0] < settings.red_flag_cache_seconds:
        return _cache[1]

    try:
        rules = tuple(_query())
        # **查得到但一条没有**，与「查不到」同样是这一层没生效：表被清空、迁移没跑、
        # 运营把 enabled 全关了，都会走到这里。早先这里写死 available=True，
        # 于是红线层实际不存在、对外却报「已检查」——安全网静默失效（测试报告 D5）。
        result = LoadResult(
            rules=rules,
            available=bool(rules),
            detail="" if rules else "词表为空（enabled 的规则数为 0）",
        )
    except Exception as exc:  # noqa: BLE001 —— DB 故障的种类不值得在这里穷举，一律视为「这一层不可用」
        logger.warning("红线词表加载失败，本次不生效：%s", exc)
        result = LoadResult(rules=(), available=False, detail=str(exc))

    _cache = (now, result)
    return result


def _query() -> list[RedFlag]:
    """查库。**只读 `knowledge_*`**（ADR-0009 给的唯一例外），不碰任何业务表。"""
    import pymysql  # 延迟导入：没有 MySQL 的部署（只跑单测）也能 import 这个模块

    connection = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=3,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT code, pattern, variants, species_scope, age_stage_scope, level, action_hint "
                "FROM knowledge_red_flag WHERE enabled = 1 AND is_deleted = 0"
            )
            rows = cursor.fetchall()
    finally:
        connection.close()

    rules = []
    for row in rows:
        rules.append(
            RedFlag(
                code=row["code"],
                pattern=row["pattern"],
                variants=tuple(_parse_variants(row["variants"])),
                species_scope=row["species_scope"] or "all",
                age_stage_scope=row["age_stage_scope"] or "all",
                level=int(row["level"]),
                action_hint=row["action_hint"] or "",
            )
        )
    return rules


def _parse_variants(raw: object) -> list[str]:
    if raw is None:
        return []
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return []
    if not isinstance(raw, list):
        return []
    return [str(item) for item in raw if str(item).strip()]


def match(
    text: str,
    rules: tuple[RedFlag, ...],
    species: int | None = None,
    age_stage: str = "all",
) -> list[RedFlagHit]:
    """纯函数：在 `text` 里找命中。抽出来是为了能脱离数据库测（测试见 tests/test_red_flags.py）。"""
    if not text:
        return []
    lowered = text.lower()
    hits: list[RedFlagHit] = []
    for rule in rules:
        if not _applies_to(rule, species, age_stage):
            continue
        for term in rule.terms():
            if term and term.lower() in lowered:
                hits.append(RedFlagHit(code=rule.code, term=term, rule=rule))
                break  # 一条规则只留一次命中：留痕里要的是「哪条规则触发」，不是「命中几个词」
    return hits


def _applies_to(rule: RedFlag, species: int | None, age_stage: str) -> bool:
    if rule.species_scope != "all" and species is not None:
        # 1 犬 / 2 猫，与 contract/app.yaml 的 species 一致
        want = "dog" if species == 1 else "cat" if species == 2 else None
        if want is not None and rule.species_scope != want:
            return False
    # 年龄维度只有在规则限定了年龄段时才参与过滤
    return rule.age_stage_scope == "all" or rule.age_stage_scope == age_stage


def age_stage_of(birth_date: str | None, species: int | None) -> str:
    """按生日推年龄段。

    **阈值是占位的**（犬 <1 岁 / 1–7 岁 / >7 岁，猫 <1 岁 / 1–10 岁 / >10 岁），
    与 63 号调研 §2.3 的说明一致：幼年/成年/老年的分界要兽医定稿，工程不拍板。
    定稿前先用它做红线过滤——比完全不过滤安全（幼宠红线不会误判到成年宠身上）。
    """
    if not birth_date:
        return "all"
    from datetime import date, datetime
    from zoneinfo import ZoneInfo

    try:
        year, month, day = (int(part) for part in birth_date.split("-")[:3])
        born = date(year, month, day)
    except (ValueError, TypeError):
        return "all"

    # 用带时区的「今天」：年龄算错一岁会让年龄段过滤放错人（docs/conventions.md 的时区约定）
    today = datetime.now(ZoneInfo(settings.timezone)).date()
    years = (today - born).days / 365.25
    if years < 1:
        return "puppy_kitten"
    senior_after = 7 if species != 2 else 10
    return "senior" if years >= senior_after else "adult"
