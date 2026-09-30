package com.pethealth.api.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改敏感词，对应 contract/admin.yaml 的 {@code SensitiveWordRequest}。
 *
 * <p>选词是**运营**的日常动作（ADR-0037 第一节的权限矩阵把「内容审核」划给运营），
 * 所以词表在库里可维护、改完即时生效（ADR-0010 的业务可调项分层）。
 *
 * <p>{@code enabled} 可空：不传按启用处理（新建时默认启用、改词时不动开关）。
 *
 * @param word     词条（按**包含**匹配，大小写不敏感）
 * @param category 分类标签（不参与判定）
 * @param enabled  是否启用
 * @param remark   为什么拦这个词
 */
public record SensitiveWordRequest(

        @NotBlank(message = "词条不能为空")
        @Size(max = 64, message = "词条最长 64 个字符")
        String word,

        @Size(max = 32, message = "分类标签最长 32 个字符")
        String category,

        Boolean enabled,

        @Size(max = 255, message = "说明最长 255 个字符")
        String remark) {
}
