package com.pethealth.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 脱敏格式（docs/conventions.md）：手机号 {@code 138****8888}、姓名 {@code 张*三}。 */
class MaskingTest {

    @Test
    @DisplayName("手机号保留前三后四")
    void phone() {
        assertThat(Masking.phone("13800138000")).isEqualTo("138****8000");
        assertThat(Masking.phone(null)).isEmpty();
        assertThat(Masking.phone("")).isEmpty();
    }

    @Test
    @DisplayName("短号整串打码，宁可少给信息也不漏")
    void shortPhone() {
        assertThat(Masking.phone("123456")).isEqualTo("******");
    }

    @Test
    @DisplayName("姓名保留首尾")
    void name() {
        assertThat(Masking.name("张三")).isEqualTo("张*");
        assertThat(Masking.name("张小三")).isEqualTo("张*三");
        assertThat(Masking.name("欧阳张三丰")).isEqualTo("欧***丰");
        assertThat(Masking.name("李")).isEqualTo("李");
        assertThat(Masking.name(null)).isEmpty();
    }
}
