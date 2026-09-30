package com.pethealth.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.ai.client.AiServiceClient;
import com.pethealth.ai.config.AiQuotaProperties;
import com.pethealth.ai.config.AiServiceProperties;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.mapper.AiConsultMapper;
import com.pethealth.api.app.AiConsultCitation;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.api.app.AiConsultView;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.common.util.JsonFields;
import com.pethealth.common.util.Text;
import com.pethealth.ai.metrics.AiMetrics;
import com.pethealth.file.api.FileUrlApi;
import com.pethealth.record.api.AiPetApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 咨询（切片 #98）：C 端提问 → AI 服务 → 分级返回并**留痕**。
 *
 * <p>三件事按顺序发生，顺序本身是决定：
 *
 * <ol>
 *   <li>**归属校验**（宠物是不是他的）——别人的宠物一律 40400，且在花掉一次模型调用之前就拦掉；
 *   <li>**组装上下文**：宠物快照来自 ph-record 的接口，图片读地址来自 ph-file 的接口——
 *       本模块不碰任何别人的表（ADR-0006）；
 *   <li>**留痕**：无论成功、降级还是红线短路，都落一行 {@code ai_consult}。
 *       留痕不是可选项：ADR-0021 的「按 prompt_version 归因分级漂移」全指望它。
 * </ol>
 *
 * <p>**降级不向用户报错**（交付文档 2.4 / #98 验收标准）：AI 服务连不上或超时时，返回一条
 * 保守答复（`degraded=true`）并 200 返回；前端据此展示「建议尽快咨询兽医」，而不是一个错误页。
 */
@Service
public class AiConsultService {

    private static final Logger log = LoggerFactory.getLogger(AiConsultService.class);

    /**
     * 没有任何知识来源时的免责声明。**措辞刻意不承诺「专业知识库」**。
     *
     * <p>检索接上之后（#100/#101）这条并没有作废：它现在负责的是「**这一轮没有可用来源**」的
     * 那些情况——召回为空、检索读不到知识域、检索被运营关掉、模型没引用到任何条目、
     * 或者命中的条目都还没有兽医复核。它们在界面上长得一样：**不能出现「基于知识库」这句话**
     * （ADR-0028 的口径，ADR-0033 记了为什么它是硬约束）。
     * 用例 {@code AiConsultTest.disclaimerDoesNotOverpromiseKnowledgeSource} 钉着这条。
     */
    private static final String DISCLAIMER = "以上依据宠物的健康档案与 AI 判断，只表示就医紧迫程度，不能替代兽医诊断。";

    /**
     * **有 vetted（兽医复核过）引用**时的免责声明：只有这一条敢说「平台知识库」。
     *
     * <p>解禁条件是「至少有一条已复核引用」——{@code citations} 里只可能有 vetted 条目
     * （Python 侧就是这么筛的），所以「非空」在这个字段上恰好等价于那个条件。
     */
    private static final String KNOWLEDGE_DISCLAIMER =
            "以上依据宠物的健康档案、平台知识库的已复核条目与 AI 判断，只表示就医紧迫程度，不能替代兽医诊断。";

    /**
     * 用到了**未复核**条目时的免责声明：如实说明，而不是把待复核内容当成权威资料。
     *
     * <p>口径来自项目所有者（2026-09-29）：检索允许使用未复核内容，但「该建议尚未经兽医复核」
     * 必须出现在回答里。这里也**不出现「知识库」**——那些条目还没被认可为可依据的知识。
     */
    private static final String UNVETTED_DISCLAIMER =
            "本次建议参考了尚未经兽医复核的平台资料，仅供参考，不能替代兽医诊断。";

    /** 有已复核引用、同时也用到了未复核条目：两句都要说，别让前者掩盖后者。 */
    private static final String MIXED_KNOWLEDGE_DISCLAIMER =
            "以上依据宠物的健康档案、平台知识库的已复核条目与 AI 判断，只表示就医紧迫程度；"
                    + "其中部分建议参考的条目尚未经兽医复核，仅供参考，不能替代兽医诊断。";

