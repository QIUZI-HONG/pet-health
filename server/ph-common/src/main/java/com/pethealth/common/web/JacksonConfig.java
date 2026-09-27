package com.pethealth.common.web;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 全局格式（ADR-0011 + contract/common.yaml）：
 *
 * <ul>
 *   <li>字段名 snake_case——契约里写的就是 {@code request_id} / {@code access_token} / {@code page_size}，
 *       由 application.yml 的 {@code spring.jackson.property-naming-strategy} 负责；
 *   <li>时间 {@code yyyy-MM-dd HH:mm:ss}、日期 {@code yyyy-MM-dd}，与交付文档 8.4 的示例一致；
 *   <li>**{@link BigDecimal} 序列化成字符串**：交付文档的订单示例就是 {@code "total_amount": "128.00"}。
 *       金额、体重这类小数在 JS 里用 number 会丢精度，这条是「禁止浮点」在接口层的落点。
 * </ul>
 *
 * <p>用 {@code serializerByType} 而不是自己注册 Module，是为了不动 Spring Boot 已经装配好的
 * JavaTimeModule 等模块——只覆盖这几种类型。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer petHealthJacksonCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME))
                .serializerByType(LocalDate.class, new LocalDateSerializer(DATE))
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(DATE))
                .serializerByType(BigDecimal.class, ToStringSerializer.instance);
    }
}
