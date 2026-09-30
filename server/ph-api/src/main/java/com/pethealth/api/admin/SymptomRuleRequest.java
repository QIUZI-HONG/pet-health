package com.pethealth.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改一条症状映射，对应 contract/admin.yaml 的 {@code SymptomRuleRequest}。
 *
 * <p>{@code symptomKeyword} 必须是**知识侧的规范名**（与 {@code knowledge_node.name} 同一套词）：
 * 运营写了一个词表里没有的说法，这条映射永远不会被命中——而界面上看不出任何异常。
 * 所以这一层不放开自由命名，只在契约里限定长度，并在说明里点名「必须是已有症状词」。
 */
public record SymptomRuleRequest(

        @NotBlank(message = "症状名不能为空")
        @Size(max = 64, message = "症状名最长 64 个字符")
        String symptomKeyword,

        @NotBlank(message = "项目编码不能为空")
        @Size(max = 32, message = "项目编码最长 32 个字符")
        String itemCode,

        @Min(value = 0, message = "排序不能为负")
        @Max(value = 9999, message = "排序最大 9999")
        Integer sortOrder,

        /** 1 启用 / 0 停用；不传按启用。**停用而不是删除**：删了就看不出「这条以前配过」。 */
        Integer enabled) {
}