    /**
     * 红线命中时的免责声明：这时**没有模型参与**，结论是平台的急症规则给的。
     *
     * <p>分开写不是措辞讲究，而是让用户与事后复核都能分清「模型判的红」与「规则判的红」——
     * 与 ADR-0021 把 {@code model_name} 标成 {@code rule:red_flag} 是同一个理由。
     */
    private static final String RED_FLAG_DISCLAIMER =
            "本次结论由平台的急症红线规则直接给出（未经模型判断），只表示就医紧迫程度，不能替代兽医诊断。";

    /**
     * 降级时的免责声明：这一轮**模型没给出可用结果**（不可用、超时、没按格式回答）。
     *
     * <p>不能复用上面那条「依据…与 AI 判断」——降级路径没有 AI 判断可依据，
     * 那样写与「承诺了没做的事」是同一类问题（评审指出）。
     */
    private static final String DEGRADED_DISCLAIMER =
            "本次未能走通 AI 判断，以下是为了不耽误而给出的保守建议，不能替代兽医诊断。";

    /** 降级答复：宁可让用户白跑一趟，也不让急症被「等 AI」耽误（docs/conventions.md 宁严勿松）。 */
    private static final String DEGRADED_ADVICE =
            "AI 服务暂时不可用，为避免耽误，建议尽快咨询兽医；情况紧急请直接送医。";

    /** Java 侧连不上 AI 服务时的降级码（与 Python 侧的四个码同一命名空间）。 */
    private static final String DEGRADE_CODE_AI_UNREACHABLE = "ai_service_unreachable";

    /** 红线预检「没生效」的取值，与 Python 侧 `models.py` 的 {@code red_flag_check} 同一套。 */
    private static final String RED_FLAG_CHECK_UNAVAILABLE = "unavailable";

    /**
     * 知识检索「读不到知识域」的取值（Python 侧 {@code retrieval_check}）。
     *
     * <p>注意**只有这一种取值要告警**：{@code empty}（召回为空）是正常结果——召回率低是
     * ADR-0022 承认的弱点，它的观测口是留痕与指标，不是日志告警；{@code disabled} 是运营自己关的。
     */
    private static final String RETRIEVAL_CHECK_UNAVAILABLE = "unavailable";

    /** 一次咨询最多带几张图：与契约的 {@code file_ids maxItems} 以及 Python 侧的上限对齐。 */
    private static final int MAX_IMAGES = 4;

    private final AiServiceClient client;
    private final AiConsultMapper consultMapper;
    private final AiPetApi petApi;
    private final FileUrlApi fileUrlApi;
    private final FieldCipher fieldCipher;
    private final AiQuotaProperties quota;
    private final AiServiceProperties serviceProperties;
    private final AiMetrics metrics;
    private final AiAdviceRecorder adviceRecorder;

    public AiConsultService(AiServiceClient client, AiConsultMapper consultMapper, AiPetApi petApi,
                            FileUrlApi fileUrlApi, FieldCipher fieldCipher,
                            AiQuotaProperties quota, AiServiceProperties serviceProperties,
                            AiMetrics metrics, AiAdviceRecorder adviceRecorder) {
        this.client = client;
        this.consultMapper = consultMapper;
        this.petApi = petApi;
        this.fileUrlApi = fileUrlApi;
        this.fieldCipher = fieldCipher;
        this.quota = quota;
        this.serviceProperties = serviceProperties;
        this.metrics = metrics;
        this.adviceRecorder = adviceRecorder;
    }

