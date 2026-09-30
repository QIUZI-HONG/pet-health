package com.pethealth.record.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.pethealth.api.app.HealthReportPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 报告正文（{@code health_report.payload}）的读写。
 *
 * <p><b>为什么不放进 {@code JsonFields}</b>：那一层是给「扁平键值 / 读一个字段」用的
 * （打卡的取值、防疫的疫苗名只有一个字符串形状），而报告正文是**带嵌套结构**的类型化对象
 * （四段 + 汇总数字），要按类型序列化与反序列化。两者混在一个工具类里，下一次改动很容易
 * 在某一侧改错形状（同样的理由让 {@code Weight} 有两个入口：{@code parse} 与 {@code parseOrNull}）。
 *
 * <p>命名用 SNAKE_CASE，与库里其它 JSON 列以及 API 的字段名一致——运维与排障时打开一行 JSON，
 * 看到的名字应该跟在接口里看到的一样。
 */
final class ReportPayloads {

    private static final Logger log = LoggerFactory.getLogger(ReportPayloads.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // 库里可能存着老模板拼出来的、当前类型没有的字段（版本演进）：忽略而不是抛错，
            // 否则一次模板调整会让历史报告在列表页 500
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReportPayloads() {
    }

    /** 序列化正文；失败即抛（写路径出错要让调用方看见，不能静默存半份报告）。 */
    static String write(HealthReportPayload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("报告正文序列化失败", e);
        }
    }

    /**
     * 反序列化正文；解析失败返回 null（列表页少一段正文，好过整页 500）。
     *
     * <p><b>返回 null 要留痕</b>：这条路径的触发条件是「库里那行的 payload 坏了或过时」，
     * 而它对用户的表现只是「这份报告少了正文」——不记日志的话，没有任何地方能发现
     * 有一行数据已经读不出来了（docs/conventions.md：禁止吞异常）。
     */
    static HealthReportPayload read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, HealthReportPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("报告正文解析失败，按「没有正文」处理：长度={} 原因={}", json.length(), e.getOriginalMessage());
            return null;
        }
    }
}
