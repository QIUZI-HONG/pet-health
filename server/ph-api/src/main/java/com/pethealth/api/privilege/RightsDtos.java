package com.pethealth.api.privilege;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权益引擎的接口 DTO，与 {@code contract/admin.yaml} 的 {@code Rights*} 组件对齐。
 *
 * <p>核心形状是 {@link RightsEvaluationView}：**每个码「是否生效 + 来源 + 到期」一次给全**。
 * 各模块（AI 额度、报告、社区发帖）都读这一份，不各自查表拼规则——「同一件事两处判定」
 * 是这类引擎最常见的坏味道，而它的表现是「用户明明有权益却被拦住」（ADR-0038 第三节）。
 *
 * <p>{@code source} 的取值 1 订阅 / 2 邀请 / 3 打卡 / 4 运营补偿：前三个是 ADR-0038 的三条路径，
 * 第四个是客诉处理的口子。**判定只比来源的优先级，不比到期时间**——
 * 「邀请得永久 + 订阅得月度」按到期比会把语义算错。
 */
public final class RightsDtos {

    private RightsDtos() {
    }

    /** 权益码（码表一行）。 */
    public record RightsCodeView(
            String code,
            String name,
            String description,
            Integer sortOrder,
            Integer status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 新增 / 修改权益码；{@code code} 只在新增时给，修改时不传。 */
    public record RightsCodeRequest(
            @Pattern(regexp = "^[a-z][a-z0-9.]{1,63}$",
                    message = "权益码用小写字母 / 数字 / 点，如 ai.unlimited")
            String code,

            @NotBlank(message = "名称不能为空")
            @Size(max = 64, message = "名称最长 64 个字符")
            String name,

            @Size(max = 255, message = "说明最长 255 个字符")
            String description,

            Integer sortOrder,

            @Min(value = 0, message = "状态只能是 1 启用 / 0 停用")
            @Max(value = 1, message = "状态只能是 1 启用 / 0 停用")
            Integer status) {
    }

    /** 一条授予记录。 */
    public record RightsGrantView(
            Long id,
            Long userId,
            String code,
            String codeName,
            Integer source,
            LocalDateTime expireAt,
            Integer status,
            String sourceRef,
            String remark,
            LocalDateTime createdAt,
            LocalDateTime revokedAt) {
    }

    /**
     * 手动授予一条权益。
     *
     * <p>典型用途是**订阅**（ADR-0036 之后没有支付载体，只能线下签约 + 后台标记）
     * 与客诉补偿。{@code expireAt} 为空表示永久（邀请来源就是这样）；
     * {@code sourceRef} 是幂等键的一半（同一来源 + 引用 + 码只授予一次）。
     */
    public record RightsGrantRequest(
            @NotNull(message = "被授予人必填")
            Long userId,

            @NotBlank(message = "权益码不能为空")
            String code,

            @NotNull(message = "来源必填")
            @Min(value = 1, message = "来源只能是 1 订阅 / 2 邀请 / 3 打卡 / 4 运营补偿")
            @Max(value = 4, message = "来源只能是 1 订阅 / 2 邀请 / 3 打卡 / 4 运营补偿")
            Integer source,

            LocalDateTime expireAt,

            @Size(max = 64, message = "来源引用最长 64 个字符")
            String sourceRef,

            @Size(max = 255, message = "理由最长 255 个字符")
            String remark) {
    }

    /** 一个用户的权益判定结果。 */
    public record RightsEvaluationView(
            Long userId,
            List<RightsItemView> rights) {
    }

    /** 单个码的判定：是否生效 + 来源 + 到期。 */
    public record RightsItemView(
            String code,
            String name,
            boolean effective,
            Integer source,
            String sourceName,
            LocalDateTime expireAt) {
    }
}
