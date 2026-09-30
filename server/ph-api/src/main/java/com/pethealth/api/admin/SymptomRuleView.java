package com.pethealth.api.admin;

import java.time.LocalDateTime;

/**
 * 一条症状映射（运营后台），对应 contract/admin.yaml 的 {@code SymptomRuleView}。
 *
 * <p>{@code itemName} / {@code categoryName} 由服务端从标准目录**现取**（不存快照，ADR-0034 决定 9）：
 * 运营在列表上要能核对「这条映射指向的项目还对不对」——目录项被删时这两个字段为空，
 * 而那正是需要被看见的信号。
 */
public record SymptomRuleView(
        Long id,
        String symptomKeyword,
        String itemCode,
        String itemName,
        String categoryName,
        Integer sortOrder,
        Integer enabled,
        LocalDateTime updatedAt) {
}
