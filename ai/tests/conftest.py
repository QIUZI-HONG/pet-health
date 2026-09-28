"""测试夹具。

**为什么要有这个文件**：常规测试用 `settings.internal_token` 做鉴权头，而 `.env` 不入库、
CI 里也不生成——于是本地跑得通、CI 上 10 条用例全红（令牌为空即 fail-closed，
测试报告 D8）。测试**不该依赖开发者本机的密钥**：显式盖一个测试令牌，走到哪跑结果都一样。
"""

import pytest

from app import red_flags
from app.config import settings

#: 测试用的内部令牌。与真实部署无关，只是让「鉴权本身」在测试里有个确定的值。
TEST_INTERNAL_TOKEN = "test-internal-token"


@pytest.fixture(autouse=True)
def deterministic_settings(monkeypatch):
    """把会随环境变化的两件事钉死：内部令牌、红线词表缓存。

    缓存也要清：红线词表带 TTL 模块级缓存，前一个用例灌进去的「空词表/异常」结果
    会漏到下一个用例，表现为随机红。
    """
    monkeypatch.setattr(settings, "internal_token", TEST_INTERNAL_TOKEN)
    monkeypatch.setattr(red_flags, "_cache", None)
    yield
    monkeypatch.setattr(red_flags, "_cache", None)
