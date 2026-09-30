"""对知识域的**唯一**数据库出口（ADR-0009 的边界落点）。

AI 服务直连 MySQL，但**只读 `knowledge_*` 表**；其余一切数据的读写都走 Java 接口。
所以这个模块刻意只提供「查询」两个函数，没有 execute / insert / update——
不是漏了写，而是「加写操作要先改 ADR」。

为什么要有这个文件：红线词表、运营可调项、三层检索三处都要读库，各写一份连接参数与异常处理
意味着「连接超时」「读不到算不算故障」这类判断会出现三种版本。收在一处，边界与错误口径只有一份。

失败一律抛 `DbUnavailable`，由调用方决定降级（**不在这里吞掉**）：读不到词表的后果是
「红线这层没生效」，读不到知识条目的后果是「这次回答没有来源」，两者都不是同一个降级分支。
"""

from __future__ import annotations

from typing import Any

from .config import settings


class DbUnavailable(Exception):
    """读不到知识域（连不上、表不存在、SQL 出错）。调用方据此降级并留痕。"""


#: 单次连接的建立超时。**刻意很短**：这类查询都在咨询的请求路径上，
#: 连不上就该尽快走降级，而不是让用户等一个 20 秒超时（ADR-0017 的延迟预算）。
CONNECT_TIMEOUT_SECONDS = 3


def query(sql: str, params: tuple[Any, ...] | list[Any] | None = None) -> list[dict]:
    """跑一条只读查询，返回字典行。

    参数用占位符传（`%s`），**不要拼字符串**——检索词来自用户输入，拼接等于把注入面敞开。
    """
    import pymysql  # 延迟导入：没有 MySQL 的部署（只跑单测）也能 import 这个模块

    try:
        connection = pymysql.connect(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            database=settings.mysql_database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=CONNECT_TIMEOUT_SECONDS,
            read_timeout=settings.mysql_read_timeout_seconds,
        )
    except Exception as exc:
        raise DbUnavailable(f"连接知识库失败：{type(exc).__name__}") from exc

    try:
        with connection.cursor() as cursor:
            cursor.execute(sql, params or ())
            return list(cursor.fetchall())
    except Exception as exc:
        raise DbUnavailable(f"读取知识库失败：{type(exc).__name__}") from exc
    finally:
        connection.close()


def query_one(sql: str, params: tuple[Any, ...] | list[Any] | None = None) -> dict | None:
    """只取第一行（「读一条配置」这类场景）。"""
    rows = query(sql, params)
    return rows[0] if rows else None
