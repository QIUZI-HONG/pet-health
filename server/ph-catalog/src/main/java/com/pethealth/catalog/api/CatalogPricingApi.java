package com.pethealth.catalog.api;

import java.math.BigDecimal;

/**
 * 价格区间校验的公共服务（**下单链路要复用**，交付文档 2.5）。
 *
 * <p>交付文档 2.2 把「建品/改价/上下架」的定价权限写成「区间内」，2.4 要求越界时
 * 「后端校验拒绝，前端输入框提示『价格须在¥X-¥Y之间』」，2.5 的验收是「定价 100% 区间校验」。
 * 三处指向同一个动作：**校验点必须只有一处**，否则「上架时校验了、下单时没校验」这种缝
 * 会以「线上出现越界价」的形式暴露出来。
 *
 * <p>实现类在 {@code com.pethealth.catalog.service}。
 */
public interface CatalogPricingApi {

    /**
     * 区间查询：目录项不存在或已停用时返回 {@link java.util.Optional#empty()}。
     *
     * <p>给「只读展示」用（服务者列表标出当前区间）。写路径请用
     * {@link #requirePriceInRange}——它对「目录项查不到」有明确结论。
     */
    java.util.Optional<PriceRange> priceRangeOf(String serviceCode);

    /**
     * 校验价格落在目录项的区间内。
     *
     * @param price 服务者定价，须已由 {@link Price} 解析为两位小数
     * @return 命中的区间（调用方可能要回显文案）
     * @throws com.pethealth.common.error.BusinessException
     *         <ul>
     *           <li><b>40400</b>：目录项不存在或已停用——这是「引用了不存在的东西」，
     *               与「价定高了」是两回事，前端要给的提示也不同；
     *           <li><b>90001</b>（HTTP 200）：越界，{@code message} 就是
     *               「价格须在¥X-¥Y之间」这句给用户看的话（交付文档 2.4）。
     *         </ul>
     */
    PriceRange requirePriceInRange(String serviceCode, BigDecimal price);
}
