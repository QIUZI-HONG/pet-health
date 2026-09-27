package com.pethealth.common.util;

/**
 * 脱敏：手机号 {@code 138****8888}、姓名 {@code 张*三}（docs/conventions.md）。
 *
 * <p>手机号在库里是密文（ADR-0013），**接口返回的一律是脱敏值**——解密只发生在确实需要真值的场景
 * （发短信、用户导出自己的数据），且不经过任何列表接口。
 */
public final class Masking {

    private Masking() {
    }

    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        if (phone.length() < 7) {
            // 长度不足以按「前 3 后 4」脱敏时，整串打码，宁可信息少也不漏
            return "*".repeat(phone.length());
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String name(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
