package com.pethealth.provider.domain;

import com.pethealth.common.error.BusinessException;
import com.pethealth.common.util.Text;

import java.math.BigDecimal;

/**
 * 经纬度：**范围的唯一定义处**（服务者入驻时填的门店坐标）。
 *
 * <p>为什么要有这个类：同一段「解析 + 区间校验」原先在入驻（{@code OnboardingService}）与
 * 门店信息维护（{@code ProviderProfileService}）里各写了一份，逐字相同。两处都写意味着
 * **同一个坐标在两个入口可能得到不同结论**——改了一处、漏了另一处，表现是「提交入驻被拒、
 * 改门店信息却通过了」。范围这种东西只该有一个答案。
 *
 * <p>三条口径与 {@code BusinessHours} / {@code Weight} 一致：
 *
 * <ul>
 *   <li><b>空值合法</b>：坐标是选填项，空白（含 {@code null}）一律返回 {@code null}，
 *       由调用方按「没填」处理；
 *   <li><b>解析不了就是 40001</b>：不是 500——它是用户输入的问题，文案要指出是哪一项；
 *   <li><b>越界也是 40001</b>，且文案带上合法区间，让人知道该填什么。
 * </ul>
 *
 * <p>只做校验、不做换算：坐标错到月球上去没人会发现，所以这里必须拦（这是它存在的理由），
 * 但「怎么用这个坐标算距离」不在这里——那属于将来的区域保护（ADR-0052 待澄清第 1 条）。
 */
public final class Coordinate {

    /** 经度合法区间（WGS-84）。 */
    public static final BigDecimal LNG_MIN = new BigDecimal("-180");
    public static final BigDecimal LNG_MAX = new BigDecimal("180");

    /** 纬度合法区间（WGS-84）。 */
    public static final BigDecimal LAT_MIN = new BigDecimal("-90");
    public static final BigDecimal LAT_MAX = new BigDecimal("90");

    private Coordinate() {
    }

    /** 经度：空白返回 {@code null}，非法抛 40001。 */
    public static BigDecimal longitude(String raw) {
        return parse(raw, "经度", LNG_MIN, LNG_MAX);
    }

    /** 纬度：空白返回 {@code null}，非法抛 40001。 */
    public static BigDecimal latitude(String raw) {
        return parse(raw, "纬度", LAT_MIN, LAT_MAX);
    }

    private static BigDecimal parse(String raw, String label, BigDecimal min, BigDecimal max) {
        String value = Text.trimToNull(raw);
        if (value == null) {
            return null;
        }
        BigDecimal coordinate;
        try {
            coordinate = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw BusinessException.paramInvalid(label + "格式不正确");
        }
        if (coordinate.compareTo(min) < 0 || coordinate.compareTo(max) > 0) {
            throw BusinessException.paramInvalid(label + "超出合法范围（" + min.toPlainString()
                    + " ~ " + max.toPlainString() + "）");
        }
        return coordinate;
    }
}
