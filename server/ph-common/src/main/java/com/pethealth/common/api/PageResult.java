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

    public static <T> PageResult<T> of(List<T> list, long page, long pageSize, long total) {
        return new PageResult<>(list, page, pageSize, total, page * pageSize < total);
    }

    /** 把 MyBatis-Plus 的分页对象转成契约结构，顺带做实体 → DTO 的映射。 */
    public static <E, T> PageResult<T> from(IPage<E> source, Function<E, T> mapper) {
        return of(source.getRecords().stream().map(mapper).toList(),
                source.getCurrent(), source.getSize(), source.getTotal());
    }
}
