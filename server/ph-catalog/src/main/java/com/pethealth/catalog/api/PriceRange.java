package com.pethealth.catalog.api;

import java.math.BigDecimal;

/**
 * 价格区间：目录项允许的定价范围（含两端）。
 *
 * <p>区间**属于平台**（存在 {@code service_item} 上），不属于服务者：交付文档 2.5 的验收是
 * 「商家定价 100% 区间校验」（文档用词），校验基准若存在服务者行里，服务者改一行就等于自己给自己放宽了区间。
 *
 * <p>{@link #describe()} 产出的是**给用户看的话术**（交付文档 2.4：「价格须在¥X-¥Y之间」），
 * 服务端与前端用同一句，避免两边各拼一遍。文案口径：金额两位小数、区间用 {@code -} 连接。
 */
public record PriceRange(BigDecimal min, BigDecimal max) {

    /** 价格是否落在区间内（含两端）。区间包含边界，所以 88.00–88.00 是一个合法的单点区间。 */
    public boolean contains(BigDecimal price) {
        return price != null && price.compareTo(min) >= 0 && price.compareTo(max) <= 0;
    }

    /** 区间文案：{@code ¥88.00-¥260.00}。 */
    public String describe() {
        return "¥" + Price.format(min) + "-¥" + Price.format(max);
    }

    /** 越界时的完整提示：{@code 价格须在¥88.00-¥260.00之间}。 */
    public String outOfRangeMessage() {
        return "价格须在" + describe() + "之间";
    }

    /** 区间自身是否合法：下限不高于上限。运营写反了要在写入口就拦住，而不是等定价时才暴露。 */
    public boolean isValid() {
        return min.compareTo(max) <= 0;
    }
}
