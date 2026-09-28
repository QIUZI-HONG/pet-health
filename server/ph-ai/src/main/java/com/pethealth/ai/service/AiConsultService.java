package com.pethealth.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.ai.client.AiServiceClient;
import com.pethealth.ai.config.AiQuotaProperties;
import com.pethealth.ai.domain.AiConsult;
import com.pethealth.ai.mapper.AiConsultMapper;
import com.pethealth.api.app.AiConsultRequest;
import com.pethealth.api.app.AiConsultView;
import com.pethealth.common.crypto.FieldCipher;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.time.AppTime;
import com.pethealth.common.trace.TraceIds;
import com.pethealth.file.api.FileUrlApi;
import com.pethealth.record.api.AiPetApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    /** 免责声明由后端给：医疗文案散落到前端各处时，改起来一定会漏。 */
    private static final String DISCLAIMER = "以上基于宠物的健康档案与专业知识库，用于判断就医紧迫程度，不能替代兽医诊断。";

    /** 降级答复：宁可让用户白跑一趟，也不让急症被「等 AI」耽误（docs/conventions.md 宁严勿松）。 */
    private static final String DEGRADED_ADVICE =
            "AI 服务暂时不可用，为避免耽误，建议尽快咨询兽医；情况紧急请直接送医。";

    /** 一次咨询最多带几张图：模型看太多图会明显变慢，而慢在网络不好的时候等于降级。 */
    private static final int MAX_IMAGES = 4;

    private final AiServiceClient client;
    private final AiConsultMapper consultMapper;
    private final AiPetApi petApi;
    private final FileUrlApi fileUrlApi;
    private final FieldCipher fieldCipher;
    private final ObjectMapper objectMapper;
    private final AiQuotaProperties quota;

    public AiConsultService(AiServiceClient client, AiConsultMapper consultMapper, AiPetApi petApi,
                            FileUrlApi fileUrlApi, FieldCipher fieldCipher, ObjectMapper objectMapper,
                            AiQuotaProperties quota) {
        this.client = client;
        this.consultMapper = consultMapper;
        this.petApi = petApi;
        this.fileUrlApi = fileUrlApi;
        this.fieldCipher = fieldCipher;
        this.objectMapper = objectMapper;
        this.quota = quota;
    }

    public AiConsultView consult(long userId, long petId, AiConsultRequest request) {
        AiPetApi.PetSnapshot pet = petApi.snapshotOwnedBy(userId, petId)
                .orElseThrow(BusinessException::notFound);

        List<Long> fileIds = request.fileIds() == null ? List.of() : request.fileIds();
        List<String> mediaUrls = fileIds.isEmpty()
                ? List.of()
                : fileUrlApi.readUrls(userId, fileIds.stream().limit(MAX_IMAGES).toList());

        String traceId = TraceIds.currentTraceId();
        AiServiceClient.ConsultResponse response = callAi(userId, pet, request.question(), mediaUrls, traceId);

        AiConsult record = save(userId, petId, request.question(), mediaUrls.size(), response, traceId);
        // 计数取「含本次在内」的当天行数；请求被拒（越权、参数错）本就到不了这里，不消耗额度
        return toView(record, response, countToday(userId));
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
                        pet.chronicDesc() == null ? List.of() : List.of(pet.chronicDesc())),
                new AiServiceClient.ConsultInput(
                        mediaUrls.isEmpty() ? "text" : "image", question, mediaUrls),
                List.of());

        try {
            return client.consult(payload);
        } catch (AiServiceClient.AiUnavailable e) {
            // 降级留痕：降级率是判断「模型好不好用」最直接的指标（与 Python 侧同一口径）
            log.warn("AI 咨询降级 trace_id={} user_id={} reason={}", traceId, userId, e.getMessage());
            return new AiServiceClient.ConsultResponse(
                    2, List.of(), DEGRADED_ADVICE, true, List.of(), List.of(),
                    0, List.of(), List.of(), "ok",
                    true, "java_client: " + e.getMessage(),
                    "", "", "", 0);
        }
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
        record.setActionSuggestion(trim(response.actionSuggestion(), 1024));
        record.setNeedHospital(response.needHospital() ? 1 : 0);
        record.setCareTips(toJson(response.careTips()));
        record.setRedFlagHits(toJson(response.redFlagHits()));
        record.setGuardHits(toJson(response.guardHits()));
        record.setRedFlagCheck(response.redFlagCheck() == null ? "ok" : response.redFlagCheck());
        record.setDegraded(response.degraded() ? 1 : 0);
        record.setDegradeReason(trim(response.degradeReason(), 256));
        record.setModelName(trim(response.modelName(), 64));
        record.setModelVersion(trim(response.modelVersion(), 64));
        record.setPromptVersion(trim(response.promptVersion(), 64));
        record.setLatencyMs(response.latencyMs());
        consultMapper.insert(record);
        return record;
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
                response.degraded(),
                response.degradeReason(),
                response.modelVersion(),
                response.promptVersion(),
                response.latencyMs(),
                DISCLAIMER,
                record.getCreatedAt(),
                quota.freePerDay(),
                Math.max(0, quota.freePerDay() - usedToday));
    }

    /** 列表字段存 JSON。序列化失败不该让整次咨询失败，留痕里写 null 即可。 */
    private String toJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("留痕序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
