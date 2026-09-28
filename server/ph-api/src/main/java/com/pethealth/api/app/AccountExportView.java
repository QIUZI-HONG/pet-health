package com.pethealth.api.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号数据导出，对应 contract/app.yaml 的 {@code AccountExportView}（切片 #74）。
 *
 * <p>一次交齐四类数据：账号资料、宠物、档案记录与评分、消息。**不含**任何不属于该用户的数据，
 * 也不含服务者侧的内部字段。
 *
 * <p>{@code notice} 是给用户看的一句话：导出的**不是**全部数据（例如服务者报工写入的记录在
 * 详情里没有逐字段展开），别让人误以为这就是完整副本。
 */
public record AccountExportView(
        UserProfile user,
        List<Pet> pets,
        List<PetMessage> messages,
        LocalDateTime exportedAt,
        String notice) {

    /** 一只宠物及其全部记录与评分。 */
    public record Pet(
            long petId,
            String name,
            int species,
            String breed,
            int gender,
            LocalDate birthday,
            BigDecimal weight,
            boolean sterilized,
            String chronicDesc,
            List<Record> records,
            List<Score> scores) {
    }

    /** 一条档案记录：打卡分项与防疫记录都是它的行。 */
    public record Record(LocalDate recordDate, int category, String content, Integer source,
                         LocalDate dueOn, BigDecimal numericValue) {
    }

    /** 一日健康评分。 */
    public record Score(LocalDate calcDate, Integer totalScore, Integer physiology, Integer behavior,
                        Integer hygiene, Integer epidemic, Integer elderly) {
    }

    /** 一条消息（提醒或业务通知）。 */
    public record PetMessage(String kind, String type, String title, String content, int riskLevel,
                             LocalDateTime remindAt, boolean read, LocalDateTime createdAt) {
    }
}