    public AiConsultView consult(long userId, long petId, AiConsultRequest request) {
        AiPetApi.PetSnapshot pet = petApi.snapshotOwnedBy(userId, petId)
                .orElseThrow(BusinessException::notFound);

        List<Long> fileIds = request.fileIds() == null ? List.of() : request.fileIds();
        List<String> mediaUrls = fileIds.isEmpty()
                ? List.of()
                : absolute(fileUrlApi.readUrls(userId, fileIds.stream().limit(MAX_IMAGES).toList()));

        String traceId = TraceIds.currentTraceId();
        AiServiceClient.ConsultResponse response = callAi(userId, pet, request.question(), mediaUrls, traceId);

        // 留痕里的图片张数用 AI 服务**实际送进模型**的张数，不是我们交出去的张数（测试报告 D27）
        if (!"ok".equals(response.redFlagCheck())) {
            // 红线预检这一层没生效（词表为空 / 读不到库）：red_flag_check=unavailable。
            // 它是「红色 100% 召回」那条验收标准的机械保障，静默失效比失效本身更危险——
            // 所以在这里留一条 warn（排障与告警系统都看得见），而不是等事后从留痕里发现。
            log.warn("红线预检未生效 trace_id={} user_id={} red_flag_check={}",
                    traceId, userId, response.redFlagCheck());
        }
        if (RETRIEVAL_CHECK_UNAVAILABLE.equals(response.retrievalCheck())) {
            // 检索读不到知识域：这一轮的回答**没有知识来源**（citations 必为空）。
            // 与红线那一层不同，它不影响安全性（回答照常给，只是无来源），所以只记 warn 不阻断；
            // 但静默失效同样不可接受——「来源可追溯」是 #101 的验收标准。
            log.warn("知识检索未生效 trace_id={} user_id={} retrieval_check={}",
                    traceId, userId, response.retrievalCheck());
        }
        AiConsult record = save(userId, petId, request.question(), response.imagesUsed(), response, traceId);
        // 任务中心「查看 AI 建议」的进度（`AI_ADVICE`，0 分 = 只记行为不发分，见 V27 的种子）。
        // **只在真的产出了建议时记**，判据就是 degraded：
        //   · 降级不记——这一轮模型没给出可用结果（答复是保守话术，免责声明也明说「未能走通 AI 判断」），
        //     记它等于把一次**没产出建议**的咨询算成任务完成，那是对用户的假承诺；
        //   · 红线短路**记**——它不是降级：结论是平台的急症规则给的、用户拿到的是一条明确的建议
        //     （尽快就医）。把它排除会让「问急症的人任务永远不前进」，而这条任务的名字本就是
        //     「查看 AI 建议」——计量的是「这次咨询有没有拿到建议」，不是「模型有没有参与」。
        // 记行为走 ph-privilege 的接口（不是本模块的表），失败只降级不影响这次咨询：
        // AiAdviceRecorder 内部 catch + WARN。
        if (!response.degraded()) {
            adviceRecorder.record(userId);
        }
        // 打点：降级占比与分级分布是「提示词改坏 / 模型静默换版」的最早信号（ADR-0029）。
        // 界面上看不出任何异常——用户拿到的永远是一段格式正常的回答
        metrics.consulted(response.degraded(),
                response.redFlagHits() != null && !response.redFlagHits().isEmpty(),
                response.riskLevel());
        // 计数取「含本次在内」的当天行数；请求被拒（越权、参数错）本就到不了这里，不消耗额度
        return toView(record, response, countToday(userId));
    }

    /**
     * 签名读地址是**相对路径**（{@code /api/v1/open/files/…}），浏览器按当前站点解析没问题，
     * 但 AI 服务没有「我们的站点」这个概念——它得自己去把图片取回来（Python 侧 `inline_images`），
     * 相对路径取不到，而且失败是**静默的**：模型基于「没看到图」给出格式正常的回答。
     * 所以出站前补成 AI 服务可达的绝对地址（测试报告 D7）。
     */
    private List<String> absolute(List<String> urls) {
        String base = serviceProperties.fileBaseUrl();
        return urls.stream().map(url -> url.startsWith("http") ? url : base + url).toList();
    }

