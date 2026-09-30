package com.pethealth.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页结果：{@code {list, page, page_size, total, has_more}}（交付文档 8.1）。
 *
 * <p>每页 20 条、参数 {@code page} / {@code page_size}，上限 100——见 docs/conventions.md 与 ADR-0011。
 */
public record PageResult<T>(
        List<T> list,
        long page,
        long pageSize,
        long total,
        boolean hasMore) {

    /**
     * 每页条数上限，与 contract/common.yaml 的 {@code PageSize}（{@code maximum: 100}）一致。
     *
     * <p>三处需要同一个数：DTO 校验注解（越界回 40001）、MyBatis-Plus 拦截器的兜底上限、
     * service 层夹取。定义在这里而不是各写各的，是为了改契约时只有一个地方要跟。
     */
    public static final long MAX_PAGE_SIZE = 100L;

    public static <T> PageResult<T> of(List<T> list, long page, long pageSize, long total) {
        return new PageResult<>(list, page, pageSize, total, page * pageSize < total);
    }

    /** 把 MyBatis-Plus 的分页对象转成契约结构，顺带做实体 → DTO 的映射。 */
    public static <E, T> PageResult<T> from(IPage<E> source, Function<E, T> mapper) {
        return of(source.getRecords().stream().map(mapper).toList(),
                source.getCurrent(), source.getSize(), source.getTotal());
    }
}
