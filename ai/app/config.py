"""AI 服务的全部可配项。

分层见 [ADR-0010](../../docs/adr/0010-ai-config-layering.md)：这里**只放技术参数**
（谁能改 = 部署者，生效方式 = 重启）。

业务可调项——提示词模板、硬红线词词典、风险分级规则、降级开关、灰度比例——
在数据库里，由运营后台改、即时生效，**不进这里**（提示词目前还在代码里，见
`prompts.py` 顶部的说明，等 #103 建表后搬走）。

供应商与模型的选择见 [ADR-0017](../../docs/adr/0017-model-provider-deepseek.md)：
当前接的是 DeepSeek，**文本与图片可用**（图片只有 flash 看得见，pro 看不见），
没有语音转写、没有向量。下面几个 `ai_supports_*` 开关不是配置花样，
是把「这家有/没有这个能力」写进代码——**请求路径也读它们**，不是只给健康检查看。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- 模型供应商（OpenAI 兼容接口）----
    ai_api_key: str = ""
    ai_base_url: str = "https://api.deepseek.com"
    # 分级默认用 flash：实测 pro 延迟跨到 20 秒并超时（5.6/11.3/20.3s），
    # flash 稳定 5~7 秒（ADR-0017 有数据）。**而且只有 flash 看得见图片**（pro 看图和
    # 不看图都答「无法确定」），所以图片也走它。分级质量该由 #62 的评测集用漏判率判断
    # （漏判率比准确率重要：红判绿比绿判红代价高得多）。
    #
    # ai_model_fast 是两级分工里给「常规问答」留的位置，目前分级与问答共用 flash，
    # 等 #98 定了意图路由再用起来。
    ai_model_grading: str = "deepseek-flash"
    ai_model_fast: str = "deepseek-flash"
    # 支持图片输入的模型。留空 = 当前供应商不支持图片，带图请求会被明确降级。
    ai_vision_model: str = "deepseek-flash"

    # **推理型模型会把输出预算烧在 reasoning 上**：给少了会返回空 content 且 finish_reason=length
    # （40 / 120 / 900 / 1500 token 都实测踩过）。一次带图的真实分级用了 1083 个输出 token
    # （其中 reasoning 510），所以默认留到 3000——上限只是上限，不生成的 token 不计费。
    ai_max_output_tokens: int = 3000
    # 单次最多送几张图。**与契约的 file_ids maxItems: 4 对齐**——两边不一致时，
    # 第 4 张会被静默丢掉（用户以为传了 4 张，模型只看了 3 张）。图片直接进模型上下文，
    # 4 张已经是够用的上限（测试报告 D27）。
    ai_max_images: int = 4
    ai_timeout_seconds: float = 20.0
    ai_max_repair_retry: int = 1
    # 图片要从 Java 侧取回来再内联给模型（供应商拉不到我们的内网地址）：单张下载超时与体积上限。
    # 上限与 ph-file 的 10MB 上传上限不是一个概念——这里限制的是「进模型上下文的那份」。
    ai_image_timeout_seconds: float = 10.0
    ai_max_image_bytes: int = 5_000_000

    # ---- 能力开关（实测记录见 ADR-0017）----
    # 图片：**flash 可以**（红/蓝方块颜色都能正确识别，不给图则答「无法确定」；pro 不行），
    #       所以这里为 true，由 ai_vision_model 承载。换供应商或换模型时先重测再改。
    # 语音 / 向量：两个端点都是 404，确实没有。
    ai_supports_image: bool = True
    ai_supports_audio: bool = False
    ai_supports_embedding: bool = False

    # ---- 留痕 ----
    # 提示词版本号随调用结果落库，用于事后归因分级漂移（提示词改了还是模型改了）。
    # **唯一来源就是这里**：提示词入 DB（#103）之前，改提示词时必须手工把这里 +1。
    prompt_version: str = "p0-code"

    # 红线词表的缓存秒数（ADR-0021）：运营在后台改完，最多滞后这么久生效。
    # 调小 = 更及时但每次咨询都查库；0 表示每次都查。
    red_flag_cache_seconds: float = 60.0

    # 时区：与 Java 侧 AppTime 和数据库保持一致（docs/conventions.md）
    timezone: str = "Asia/Shanghai"

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
    # **没有默认值**：与仓库里不放 JWT/AES 默认密钥同一个道理——漏配时不能静默放行。
    # 未配置时所有 /internal/* 调用都会被拒（fail closed），启动日志里也会有一条警告。
    internal_token: str = ""


settings = Settings()