    /** 调 AI 服务；失败时给出降级答复（不抛错，不向用户报错）。 */
    private AiServiceClient.ConsultResponse callAi(long userId, AiPetApi.PetSnapshot pet, String question,
                                                   List<String> mediaUrls, String traceId) {
        AiServiceClient.ConsultRequest payload = new AiServiceClient.ConsultRequest(
                traceId,
                userId,
                new AiServiceClient.PetContext(
                        pet.petId(),
                        pet.species(),
                        pet.breed(),
                        pet.birthDate() == null ? null : pet.birthDate().toString(),
                        pet.weight() == null ? null : pet.weight().doubleValue(),
                        pet.chronicDesc() == null ? List.of() : List.of(pet.chronicDesc()),
                        careContext(pet)),
                new AiServiceClient.ConsultInput(
                        mediaUrls.isEmpty() ? "text" : "image", question, mediaUrls),
                List.of());

        try {
            return client.consult(payload);
        } catch (AiServiceClient.AiUnavailable e) {
            // 降级留痕：降级率是判断「模型好不好用」最直接的指标（与 Python 侧同一口径）
            log.warn("AI 咨询降级 trace_id={} user_id={} reason={}", traceId, userId, e.getMessage());
            return new AiServiceClient.ConsultResponse(
                    2, List.of(), DEGRADED_ADVICE, true, List.of(),
                    // 降级答复一律**没有**来源：结论不是基于知识条目给的，带上来源等于给固定话术伪造出处
                    List.of(), List.of(),
                    0, List.of(), List.of(), List.of(),
                    // 连不上 AI 服务 = 红线预检这一层**确定没有跑过**，所以这里必须报 unavailable。
                    // 原先写成 "ok"：那会让 consult() 里的告警永远不触发，留痕里也记着「安全网正常」——
                    // 与「静默少一层比少一层本身更危险」正相反（同 Python 侧 models.py 对该字段的定义）
                    RED_FLAG_CHECK_UNAVAILABLE,
                    // 同理：连不上 AI 服务，检索这一层也确定没跑过 → unavailable（不是 empty）
                    RETRIEVAL_CHECK_UNAVAILABLE,
                    true, DEGRADE_CODE_AI_UNREACHABLE, e.getMessage(),
                    "", "", "", 0,
                    // 连不上 AI 服务：这一轮没有 token 消耗（也没花钱）
                    0, 0);
        }
    }

    /**
     * 专项照护的上下文（交付文档 F009：「AI 咨询的上下文带上专项信息」）。
     *
     * <p>没开启就返回空列表——**不能给一个 active=false 的条目**：模型只会看到一句
     * 「专项照护未开启」，那是多余的噪音，而且容易被读成「这只宠物有专项问题但没开」。
     *
     * <p>这条信息的**判定来源是档案模块**（{@code AiPetApi.PetSnapshot.careMode}，ADR-0032 决定一），
     * 这里只做搬运：本模块不自己算年龄，也不自己解释慢病。
     */
    private List<AiServiceClient.CareContext> careContext(AiPetApi.PetSnapshot pet) {
        if (!pet.careMode()) {
            return List.of();
        }
        List<String> reasons = new java.util.ArrayList<>();
        if (pet.ageText() != null) {
            reasons.add("年龄 " + pet.ageText());
        }
        if (pet.chronicDesc() != null && !pet.chronicDesc().isBlank()) {
            reasons.add("慢病：" + pet.chronicDesc());
        }
        return List.of(new AiServiceClient.CareContext("care_mode", true, reasons,
                "这只宠物处于专项照护模式（老年或慢病），请按更保守的紧迫程度判断"));
    }

