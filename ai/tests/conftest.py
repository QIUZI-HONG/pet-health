"""测试夹具。

**为什么要有这个文件**：常规测试用 `settings.internal_token` 做鉴权头，而 `.env` 不入库、
CI 里也不生成——于是本地跑得通、CI 上 10 条用例全红（令牌为空即 fail-closed，
测试报告 D8）。测试**不该依赖开发者本机的密钥**：显式盖一个测试令牌，走到哪跑结果都一样。

切片 #100/#103 之后这里多钉两件事，同一条理由（本地开发库和 CI 的差别不该改变测试结果）：

- **运营可调项与知识检索都退回「读不到库」的基线**：开发者本机通常跑着 MySQL（迁移一跑，
  提示词就从库里的 p1 版本生效、检索也会拿到种子条目），CI 上没有库。不钉住的话，
  「提示词版本号」这类断言会本地红、CI 绿（或反过来）。
  需要真实数据行为的用例自己 monkeypatch 那几个查库接缝（`ops._query_*` / `knowledge._query_*`）。
- **两处 TTL 缓存都要清**：前一个用例灌进去的词表/提示词会漏到下一个用例，表现为随机红。
"""

import pytest

from app import knowledge, ops, red_flags
from app.config import settings
from app.db import DbUnavailable

#: 测试用的内部令牌。与真实部署无关，只是让「鉴权本身」在测试里有个确定的值。
TEST_INTERNAL_TOKEN = "test-internal-token"

#: 查库接缝的**真实现**（在本文件 import 时抓下来，那时还没被替换）。
#: 需要真库的用例（`test_knowledge_db.py`）用它们把接缝换回去——下面那个 autouse 夹具
#: 会把所有接缝打到「读不到库」，而那正是那些用例要绕开的。
REAL_SEAMS: dict[tuple[str, str], object] = {
    (module_name, name): getattr(module, name)
    for module_name, module in (("red_flags", red_flags), ("ops", ops), ("knowledge", knowledge))
    for name in ("_query", "_query_prompts", "_query_grading_rules", "_query_guard_terms",
                 "_query_switches", "_query_entries", "_query_facts", "_query_relations",
                 "_query_entries_by_codes", "_static")
    if hasattr(module, name)
}


def restore_real_seams(monkeypatch, *module_names: str) -> None:
    """把指定模块的查库接缝换回真实现（给连库用例用）。"""
    modules = {"red_flags": red_flags, "ops": ops, "knowledge": knowledge}
    for (module_name, name), function in REAL_SEAMS.items():
        if module_name in module_names:
            monkeypatch.setattr(modules[module_name], name, function, raising=False)


def no_db(*_args, **_kwargs):
    """模拟「知识域读不到」：运营可调项与检索都走各自的降级路径。"""
    raise DbUnavailable("测试环境没有知识库（见 conftest 的说明）")


@pytest.fixture(autouse=True)
def deterministic_settings(monkeypatch):
    """把会随环境变化的东西全钉死：内部令牌、红线词表缓存、运营可调项、知识检索。"""
    monkeypatch.setattr(settings, "internal_token", TEST_INTERNAL_TOKEN)

    clear_ttl_caches()

    # 运营可调项：读不到库 → 提示词/护栏词表走代码基线、分级规则为空、开关全关。
    # 要测库里的那套就在用例里覆盖这几个接缝（例：tests/test_ops.py）。
    for name in ("_query_prompts", "_query_grading_rules", "_query_guard_terms", "_query_switches"):
        monkeypatch.setattr(ops, name, no_db, raising=False)

    # 知识检索：三层查询都视为读不到 → retrieval_check=unavailable、citations 为空。
    # 这与「检索层未实现」时的对外行为一致，所以老用例（断言 citations 为空）继续成立。
    for name in ("_query_entries", "_query_facts", "_query_relations", "_query_entries_by_codes",
                 "_static"):
        monkeypatch.setattr(knowledge, name, no_db, raising=False)

    yield

    clear_ttl_caches()


def clear_ttl_caches() -> None:
    """清掉三处 TTL 缓存（`app/ttl_cache.py`）。

    **用例前后都要清**：前一个用例灌进去的词表/提示词漏到下一个用例，表现是随机红——
    比直接失败难查得多。收成一个函数是因为有两个调用点（before / after），
    而「要清哪几个缓存」这件事不该写两遍：加了第四个缓存时漏改一处的表现正是上面那种随机红。
    """
    red_flags._cache.clear()
    ops._cache.clear()
    knowledge._static_cache.clear()
