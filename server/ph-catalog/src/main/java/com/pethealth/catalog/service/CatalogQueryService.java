package com.pethealth.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pethealth.api.catalog.ServiceCategoryView;
import com.pethealth.api.app.CatalogCategoryView;
import com.pethealth.api.app.CatalogItemView;
import com.pethealth.api.catalog.ServiceItemView;
import com.pethealth.catalog.api.CatalogPricingApi;
import com.pethealth.catalog.api.CatalogQueryApi;
import com.pethealth.catalog.api.Price;
import com.pethealth.catalog.api.PriceRange;
import com.pethealth.catalog.domain.ServiceCategory;
import com.pethealth.catalog.domain.ServiceItem;
import com.pethealth.catalog.mapper.ServiceCategoryMapper;
import com.pethealth.catalog.mapper.ServiceItemMapper;
import com.pethealth.common.api.PageResult;
import com.pethealth.common.error.BusinessException;
import com.pethealth.common.error.ErrorCode;
import com.pethealth.common.util.Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 标准目录的读：分类与项目的列表、以及**价格区间校验这个公共服务**（交付文档 2.5）。
 *
 * <p>这一层同时实现两个对外接口（{@link CatalogQueryApi} / {@link CatalogPricingApi}）：
 * 它们是同一个数据（目录项 + 它的区间）的两种用法，拆成两个实现类会让「区间怎么算」
 * 出现两份代码——而「上架时校验了、下单时没校验」正是这样来的。
 *
 * <p>三条口径：
 *
 * <ul>
 *   <li><b>停用项对服务者不可见、也不可定价</b>：{@link #priceRangeOf} 与
 *       {@link #requirePriceInRange} 都把 {@code status=0} 当作「不存在」；
 *   <li><b>单次批量取名称</b>：列表视图要拼分类名，一次把分类取全（分类是十几个量级），
 *       而不是每行查一次；
 *   <li><b>关键字只搜名称</b>：目录没有别的可搜字段，不引入额外的检索依赖（ngram 是知识库的事）。
 * </ul>
 */
@Service
public class CatalogQueryService implements CatalogQueryApi, CatalogPricingApi {

    /** 关键字长度上限，与控制器上的校验共用一份值（改这里就够）。 */
    public static final int KEYWORD_MAX_LENGTH = 64;

    private final ServiceCategoryMapper categoryMapper;
    private final ServiceItemMapper itemMapper;

    public CatalogQueryService(ServiceCategoryMapper categoryMapper, ServiceItemMapper itemMapper) {
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * 分类列表。{@code includeDisabled=false} 时只给启用的（服务者选品与将来的 C 端浏览用），
     * 运营侧传 true 以便管理停用的分类。
     */
    @Transactional(readOnly = true)
    public List<ServiceCategoryView> listCategories(boolean includeDisabled) {
        List<ServiceCategory> categories = categoryMapper.selectList(
                Wrappers.<ServiceCategory>lambdaQuery()
                        .eq(!includeDisabled, ServiceCategory::getStatus, ServiceCategory.STATUS_ENABLED)
                        .orderByAsc(ServiceCategory::getSortOrder)
                        .orderByAsc(ServiceCategory::getId));
        Map<String, Long> counts = countEnabledItemsByCategory();
        return categories.stream()
                .map(category -> toView(category, counts.getOrDefault(category.getCode(), 0L)))
                .toList();
    }

    /**
     * 目录项分页。
     *
     * @param categoryCode 为空表示全部分类
     * @param status       为空表示全部状态；**服务者侧必须传 1**（由控制器固定，不由调用方决定）
     */
    @Transactional(readOnly = true)
    public PageResult<ServiceItemView> pageItems(String categoryCode, String keyword, Integer status,
                                                long page, long pageSize) {
        String nameKeyword = Text.trimToNull(keyword);
        Page<ServiceItem> result = itemMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<ServiceItem>lambdaQuery()
                        .eq(Text.trimToNull(categoryCode) != null, ServiceItem::getCategoryCode,
                                Text.trimToNull(categoryCode))
                        .eq(status != null, ServiceItem::getStatus, status)
                        .like(nameKeyword != null, ServiceItem::getName, nameKeyword)
                        // 目录是「人排好的顺序」，所以按 sort_order 而不是时间倒序
                        .orderByAsc(ServiceItem::getSortOrder)
                        .orderByAsc(ServiceItem::getId));
        Map<String, String> categoryNames = categoryNames();
        return PageResult.from(result, item -> toView(item, categoryNames.get(item.getCategoryCode())));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ItemInfo> findItems(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        // 去重后再查：调用方来自「列表里的 code」，重复是常态
        Set<String> distinct = codes.stream().filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, String> categoryNames = categoryNames();
        return itemMapper.selectList(Wrappers.<ServiceItem>lambdaQuery()
                        .in(ServiceItem::getCode, distinct)).stream()
                .collect(Collectors.toMap(ServiceItem::getCode,
                        item -> toInfo(item, categoryNames.get(item.getCategoryCode())),
                        (first, second) -> first));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemInfo> findItem(String code) {
        ServiceItem item = findByCode(code);
        return item == null ? Optional.empty() : Optional.of(toInfo(item, categoryName(item.getCategoryCode())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceRange> priceRangeOf(String serviceCode) {
        ServiceItem item = findByCode(serviceCode);
        return item == null || !item.isEnabled()
                ? Optional.empty()
                : Optional.of(rangeOf(item));
    }

    @Override
    @Transactional(readOnly = true)
    public PriceRange requirePriceInRange(String serviceCode, BigDecimal price) {
        ServiceItem item = findByCode(serviceCode);
        if (item == null || !item.isEnabled()) {
            // 「引用了不存在的东西」与「价定高了」是两回事：前端提示不同，所以要分开
            throw BusinessException.notFound("目录项 " + serviceCode + " 不存在或已停用");
        }
        PriceRange range = rangeOf(item);
        if (!range.contains(price)) {
            // 90001 是**业务结果**不是传输失败，HTTP 仍是 200（ErrorCode 里就是这么映射的）
            throw new BusinessException(ErrorCode.PRICE_OUT_OF_RANGE, range.outOfRangeMessage());
        }
        return range;
    }

    /** 分类编码 → 分类名（一页最多 100 行，分类是十几个量级，一次取全最简单也最稳）。 */
    @Transactional(readOnly = true)
    public Map<String, String> categoryNames() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(ServiceCategory::getCode, ServiceCategory::getName,
                        (first, second) -> first));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> categoryNames(Collection<String> categoryCodes) {
        if (categoryCodes == null || categoryCodes.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectList(Wrappers.<ServiceCategory>lambdaQuery()
                        .in(ServiceCategory::getCode, categoryCodes)
                        .eq(ServiceCategory::getStatus, ServiceCategory.STATUS_ENABLED)).stream()
                .collect(Collectors.toMap(ServiceCategory::getCode, ServiceCategory::getName,
                        (first, second) -> first));
    }

    /** 分类编码 → 分类（运营改项目时要拿前缀与状态，只有名字不够）。 */
    @Transactional(readOnly = true)
    public Map<String, ServiceCategory> categoriesByCode() {
        Map<String, ServiceCategory> result = new HashMap<>();
        for (ServiceCategory category : categoryMapper.selectList(null)) {
            result.put(category.getCode(), category);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ServiceItem findByCode(String code) {
        String normalized = Text.trimToNull(code);
        if (normalized == null) {
            return null;
        }
        return itemMapper.selectOne(Wrappers.<ServiceItem>lambdaQuery().eq(ServiceItem::getCode, normalized));
    }

    /** 区间：两个非空列，直接包成值对象。 */
    public static PriceRange rangeOf(ServiceItem item) {
        return new PriceRange(item.getPriceMin(), item.getPriceMax());
    }

    public static ServiceItemView toView(ServiceItem item, String categoryName) {
        return new ServiceItemView(
                item.getId(),
                item.getCode(),
                item.getCategoryCode(),
                categoryName,
                item.getName(),
                Price.format(item.getPriceMin()),
                Price.format(item.getPriceMax()),
                item.getPriceUnit(),
                item.getDurationMinutes(),
                item.getApplicablePets(),
                item.getDescription(),
                item.getSortOrder(),
                item.getStatus(),
                item.getUpdatedAt());
    }

    public static ServiceCategoryView toView(ServiceCategory category, long itemCount) {
        return new ServiceCategoryView(
                category.getId(),
                category.getCode(),
                category.getItemCodePrefix(),
                category.getName(),
                category.getIcon(),
                category.getDescription(),
                category.getSortOrder(),
                category.getStatus(),
                itemCount,
                category.getUpdatedAt());
    }

    public static ItemInfo toInfo(ServiceItem item, String categoryName) {
        return new ItemInfo(
                item.getCode(),
                item.getName(),
                item.getCategoryCode(),
                categoryName,
                item.getPriceUnit(),
                rangeOf(item),
                item.getApplicablePets(),
                item.getStatus(),
                item.getDurationMinutes());
    }

    private String categoryName(String categoryCode) {
        ServiceCategory category = categoryMapper.selectOne(
                Wrappers.<ServiceCategory>lambdaQuery().eq(ServiceCategory::getCode, categoryCode));
        return category == null ? null : category.getName();
    }

    /**
     * 每个分类下的启用项目数。
     *
     * <p>键名两种拼法都试一下：MyBatis 对 {@code Map} 结果集是否套用
     * {@code map-underscore-to-camel-case} 依版本而异（POJO 一定套用，Map 不一定），
     * 与其在注释里赌一个版本行为，不如两种都认——这两种写法不会同时出现，也不会互相盖掉。
     */
    private Map<String, Long> countEnabledItemsByCategory() {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : categoryMapper.countEnabledItemsByCategory()) {
            String code = text(row, "category_code", "categoryCode");
            Object count = row.containsKey("item_count") ? row.get("item_count") : row.get("itemCount");
            if (code != null && count instanceof Number number) {
                counts.put(code, number.longValue());
            }
        }
        return counts;
    }

    private static String text(Map<String, Object> row, String snake, String camel) {
        Object value = row.containsKey(snake) ? row.get(snake) : row.get(camel);
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------- C 端浏览（`/api/v1/app/catalog`，只读）

    /**
     * C 端分类导航：**只给启用中的分类**，按运营维护的 `sortOrder` 升序。
     *
     * <p>与 {@link #listCategories(boolean)} 的区别是「谁决定要不要看停用的」：运营侧传 true
     * 是为了管理自己停用过什么；C 端由服务端固定只看启用的——所以这条方法没有参数，
     * 调用方（控制器）也没有机会把停用的分类放出去。
     */
    @Transactional(readOnly = true)
    public List<CatalogCategoryView> appCategories() {
        Map<String, Long> counts = countEnabledItemsByCategory();
        return categoryMapper.selectList(Wrappers.<ServiceCategory>lambdaQuery()
                        .eq(ServiceCategory::getStatus, ServiceCategory.STATUS_ENABLED)
                        .orderByAsc(ServiceCategory::getSortOrder)
                        .orderByAsc(ServiceCategory::getId))
                .stream()
                .map(category -> new CatalogCategoryView(
                        category.getCode(),
                        category.getName(),
                        category.getDescription(),
                        category.getIcon(),
                        counts.getOrDefault(category.getCode(), 0L)))
                .toList();
    }

    /**
     * C 端目录项分页：**只给启用中的项目**（停用项不允许新增选品，对 C 端也没有意义）。
     *
     * <p>排序按项目自己的 `sortOrder` → `code`：与运营后台同一分类内看到的顺序一致——
     * 两处顺序不同会让「我在后台排在第一个」的运营困惑。
     */
    @Transactional(readOnly = true)
    public PageResult<CatalogItemView> appItems(String categoryCode, String keyword, long page, long pageSize) {
        String nameKeyword = Text.trimToNull(keyword);
        String category = Text.trimToNull(categoryCode);
        Page<ServiceItem> result = itemMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.<ServiceItem>lambdaQuery()
                        .eq(ServiceItem::getStatus, ServiceItem.STATUS_ENABLED)
                        .eq(category != null, ServiceItem::getCategoryCode, category)
                        .like(nameKeyword != null, ServiceItem::getName, nameKeyword)
                        .orderByAsc(ServiceItem::getSortOrder)
                        .orderByAsc(ServiceItem::getCode));
        Map<String, String> categoryNames = categoryNames();
        return PageResult.from(result, item -> toAppView(item, categoryNames.get(item.getCategoryCode())));
    }

    /** 目录项 → C 端视图。价格一律以字符串出（ADR-0011：JS 里丢精度）。 */
    public static CatalogItemView toAppView(ServiceItem item, String categoryName) {
        return new CatalogItemView(
                item.getCode(),
                item.getCategoryCode(),
                categoryName,
                item.getName(),
                item.getDescription(),
                Price.format(item.getPriceMin()),
                Price.format(item.getPriceMax()),
                item.getPriceUnit(),
                item.getDurationMinutes(),
                item.getApplicablePets());
    }

    /** 给同模块的写服务用：把实体映射成视图，规则只有这一份（见 {@link CatalogAdminService}）。 */
    public Function<ServiceItem, ServiceItemView> viewMapper(Map<String, String> categoryNames) {
        return item -> toView(item, categoryNames.get(item.getCategoryCode()));
    }
}