    private AiConsult save(long userId, long petId, String question, int imageCount,
                           AiServiceClient.ConsultResponse response, String traceId) {
        AiConsult record = new AiConsult();
        record.setUserId(userId);
        record.setPetId(petId);
        record.setTraceId(traceId);
        // 健康描述按病历口径加密（ADR-0013）：明文入库等于把病史摊在备份里
        record.setQuestionEnc(fieldCipher.encrypt(question));
        record.setImageCount(imageCount);
        record.setRiskLevel(response.riskLevel());
        record.setPossibleCauses(toJson(response.possibleCauses()));
        record.setActionSuggestion(Text.truncate(response.actionSuggestion(), 1024));
        record.setNeedHospital(response.needHospital() ? 1 : 0);
        record.setCareTips(toJson(response.careTips()));
        record.setRedFlagHits(toJson(response.redFlagHits()));
        record.setGuardHits(toJson(response.guardHits()));
        record.setRedFlagCheck(response.redFlagCheck() == null ? "ok" : response.redFlagCheck());
        record.setDegraded(response.degraded() ? 1 : 0);
        // 留痕表存**内部明细**（含降级码与异常/上游原文），供事后归因；
        // 给用户看的那句话在 toView 里按降级码映射（测试报告 D6）
        record.setDegradeReason(Text.truncate(internalReason(response), 256));
        record.setModelName(Text.truncate(response.modelName(), 64));
        record.setModelVersion(Text.truncate(response.modelVersion(), 64));
        record.setPromptVersion(Text.truncate(response.promptVersion(), 64));
        record.setLatencyMs(response.latencyMs());
        // token 用量进留痕：日预算告警按它估算当天花费（ADR-0026），也是模型换代/提示词改版的归因依据
        record.setPromptTokens(response.promptTokens());
        record.setCompletionTokens(response.completionTokens());
        // 引用与检索状态进留痕（ADR-0033）：事后要能回答「这句话当时有依据吗、依据复核过吗」
        record.setCitations(toJson(citationLabels(response.citations())));
        record.setUnvettedHits(toJson(response.unvettedHits()));
        record.setGradingRuleHits(toJson(response.gradingRuleHits()));
        record.setRetrievalCheck(Text.truncate(
                response.retrievalCheck() == null ? "ok" : response.retrievalCheck(), 16));
        consultMapper.insert(record);
        return record;
    }

    /** 内部明细：{@code 降级码: 具体原因}。留痕归因用，不对外。 */
    private String internalReason(AiServiceClient.ConsultResponse response) {
        if (!response.degraded()) {
            return null;
        }
        String code = response.degradeCode() == null ? "degraded" : response.degradeCode();
        return response.degradeDetail() == null ? code : code + ": " + response.degradeDetail();
    }

    /**
     * 降级码 → 给用户看的一句话。
     *
     * <p>为什么不让上游的 {@code degrade_reason} 直接出给用户：那个字段里是异常类名、
     * 上游原始响应、甚至**模型返回的原始文本**（参数不是合法 JSON 时会带上原文片段）——
     * 既有内部信息泄漏，也可能带着没有过护栏的医疗内容（测试报告 D6）。
     * 用户只需要知道「这次的结果是什么性质、该做什么」。
     */
    private String userFacingReason(AiServiceClient.ConsultResponse response) {
        if (!response.degraded()) {
            return null;
        }
        String code = response.degradeCode() == null ? "" : response.degradeCode();
        return switch (code) {
            case "image_not_supported" -> "当前没有能看图的模型，这次只按文字描述判断";
            case "image_unavailable" -> "这次没能读到你的图片，只按文字描述判断";
            case "model_output_invalid" -> "模型这次没有按格式回答，已按更保守的结论给你";
            case DEGRADE_CODE_AI_UNREACHABLE -> "AI 服务暂时不可用，已按更保守的建议给你";
            default -> "AI 服务暂时不可用，已按更保守的建议给你";
        };
    }

    /**
     * 今日已用次数。
     *
     * <p>**直接数留痕表的行**，而不是另用一个 Redis 计数器：一次咨询必落一行 `ai_consult`，
     * 所以这张表就是「今天问了几次」的权威记录。多一个计数器就多一处漂移（计数与留痕不一致时，
     * 谁都说不清哪个对），而按用户 + 时间取数的查询已经有索引（`idx_user_time`）。
     *
     * <p>**只计数不拦截**（ADR-0024）：到量后接口行为不变，界面负责劝说。
     */
    private int countToday(long userId) {
        Long used = consultMapper.selectCount(Wrappers.<AiConsult>lambdaQuery()
                .eq(AiConsult::getUserId, userId)
                .ge(AiConsult::getCreatedAt, AppTime.today().atStartOfDay()));
        return used == null ? 0 : used.intValue();
    }

