package com.pethealth.common.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页结果的换算。契约里 {@code has_more} 是前端判断「还有没有下一页」的唯一依据，
 * 所以边界要钉住：整除时最后一页不能报 true。
 */
class PageResultTest {

    @Test
    @DisplayName("has_more：还有下一页才为 true")
    void hasMore() {
        assertThat(PageResult.of(List.of("a"), 1, 20, 21).hasMore()).isTrue();
        assertThat(PageResult.of(List.of("a"), 1, 20, 20).hasMore()).isFalse();
        assertThat(PageResult.of(List.of("a"), 2, 20, 40).hasMore()).isFalse();
        assertThat(PageResult.of(List.of(), 3, 20, 40).hasMore()).isFalse();
        assertThat(PageResult.of(List.of(), 1, 20, 0).hasMore()).isFalse();
    }

    @Test
    @DisplayName("从 MyBatis-Plus 的分页对象取：页码、每页条数、总数都照搬，元素做映射")
    void fromMybatisPage() {
        Page<Integer> page = new Page<>(2, 20, 45);
        page.setRecords(List.of(1, 2, 3));

        PageResult<String> result = PageResult.from(page, n -> "no-" + n);

        assertThat(result.list()).containsExactly("no-1", "no-2", "no-3");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(45);
        assertThat(result.hasMore()).isTrue();
    }
}
