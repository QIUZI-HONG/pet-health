package com.pethealth.record.api;

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

    /** 一只宠物及其记录与评分。 */
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
            List<ScoreExport> scores) {
    }

    /** 一条档案记录（含打卡分项与防疫——它们都是 archive_record 的行）。 */
    record RecordExport(LocalDate recordDate, int category, String content, Integer source,
                        LocalDate dueOn, BigDecimal numericValue) {
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
