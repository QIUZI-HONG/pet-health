package com.pethealth.ai.domain;

/**
 * 风险分级：**知识侧的字符串与 C 端数字码之间的唯一定义处**。
 *
 * <p>平台里这个量有两套写法，各出现在它的源头：知识库（{@code knowledge_entry.risk_hint}）
 * 与 AI 服务的响应里是 {@code green / yellow / red}，而入库与出接口（{@code risk_level}）
 * 是 1 / 2 / 3。两套写法之间的换算原先在症状识别与知识浏览里各有一份——
 * 同一份数据在两个入口得到不同分级，是那种「两处都对但合起来错」的缺陷，
 * 所以换算只留在这里一处。
 *
 * <p><b>认不出就返回 {@code null}，不兜底成 GREEN</b>：兜底会把「知识库那条写的分级有问题」
 * 变成一个看起来正常的值（绿色的、居家的建议），而它实际可能该是红色。null 会让上层
 * 按「这次没有分级」处理——这正是契约里 {@code risk_level} 可为空的原因。
 */
public enum RiskLevel {

    /** 居家观察、指标正常。 */
    GREEN(1),
    /** 建议尽快就医。 */
    YELLOW(2),
    /** 立即急诊（必须带就医建议与免责声明，ADR-0025）。 */
    RED(3);

    private final int code;

    RiskLevel(int code) {
        this.code = code;
    }

    /** 契约与库里的数字码（{@code risk_level}）：1 绿 / 2 黄 / 3 红。 */
    public int code() {
        return code;
    }

    /**
     * 知识侧的 {@code risk_hint} → 数字码；空值、大小写混写与认不出的写法都在这里归一。
     *
     * @param riskHint {@code green} / {@code yellow} / {@code red}，大小写不敏感
     * @return 1 / 2 / 3；入参为空或认不出时 {@code null}（见类注释：不兜底）
     */
    public static Integer codeOfHint(String riskHint) {
        if (riskHint == null) {
            return null;
        }
        return switch (riskHint.trim().toLowerCase()) {
            case "red" -> RED.code;
            case "yellow" -> YELLOW.code;
            case "green" -> GREEN.code;
            default -> null;
        };
    }
}
