package com.pethealth.catalog.api;

import com.pethealth.common.error.BusinessException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 金额值对象：解析、校验与格式化价格的**唯一入口**（目录侧与上架定价都走这里）。
 *
 * <p>为什么要有它：钱是 decimal(10,2) 且禁止浮点（docs/conventions.md / ADR-0011），
 * 而接口收到的价格是字符串。三处规则必须一致——最多两位小数、上限、是否允许 0——
 * 分散在 DTO 注解、service、mapper 里就会各改各的（体重那条链路上已经踩过一次：注解里抄了一份规则）。
 *
 * <p>两条边界是刻意不同的：
 *
 * <ul>
 *   <li><b>目录的区间边界允许 0</b>：{@code 0.00–50.00} 的试吃装是合法的（区间下限为 0 表示
 *       「从免费起」）。
 *   <li><b>服务者上架价必须 &gt; 0</b>：0 元不做成价格——免费体验由券表达（交付文档的券类型 3），
 *       而不是把价签写成 0，否则「0 元单」会一路走进下单与对账，而平台不经手资金、无从处置。
 * </ul>
 */
public final class Price {

    /** 与 DECIMAL(10,2) 一致：整数部分最多 8 位，小数固定 2 位。 */
    public static final BigDecimal MAX = new BigDecimal("99999999.99");

    public static final int SCALE = 2;

    private static final Pattern SHAPE = Pattern.compile("^\\d{1,8}(\\.\\d{1,2})?$");

    private Price() {
    }

    /**
     * 解析一个非负价格（目录区间边界用）。
     *
     * @param raw        契约里的字符串价格，如 {@code "128.00"}
     * @param fieldLabel 出错时给用户看的字段名，如「价格区间下限」
     * @return 两位小数的 {@link BigDecimal}（四舍五入方向不存在：这里拒绝多于两位小数，不静默改金额）
     * @throws BusinessException 40001——格式不对、多于两位小数、超过上限
     */
    public static BigDecimal parse(String raw, String fieldLabel) {
        BigDecimal value = parseShape(raw, fieldLabel);
        if (value.compareTo(MAX) > 0) {
            throw BusinessException.paramInvalid(fieldLabel + "不能超过 " + MAX.toPlainString() + " 元");
        }
        return value.setScale(SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    /**
     * 解析一个必须大于 0 的价格（服务者上架价用）。
     *
     * @throws BusinessException 40001——除 {@link #parse} 的规则外，0 或负数也不接受
     */
    public static BigDecimal parsePositive(String raw, String fieldLabel) {
        BigDecimal value = parse(raw, fieldLabel);
        if (value.signum() <= 0) {
            throw BusinessException.paramInvalid(fieldLabel + "必须大于 0（免费体验请用券，不要设成 0 元）");
        }
        return value;
    }

    /** 格式化回契约里的字符串形态：固定两位小数，与库里的 DECIMAL(10,2) 一致。 */
    public static String format(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * 只在形状层面解析，不判上限也不定标度。
     *
     * <p>刻意在拦截多余小数位之前不做任何舍入：{@code "128.005"} 宁可报错也不该被悄悄变成
     * {@code 128.01}——金额上「静默取整」是用户无法察觉的错。
     */
    private static BigDecimal parseShape(String raw, String fieldLabel) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.paramInvalid(fieldLabel + "不能为空");
        }
        String trimmed = raw.trim();
        if (!SHAPE.matcher(trimmed).matches()) {
            throw BusinessException.paramInvalid(fieldLabel + "格式不正确：只能是最多两位小数的正数（如 128.00）");
        }
        return new BigDecimal(trimmed);
    }
}
