"""AI 服务的全部可配项。

分层见 [ADR-0010](../../docs/adr/0010-ai-config-layering.md)：这里**只放技术参数**
（谁能改 = 部署者，生效方式 = 重启）。

业务可调项——提示词模板、硬红线词词典、风险分级规则、降级开关、灰度比例——
在数据库里，由运营后台改、即时生效，**不进这里**。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- 模型 ----
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ai_model_chat: str = "qwen3-vl-plus"
    ai_model_fast: str = "qwen3-vl-flash"
    ai_model_asr: str = "paraformer-v2"
    ai_model_embed: str = "qwen3.7-text-embedding"
    ai_max_repair_retry: int = 1

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
