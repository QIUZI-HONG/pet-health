package com.pethealth.record.domain;

import com.pethealth.common.error.BusinessException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 体重：把「什么是一个合法的体重」收在一处（值对象，只有一个入口）。
 *
 * <p>为什么要有它：体重在系统里以**字符串**出现——契约规定金额与体重都用 decimal 字符串、
 * 禁止浮点（ADR-0011 / docs/conventions.md）。于是每个入口都得自己解析一遍，
 * 而规则一旦有两个副本就会分叉：建档走了 Bean Validation 注解，打卡那条路径当初根本没有校验，
 * {@code abc} / {@code 0} / {@code -5} / {@code 99999} 全被收下并落进 {@code numeric_value}，
 * 最后算出「体重下降了 2000080%」这种提醒推给用户（2026-09-28 测试报告 D1）。
 *
 * <p>所以：**规则只写在这一个类里**，建档与打卡都走 {@link #parse}。
 * 取值范围与契约的 {@code PetCreateRequest.weight} 一致（{@code 0.01–999.99}，最多两位小数）。
 */
public final class Weight {

    /** 形状：最多三位整数 + 最多两位小数（禁止科学计数法、负号、全角数字）。 */
    public static final String PATTERN = "^\\d{1,3}(\\.\\d{1,2})?$";

    public static final String MIN = "0.01";
    public static final String MAX = "999.99";

    /** 给用户看的一句话：范围与形状写在一起。前端有同一句（`CheckInCard` 的提示），改一处要改两处。 */
    public static final String HINT = "体重需为 " + MIN + "–" + MAX + " 之间的数字，最多两位小数";

    private static final Pattern SHAPE = Pattern.compile(PATTERN);
    private static final BigDecimal MIN_VALUE = new BigDecimal(MIN);
    private static final BigDecimal MAX_VALUE = new BigDecimal(MAX);

    private Weight() {
    }

    /**
     * 解析并校验**建档 / 编辑**传来的体重。{@code null} 返回 {@code null}（这个字段可以不填）；
     * **空串一律 40001**——契约里写着「`weight` 不接受空串（要改就得给合法值）」：
     * 放它过去就等于让一次误传的空串把体重悄悄清掉。
     *
     * <p>**不做四舍五入**：形状已经限死两位小数，能通过校验的值不会被截断，
     * 悄悄改掉用户填的数字（12.345 → 12.35）比报错更糟。
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw BusinessException.paramInvalid("体重不能为空；不想填就整个字段不要传");
        }
        return validate(raw.trim());
    }

    /**
     * 打卡用的宽松入口：**留空是合法意图**（「这一项先不记」），{@code null} 与空串都返回 {@code null}。
     *
     * <p>两个入口分开而不是一个方法兼两义：同一个字符串在两条业务线上含义不同，
     * 合并成一个「看情况」的方法，下一次改动一定会在某一侧悄悄改错（评审意见）。
     */
    public static BigDecimal parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return validate(raw.trim());
    }

    private static BigDecimal validate(String value) {
        if (!SHAPE.matcher(value).matches()) {
            throw BusinessException.paramInvalid(HINT);
        }
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.compareTo(MIN_VALUE) < 0 || parsed.compareTo(MAX_VALUE) > 0) {
            throw BusinessException.paramInvalid(HINT);
        }
        return parsed;
    }
}
