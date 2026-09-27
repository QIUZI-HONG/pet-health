package com.pethealth.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段加密的单元测试（ADR-0013）。这些不依赖数据库，用不着 Testcontainers。
 */
class FieldCipherTest {

    private static final String KEY_A = base64Key('A');
    private static final String KEY_B = base64Key('B');
    private static final String HMAC_KEY = base64Key('H');

    private final FieldCipher cipher = new FieldCipher(new FieldCryptoProperties(KEY_A, HMAC_KEY, null));

    @Test
    @DisplayName("加解密往返一致，且密文里看不到明文")
    void roundTrip() {
        String phone = "13800138000";

        String encrypted = cipher.encrypt(phone);

        assertThat(encrypted).startsWith("v1:").doesNotContain(phone);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(phone);
    }

    @Test
    @DisplayName("同一个明文两次加密结果不同（随机 IV），但都能解回来")
    void encryptionIsRandomized() {
        String first = cipher.encrypt("13800138000");
        String second = cipher.encrypt("13800138000");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("密文被篡改时解密失败，而不是解出垃圾")
    void tamperedCiphertextFails() {
        String encrypted = cipher.encrypt("13800138000");
        byte[] payload = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
        payload[payload.length - 1] ^= 0x01;   // 翻转最后一位
        String tampered = "v1:" + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("轮换期间用旧密钥解老数据，新数据仍用新密钥")
    void previousKeyDecryptsOldCiphertext() {
        FieldCipher oldCipher = new FieldCipher(new FieldCryptoProperties(KEY_B, HMAC_KEY, null));
        String encryptedWithOldKey = oldCipher.encrypt("13800138000");

        FieldCipher rotated = new FieldCipher(new FieldCryptoProperties(KEY_A, HMAC_KEY, KEY_B));

        assertThat(rotated.decrypt(encryptedWithOldKey)).isEqualTo("13800138000");
        // 新写入的用当前密钥，解回来还是同一个值
        assertThat(rotated.decrypt(rotated.encrypt("13900139000"))).isEqualTo("13900139000");
    }

    @Test
    @DisplayName("查找值恒定，与加密结果无关——唯一索引靠它")
    void lookupHashIsDeterministic() {
        assertThat(cipher.lookupHash("13800138000")).isEqualTo(cipher.lookupHash("13800138000"));
        assertThat(cipher.lookupHash("13800138000")).hasSize(64);
        assertThat(cipher.lookupHash("13800138000")).isNotEqualTo(cipher.lookupHash("13800138001"));
        // 查找值与密文不同源：拿到密文并不能反推查找值
        assertThat(cipher.encrypt("13800138000")).doesNotContain(cipher.lookupHash("13800138000"));
    }

    @Test
    @DisplayName("密钥长度不对时启动即报错，不要等到线上才炸")
    void invalidKeyLengthFailsFast() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new FieldCipher(new FieldCryptoProperties(tooShort, HMAC_KEY, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("缺少 v1: 前缀的密文直接拒绝")
    void missingVersionPrefixIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt("not-encrypted"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1:");
    }

    private static String base64Key(char filler) {
        return Base64.getEncoder().encodeToString((String.valueOf(filler).repeat(32)).getBytes(StandardCharsets.UTF_8));
    }
}
