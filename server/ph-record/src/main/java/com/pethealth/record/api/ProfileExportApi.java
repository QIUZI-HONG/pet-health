package com.pethealth.record.api;

import com.pethealth.api.app.HealthReportPayload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案模块对外暴露的「导出用户全部档案数据」接口（切片 #74 的数据导出）。
 *
 * <p>与 {@link AiPetApi} 分开：那个是给 AI 看的一只宠物，这个是给**用户自己**看的全量，
 * 用途不同、字段集合也不同（导出要包含原始记录，AI 只需要上下文）。
 */
public interface ProfileExportApi {

    /** 一只宠物及其记录、评分与健康报告。 */
    record PetExport(
            long petId,
            String name,
            int species,
            String breed,
            int gender,
            LocalDate birthday,
            BigDecimal weight,
            boolean sterilized,
            String chronicDesc,
            List<RecordExport> records,
            List<ScoreExport> scores,
            /**
             * 健康报告（ADR-0031 决定六：报告进导出，复用档案导出通道而不新造下载接口）。
             * **没有报告时是空列表**，不是 null——纯加法，老调用方不受影响。
             */
            List<ReportExport> reports) {
    }

    /**
     * 一条档案记录（打卡分项、分项记录与防疫都是 archive_record 的行）。
     *
     * <p>{@code content} 是**该行的载荷原文**：打卡与防疫是扁平键值（`content` 列），
     * 分项记录是结构化对象（`structured_payload` 列）。导出视图刻意保持扁平的一列——
     * 给医院看的是原始记录，不是平台的字段划分（ADR-0030 第五条）。
     */
    record RecordExport(LocalDate recordDate, int category, String content, Integer source,
                        LocalDate dueOn, BigDecimal numericValue) {
    }

    /**
     * 一份健康报告（按周期一行）。
     *
     * <p>{@code payload} 直接用 ph-api 的 DTO，**不是 JSON 字符串**：报告正文是带嵌套结构的类型化对象，
     * 让调用方（ph-account）自己去解析库里那串 JSON 等于把「报告长什么样」这件事复制到第二个模块。
     */
    record ReportExport(int type, String typeName, LocalDate periodStart, LocalDate periodEnd,
                        String grade, Integer totalScore, HealthReportPayload payload) {
    }

    /** 一日健康评分。 */
    record ScoreExport(LocalDate calcDate, Integer totalScore, Integer physiology, Integer behavior,
                       Integer hygiene, Integer epidemic, Integer elderly) {
    }

    /** 该用户的全部宠物档案；没有宠物时返回空列表。 */
    List<PetExport> exportOf(long userId);

    /** 软删该用户的全部宠物及其档案（注销用）。返回受影响的宠物数。 */
    int softDeleteAll(long userId);
}