    private AiConsultView toView(AiConsult record, AiServiceClient.ConsultResponse response, int usedToday) {
        return new AiConsultView(
                record.getId(),
                response.riskLevel(),
                response.possibleCauses() == null ? List.of() : response.possibleCauses(),
                response.actionSuggestion(),
                response.needHospital(),
                response.careTips() == null ? List.of() : response.careTips(),
                response.redFlagHits() == null ? List.of() : response.redFlagHits(),
                citationsOf(response),
                response.unvettedHits() != null && !response.unvettedHits().isEmpty(),
                response.degraded(),
                userFacingReason(response),
                response.modelVersion(),
                response.promptVersion(),
                response.latencyMs(),
                disclaimerFor(response),
                record.getCreatedAt(),
                quota.freePerDay(),
                Math.max(0, quota.freePerDay() - usedToday));
    }

    /**
     * 按「这次结果是谁给的、有没有来源」选免责声明。
     *
     * <p>判据全部用**语义字段**（{@code degraded} / {@code redFlagHits} / {@code citations} /
     * {@code unvettedHits}），而不是留痕字段 {@code modelName}——改留痕的取值不该影响给用户看的话。
     *
     * <p>五条分支对应五种事实，**「有没有知识来源」这一维是这次新增的**：
     * <ol>
     *   <li>降级：模型没给出可用结果 → 不说「与 AI 判断」；
     *   <li>红线短路：结论由急症规则给出、未经模型；
     *   <li>有已复核引用 → 这是唯一敢说「平台知识库」的分支；
     *   <li>只用到未复核条目 → 明说「尚未经兽医复核」，且不提知识库；
     *   <li>其余（无来源 / 召回为空 / 检索不可用）→ 与接检索之前那句一样，不做任何来源承诺。
     * </ol>
     */
    private String disclaimerFor(AiServiceClient.ConsultResponse response) {
        if (response.degraded()) {
            return DEGRADED_DISCLAIMER;
        }
        List<String> hits = response.redFlagHits();
        if (hits != null && !hits.isEmpty()) {
            return RED_FLAG_DISCLAIMER;
        }
        boolean hasVetted = response.citations() != null && !response.citations().isEmpty();
        boolean usedUnvetted = response.unvettedHits() != null && !response.unvettedHits().isEmpty();
        if (hasVetted) {
            return usedUnvetted ? MIXED_KNOWLEDGE_DISCLAIMER : KNOWLEDGE_DISCLAIMER;
        }
        return usedUnvetted ? UNVETTED_DISCLAIMER : DISCLAIMER;
    }

    /** 引用条目 → 契约形状的 DTO。字段名与 Python 侧 {@code Citation} 对齐（跨服务契约）。 */
    private static List<AiConsultCitation> citationsOf(AiServiceClient.ConsultResponse response) {
        if (response.citations() == null) {
            return List.of();
        }
        return response.citations().stream()
                .map(citation -> new AiConsultCitation(
                        citation.entryId(), citation.title(), citation.category(),
                        citation.sourceTitle(), citation.sourceVersion(), citation.sourceUrl(),
                        citation.reviewStatus()))
                .toList();
    }

    /**
     * 留痕里记引用编号与来源名（而不是只记编号）：事后核查「当时引用的是哪一版资料」要看后者。
     * 未复核条目的编号另记一列（{@code unvetted_hits}），两列分开才能回答「这句话当时有依据吗」。
     */
    private static List<String> citationLabels(List<AiServiceClient.Citation> citations) {
        if (citations == null) {
            return List.of();
        }
        return citations.stream()
                .map(citation -> citation.entryId() + "|" + Text.truncate(citation.sourceTitle(), 64))
                .toList();
    }

    /** 列表字段存 JSON。空列表与 null 都不写（库里是「没记下」，不是「记了个空数组」）。 */
    private static String toJson(List<String> values) {
        return values == null || values.isEmpty() ? null : JsonFields.writeQuietly(values);
    }
}
