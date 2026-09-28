package com.pethealth.ai.client;

import com.pethealth.ai.config.AiServiceProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * AI 服务的 HTTP 客户端。**这是 Java 与模型之间唯一的通道**（ADR-0009）：
 * 模型调用与检索都在 Python 那边，这里只负责把请求发过去、把结构化结果带回来。
 *
 * <p>传输层失败一律转成 {@link AiUnavailable}，由调用方决定怎么降级——
 * 这里不抛 Spring 的异常类型，业务层不该认识 {@code RestClientException}。
 */
@Component
public class AiServiceClient {

    private final RestClient restClient;

    /**
     * @param builder **必须用容器注入的这个**，不能用 {@code RestClient.builder()} 静态工厂：
     *     静态工厂拿不到应用配置的 Jackson（全局 SNAKE_CASE 就在这里），
     *     结果 Python 返回的 {@code risk_level} 映射不到 {@code riskLevel}，
     *     反序列化**不报错**、整条响应退化成全默认值（分级变成 0）。
     *     这个坑是在集成测试里抓到的（切片 #98）——用 mock 掉客户端的单测永远抓不到它。
     */
    public AiServiceClient(RestClient.Builder builder, AiServiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(properties.timeoutMs(), 5_000));
        // 读超时用配置值：ADR-0017 记过模型偶发 20 秒，配短了等于常态化降级
        factory.setReadTimeout((int) properties.timeoutMs());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Token", properties.token())
                .requestFactory(factory)
                .build();
    }

    /** 一次咨询。超时、连不上、协议错误都归为 {@link AiUnavailable}。 */
    public ConsultResponse consult(ConsultRequest request) {
        try {
            ConsultResponse response = restClient.post()
                    .uri("/internal/consult")
                    .body(request)
                    .retrieve()
                    .body(ConsultResponse.class);
            if (response == null) {
                throw new AiUnavailable("AI 服务返回了空响应");
            }
            // 分级只能是 1/2/3。拿到别的值说明响应结构没对上（见构造函数的说明），
            // **绝不能把它当成「绿」**：宁可降级，也不能让一个无效分级走到用户面前。
            if (response.riskLevel() < 1 || response.riskLevel() > 3) {
                throw new AiUnavailable("AI 服务返回的分级不在 1–3 之间：" + response.riskLevel());
            }
            return response;
        } catch (RestClientException e) {
            throw new AiUnavailable("调用 AI 服务失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** AI 服务不可用（超时 / 连不上 / 响应不可解析）。 */
    public static class AiUnavailable extends RuntimeException {
        public AiUnavailable(String message) {
            super(message);
        }

        public AiUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---- 与 Python 的 `app/models.py` 一一对应。**改这里等于改跨服务契约，两侧必须同步改。** ----

    /** 请求：宠物上下文 + 输入 + 历史。字段名经全局 SNAKE_CASE 序列化，与 Python 侧一致。 */
    public record ConsultRequest(String traceId, long userId, PetContext pet, ConsultInput input,
                                List<Object> history) {
    }

    public record PetContext(long id, int species, String breed, String birthDate, Double weight,
                             List<String> chronic) {
    }

    /** 输入：{@code text} 必填——纯图片分诊不可用（61 号调研）。 */
    public record ConsultInput(String type, String text, List<String> mediaUrls) {
    }

    public record ConsultResponse(
            int riskLevel,
            List<String> possibleCauses,
            String actionSuggestion,
            boolean needHospital,
            List<String> careTips,
            List<String> citations,
            int imagesUsed,
            List<String> redFlagHits,
            List<String> guardHits,
            String redFlagCheck,
            boolean degraded,
            /** 机器可读的降级原因码；给用户的中文由 {@code AiConsultService} 映射。 */
            String degradeCode,
            /** 内部明细（异常类名 / 上游原文 / 模型原文）：只进留痕，不下发给用户。 */
            String degradeDetail,
            String modelName,
            String modelVersion,
            String promptVersion,
            int latencyMs,
            /** 本轮实际消耗的 token（红线短路与降级是 0）；日预算告警按它估算花费（ADR-0026）。 */
            int promptTokens,
            int completionTokens) {
    }
}
