package com.pethealth.ai.client;

import com.pethealth.ai.config.AiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>三条链路对失败的处理是两种，不是一种</b>：{@link #consult} 抛（会话有留痕与配额，
 * 静默降级会把这些状态弄乱），{@link #matchSymptoms} / {@link #browseKnowledge} 返回
 * 「不可用」的空结果（对用户就是「暂时看不出来」）。**但两种都必须留痕**：
 * 后两条不抛，如果连一行日志都没有，AI 服务整体不可用时它们在日志与指标里是完全无声的。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

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

    /**
     * F011 的症状识别（规则版，`/internal/symptom-match`）。
     *
     * <p>与 {@link #consult} 的差别只在降级语义：这里**不抛** {@link AiUnavailable}——
     * 「这次没读出症状」与「AI 服务挂了」对用户是同一件事（都要说「暂时认不出，先看看目录」），
     * 而调用方拿到的空结果 + {@code knowledgeCheck=unavailable} 已经够它拼那句话了。
     * 抛异常会逼调用方在每个分支上重复同一段降级文案。
     */
    public SymptomMatchResponse matchSymptoms(String text) {
        try {
            SymptomMatchResponse response = restClient.post()
                    .uri("/internal/symptom-match")
                    .body(new SymptomMatchRequest(text))
                    .retrieve()
                    .body(SymptomMatchResponse.class);
            return response == null ? SymptomMatchResponse.unavailable() : response;
        } catch (RestClientException e) {
            // 不抛是对的（见方法注释），但不记就是「吞异常」：AI 挂了的表现只是
            // 「暂时认不出」，从界面看不出哪里错了，这里留一行就够定位
            log.warn("症状识别降级（AI 服务不可用）：{}", e.toString());
            return SymptomMatchResponse.unavailable();
        }
    }

    /**
     * 知识库浏览（F024 / F025，`/internal/knowledge/browse`）。
     *
     * <p>与 {@link #consult} 的分级链路是两件事：那条回答「这次咨询该引用什么」，这条回答
     * 「知识库里有什么」。降级语义与 {@link #matchSymptoms} 相同——读不到就返回空 + `unavailable`，
     * **不抛**（对用户就是「暂时看不了」，抛异常会逼调用方在每处重复同一段降级文案）。
     */
    public KnowledgeBrowseResponse browseKnowledge(String categoryCode, String keyword, String code,
                                                   long page, long pageSize) {
        try {
            KnowledgeBrowseResponse response = restClient.post()
                    .uri("/internal/knowledge/browse")
                    .body(new KnowledgeBrowseRequest(categoryCode, keyword, code, page, pageSize))
                    .retrieve()
                    .body(KnowledgeBrowseResponse.class);
            return response == null ? KnowledgeBrowseResponse.unavailable() : response;
        } catch (RestClientException e) {
            // 同 matchSymptoms：降级不抛，但必须留痕
            log.warn("知识库浏览降级（AI 服务不可用）：{}", e.toString());
            return KnowledgeBrowseResponse.unavailable();
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

    // ---- F024 / F025：知识库浏览 ----

    /** 请求：分类 / 关键词 / 编号（给了编号就是取单条）+ 分页。字段名经全局 SNAKE_CASE，与 Python 侧一致。 */
    public record KnowledgeBrowseRequest(String categoryCode, String keyword, String code,
                                        long page, long pageSize) {
    }

    /** 一条知识条目；`body` 只在取单条时有值。 */
    public record KnowledgeBrowseItem(String code, String title, String summary, String body,
                                      String categoryCode, String categoryName, String riskHint,
                                      String reviewStatus, String sourceTitle, String sourceUrl) {
    }

    /** 浏览结果；`knowledgeCheck` 取值 `ok` / `unavailable`。 */
    public record KnowledgeBrowseResponse(List<KnowledgeBrowseItem> items, int total, String knowledgeCheck) {

        static KnowledgeBrowseResponse unavailable() {
            return new KnowledgeBrowseResponse(List.of(), 0, "unavailable");
        }
    }

    // ---- F011：症状识别（规则版）----

    /** 请求：一句话症状描述；不带宠物上下文（F011 不要求绑定宠物）。 */
    public record SymptomMatchRequest(String text) {
    }

    /** 一个被认出来的症状；`title` / `riskHint` 可能为空（词表命中而条目读不到）。 */
    public record SymptomMatchItem(String symptom, String entryCode, String title, String riskHint) {
    }

    /** 识别结果；`knowledgeCheck` 取值 `ok` / `unavailable`。 */
    public record SymptomMatchResponse(List<SymptomMatchItem> matches, String knowledgeCheck) {

        /** AI 侧读不到知识时的空结果——与「认不出症状」在协议上分开（前者是降级）。 */
        static SymptomMatchResponse unavailable() {
            return new SymptomMatchResponse(List.of(), "unavailable");
        }
    }

    /** 请求：宠物上下文 + 输入 + 历史。字段名经全局 SNAKE_CASE 序列化，与 Python 侧一致。 */
    public record ConsultRequest(String traceId, long userId, PetContext pet, ConsultInput input,
                                List<Object> history) {
    }

    /**
     * 宠物上下文。
     *
     * <p>{@code recentRecords} 对应 Python 侧 {@code PetContext.recent_records}——那个字段
     * **已经在 `ai/app/prompts.py` 里被渲染进提示词**（「近期记录：…」），所以专项照护信息
     * 走这条通道就能真的到达模型，而**不需要改 `ai/`**（模型调用与提示词都在那边，ADR-0009）。
     * 条目形如 {@code {"kind":"care_mode","active":true,"reasons":[...],"note":"..."}}。
     *
     * <p>为什么单独一个字段而不是塞进 {@code chronic}：那一位的语义是「已知慢病」，
     * 把「已进入老年期」写进去会让提示词里出现一句读不通的假病史。
     */
    public record PetContext(long id, int species, String breed, String birthDate, Double weight,
                             List<String> chronic, List<CareContext> recentRecords) {
    }

    /** 专项照护上下文的一条结构化事实（切片 #116）。字段名即模型看到的样子。 */
    public record CareContext(String kind, boolean active, List<String> reasons, String note) {
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
            /** 本次回答**实际引用**的知识条目（切片 #101）。
             *  <p><b>只含 {@code vetted}（兽医复核过）的条目</b>：口径是「没经兽医复核的内容
             *  不许被当作依据」，所以未复核条目不出现在这里，而是走 {@link #unvettedHits}（ADR-0033）。 */
            List<Citation> citations,
            /** 本轮进了模型上下文的**未复核**条目编号；非空时免责声明必须明说「尚未经兽医复核」。 */
            List<String> unvettedHits,
            int imagesUsed,
            List<String> redFlagHits,
            /** 命中的分级规则编号（如 {@code ["GR-001"]}）。与红线不同：红线短路，分级规则只抬档（#103）。 */
            List<String> gradingRuleHits,
            List<String> guardHits,
            String redFlagCheck,
            /** 知识检索这一层是否生效：ok / empty / unavailable / disabled / skipped。
             *  <p>{@code empty} 是「正常但没召回到」（召回缺口的观测口）、{@code unavailable} 是故障、
             *  {@code disabled} 是运营关掉了——三者含义不同，不能压成一个布尔。 */
            String retrievalCheck,
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

    /** 一条被引用的知识条目。字段与 Python 侧 {@code models.Citation} 一一对应。 */
    public record Citation(
            /** 对外编号 K-xxxx。留痕与界面都用它，不用各环境不同的自增 id。 */
            String entryId,
            String title,
            String category,
            String sourceTitle,
            String sourceVersion,
            String sourceUrl,
            /** 复核状态：只有 {@code vetted} 会走到这里（留着它是让界面能标「已复核」）。 */
            String reviewStatus) {
    }
}
