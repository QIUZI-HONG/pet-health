package com.pethealth.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonFields() {
    }

    /** 写一个扁平的对象，例如 {@code {"kind":"vaccine","name":"狂犬疫苗"}}。 */
    public static String write(Map<String, String> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /** 读一个字段；解析不出来或字段缺失时返回 null（脏数据不该让整条链路失败）。 */
    public static String read(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
