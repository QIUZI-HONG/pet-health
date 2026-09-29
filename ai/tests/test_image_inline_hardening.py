"""图片内联的安全与健壮面（2026-09-28 深测轮）。

`inline_images` 是 AI 服务里**唯一会替调用方发出站请求**的地方：给它一个 URL，它就去 GET。
所以它的把关点值得单独一组用例——目标主机、响应状态、声明类型、体积。

现状（已确认）：只按 **content-type 与体积**把关，不校验目标主机、不嗅探字节的魔数。
调用方需要内部令牌（fail closed），所以这不是对着公网开的口子；但「谁决定这个 URL」
一旦变化（比如图片地址改由用户填写），它立刻变成 SSRF / 任意文件读取的形状。
两个缺口用 `xfail` 标出来：**它们是要关掉的洞，不是被接受的设计**——
谁哪天补上了，对应用例会 XPASS 提醒把标记摘掉。
"""

import asyncio

import pytest

from app import model_client
from app.config import settings


class _FakeResponse:
    def __init__(self, content: bytes, content_type: str, status_code: int = 200) -> None:
        self.content = content
        self.status_code = status_code
        self.headers = {"content-type": content_type}


def _stub_httpx(monkeypatch, response: _FakeResponse) -> list[str]:
    """把 httpx.AsyncClient 换成桩，返回「被请求过的 URL」列表，便于断言它到底去够哪里。"""
    requested: list[str] = []

    class FakeClient:
        def __init__(self, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url: str) -> _FakeResponse:
            requested.append(url)
            return response

    monkeypatch.setattr(model_client.httpx, "AsyncClient", FakeClient)
    return requested


def _inline(url: str) -> list[str]:
    return asyncio.run(model_client.inline_images([url]))


# ---------------------------------------------------------------- 已知缺口（xfail）


@pytest.mark.xfail(
    reason="安全加固项（#121）：内联不校验目标主机，只按 content-type 把关",
    strict=False,
)
def test_non_allowlisted_host_is_rejected(monkeypatch):
    """期望行为：只允许取我们自己后端的地址。

    现状是**任意外部主机都会去 GET**——只要它回 `image/*` 就被当图片内联。
    云元数据地址（169.254.169.254）是这类 SSRF 的经典靶子：能读图就能读别的，
    只是多一个 content-type 的门。
    """
    _stub_httpx(monkeypatch, _FakeResponse(b"\x89PNG\r\n\x1a\n", "image/png"))

    with pytest.raises(model_client.ImageUnavailable):
        _inline("http://169.254.169.254/latest/meta-data/")


@pytest.mark.xfail(
    reason="与 ph-file 的魔数把关不一致：AI 服务信 content-type，不嗅探字节",
    strict=False,
)
def test_declared_image_type_is_sniffed(monkeypatch):
    """期望行为：声明的类型要跟字节对得上（ph-file 有 `ImageSniffer`，这里没有）。

    一个回 `content-type: image/png` 但内容不是图片的地址，现在会被原样内联成 data URL
    交给模型——模型拿到的是一堆垃圾字节，回答却「看起来完全正常」。
    这与 D7 被修掉的那个坑是同一类：**看起来正常的错误答案比报错危险**。
    """
    _stub_httpx(monkeypatch, _FakeResponse(b"<html>not an image</html>", "image/png"))

    with pytest.raises(model_client.ImageUnavailable):
        _inline("http://127.0.0.1:8080/api/v1/open/files/1?token=t")


# ---------------------------------------------------------------- 现状：确实把关的几处


def test_redirect_response_is_treated_as_failure(monkeypatch):
    """302 当失败处理，不跟着跳。

    `httpx` 默认不跟随重定向，而这里连「非 200 一律失败」都钉住了——
    跟着跳会让目标主机变成「由被请求方决定」，把上面那条主机校验绕过去。
    """
    _stub_httpx(monkeypatch, _FakeResponse(b"", "text/html", status_code=302))

    with pytest.raises(model_client.ImageUnavailable) as exc:
        _inline("http://127.0.0.1:8080/moved")

    assert "302" in str(exc.value)


def test_oversized_response_is_rejected_by_actual_bytes(monkeypatch):
    """体积**以实际字节数为准**，不看声明——与 ph-file「声明不超、实际超了照样拒」同一条纪律。"""
    oversized = b"\x89PNG\r\n\x1a\n" + b"0" * settings.ai_max_image_bytes
    _stub_httpx(monkeypatch, _FakeResponse(oversized, "image/png"))

    with pytest.raises(model_client.ImageUnavailable) as exc:
        _inline("http://127.0.0.1:8080/api/v1/open/files/2?token=t")

    assert "上限" in str(exc.value)


def test_data_url_with_invalid_base64_is_rejected():
    """base64 解不开的 data URL：失败，而不是把未解码的字符串当图片送出去。"""
    with pytest.raises(model_client.ImageUnavailable):
        _inline("data:image/png;base64,!!!!not-base64!!!!")
