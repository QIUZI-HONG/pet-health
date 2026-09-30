"""带 TTL 的进程内缓存。

**为什么要有这个文件**：三处读库的「配置 / 词表 / 词典」各自写过一份同样的样板——
模块级 `_cache: tuple[float, T] | None`、`time.monotonic()` 比一次、写回、`force` 绕过。
重复的代价不在行数，而在**语义会漂移**：`force` 到底绕不绕过、TTL 从哪儿读、
用 `monotonic` 还是 `time.time`（后者会被系统校时拨动，缓存要么提前失效要么不失效），
三份各写一次就会各有一个答案，而这几处恰好都是「读不到就降级」的安全路径。

缓存的**内容是只读快照**（提示词、分级规则、护栏词表、开关、节点词典、安全边），
所以过期语义只有一条：超过 TTL 就重读。
"""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Generic, TypeVar

T = TypeVar("T")


class TtlCache(Generic[T]):
    """读一次、TTL 内复用。

    **不加锁**：多线程下最坏是并发读两次库（值本身是幂等的只读快照），
    为它上一把锁反而把「读库慢」变成「所有请求排队等锁」。

    TTL 传**可调用对象**而不是一个数：配置对象在进程启动时构造，
    而测试要能改写 TTL（`monkeypatch.setattr(settings, "ops_cache_seconds", 0)`）；
    传值就把它冻在 import 那一刻了。
    """

    def __init__(self, ttl_seconds: Callable[[], float]) -> None:
        self._ttl_seconds = ttl_seconds
        self._value: tuple[float, T] | None = None

    def get(self, load: Callable[[], T], *, force: bool = False) -> T:
        """取缓存值；过期或 `force` 时调用 `load()` 重读。

        `load()` 抛异常时**不写缓存**：这一层读库失败是「降级依据」的一部分，
        把失败瞬间的空值缓存住会让下一次调用连重试的机会都没有。
        """
        now = time.monotonic()
        if not force and self._value is not None and now - self._value[0] < self._ttl_seconds():
            return self._value[1]
        value = load()
        self._value = (now, value)
        return value

    def clear(self) -> None:
        """丢掉缓存。

        测试每个用例前都要调（`tests/conftest.py` 的 autouse 夹具）：上一个用例灌进去的
        词表/提示词漏到下一个用例的表现是**随机红**，比直接失败难查得多。
        """
        self._value = None
