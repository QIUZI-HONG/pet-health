package com.pethealth.provider.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethealth.api.provider.BusinessHour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 营业时段的 JSON 编解码（`provider.business_hours` 列）。
 *
 * <p>为什么不复用 {@code JsonFields}：它只做**扁平对象**（{@code Map<String,String>}）与单字段读，
 * 而营业时段是一个对象数组——写进去要序列化整个数组、读出来要还原成列表。硬塞进扁平工具会
 * 变成「键名拼成 day1_open」那种字符串协议，比这里多一个类糟得多。
 *
 * <p>失败策略与 JsonFields 的两种策略对齐：**写失败必须抛**（存坏了就是脏数据），
 * **读失败只记日志并当空**（展示路径不该因为一行脏 JSON 整页失败）。
 */
public final class BusinessHours {

    private static final Logger log = LoggerFactory.getLogger(BusinessHours.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BusinessHours() {
    }

    /** 编码为 JSON 文本；{@code null} 或空列表按「整周休息」处理（存空数组，不存 null）。 */
    public static String encode(List<BusinessHour> hours) {
        if (hours == null || hours.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(hours);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("营业时段序列化失败", e);
        }
    }

    /** 解码；空值或脏数据都返回空列表（前端按「未设置营业时间」展示）。 */
    public static List<BusinessHour> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<BusinessHour>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("营业时段解析失败，按未设置处理：{}", e.getMessage());
            return List.of();
        }
    }
}
