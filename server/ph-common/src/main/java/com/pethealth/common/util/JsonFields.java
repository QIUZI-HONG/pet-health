package com.pethealth.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 存取数据库里那些 JSON 列（如 {@code archive_record.content}）。
 *
 * <p>为什么不用手写字符串解析：一开始三处服务各自写了「找 `"key":"` 再截到引号」的实现，
 * 结果**连反斜杠都没转义**——名称里带一个 {@code \} 就把 JSON 写坏了。
 * 这类东西只该有一份，且该用真正的 JSON 库。
 *
 * <p>用静态方法 + 自己的 ObjectMapper：这些小工具不该依赖 Spring 上下文。
 */
public final class JsonFields {

    private static final Logger log = LoggerFactory.getLogger(JsonFields.class);

    /** 日志里回显原文的上限：脏数据可能是整列长文本，全量打进日志只会把有用的行挤掉。 */
    private static final int LOG_RAW_LIMIT = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonFields() {
    }

    /**
     * 写一个扁平的对象，例如 {@code {"kind":"vaccine","name":"狂犬疫苗"}}。
     *
     * @param fields 待写入的键值对，值允许为 null（Jackson 会写成 null）
     * @return JSON 文本
     * @throws IllegalStateException 序列化失败时抛出——写路径出错要让调用方看见，不能静默存半条
     */
    public static String write(Map<String, String> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /**
     * 写任意可序列化的值，**失败只记日志、返回 null**。
     *
     * <p>与 {@link #write} 的两种策略是刻意分开的：
     *
     * <ul>
     *   <li>{@link #write} 用于**业务数据**（打卡的取值、防疫的疫苗名）——存坏了就是脏数据，
     *       必须抛出去让调用方看见；
     *   <li>{@code writeQuietly} 用于**留痕字段**（AI 咨询的命中列表）——留痕失败不该让
     *       一次已经成功的咨询变成错误，null 在库里就是「没记下」。
     * </ul>
     */
    public static String writeQuietly(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("留痕序列化失败，按「未记录」处理：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 读一个字段。
     *
     * <p>解析不出来或字段缺失时返回 null——**脏数据不该让整条链路失败**：调用方都是
     * 「有就带上、没有就算了」的展示路径（打卡的体重/备注、防疫记录的疫苗名）。
     *
     * <p>但失败**必须留痕**：原先这里是空 catch，于是 {@code content} 被写坏之后，
     * 体重与备注会静默变成 null，趋势图上少一格而没有任何线索可查——
     * 违反 docs/conventions.md 的「禁止吞异常」。返回值不变，只是不再无声。
     *
     * @param json  数据库里的 JSON 原文；null 或空白按「没有内容」处理
     * @param field 顶层字段名
     * @return 字段的字符串值；字段缺失、值为 JSON null、或原文解析失败都是 null
     */
    public static String read(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (JsonProcessingException e) {
            log.warn("JSON 字段解析失败，按缺失处理：field={} json={}", field, forLog(json), e);
            return null;
        }
    }

    /** 日志里回显原文（截断），别把整列长文本灌进日志。 */
    private static String forLog(String raw) {
        return raw.length() <= LOG_RAW_LIMIT ? raw : raw.substring(0, LOG_RAW_LIMIT) + "…";
    }
}
