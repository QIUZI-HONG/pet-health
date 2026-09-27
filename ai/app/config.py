"""AI 服务的全部可配项。

分层见 [ADR-0010](../../docs/adr/0010-ai-config-layering.md)：这里**只放技术参数**
（谁能改 = 部署者，生效方式 = 重启）。

业务可调项——提示词模板、硬红线词词典、风险分级规则、降级开关、灰度比例——
在数据库里，由运营后台改、即时生效，**不进这里**（提示词目前还在代码里，见
`prompts.py` 顶部的说明，等 #103 建表后搬走）。

供应商与模型的选择见 [ADR-0017](../../docs/adr/0017-model-provider-deepseek.md)：
当前接的是 DeepSeek，**只有文本能力**。下面几个 `ai_supports_*` 开关不是配置花样，
是把「这家没有这个能力」写进代码——否则总有人以为 `ai_model_asr` 只是没填。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- 模型供应商（OpenAI 兼容接口）----
    ai_api_key: str = ""
    ai_base_url: str = "https://api.deepseek.com"
    # 分级默认用 flash：实测 pro 延迟跨到 20 秒并超时（5.6/11.3/20.3s），
    # flash 稳定 5 秒左右（ADR-0017 有数据）。分级质量该由 #62 的评测集判断，
    # 评测集落地后若 pro 的漏判率显著更低，再换并同步放大超时预算。
    ai_model_grading: str = "deepseek-flash"
    ai_model_fast: str = "deepseek-flash"

    # **推理型模型会把输出预算烧在 reasoning 上**：给少了会返回空 content 且 finish_reason=length
    # （40 / 120 / 900 token 都实测踩过）。所以默认给足，别按普通模型的习惯写 256。
    ai_max_output_tokens: int = 1500
    ai_timeout_seconds: float = 20.0
    ai_max_repair_retry: int = 1

    # ---- 当前供应商不具备的能力（实测：图片看不见，embeddings / audio 都 404）----
    # 留成开关而不是删掉：换供应商时改这里 + ADR，调用方按它决定走不走降级。
    ai_supports_image: bool = False
    ai_supports_audio: bool = False
    ai_supports_embedding: bool = False

    # ---- 留痕 ----
    # 提示词版本号随调用结果落库，用于事后归因分级漂移。提示词入 DB 之前先手工维护这个值。
    prompt_version: str = "p0-code"

    # ---- 基础设施：对知识域只读（ADR-0009 的边界）----
    mysql_host: str = "127.0.0.1"
    # 3307 不是 3306：本机原生 Windows MySQL 占着 3306，见 deploy/docker-compose.dev.yml
    mysql_port: int = 3307
    mysql_database: str = "pet_health"
    mysql_user: str = "root"
    mysql_password: str = "devroot"
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379

    # ---- 内部鉴权：与后端 AI_SERVICE_TOKEN 一致 ----
    internal_token: str = "dev-internal-token"


settings = Settings()
