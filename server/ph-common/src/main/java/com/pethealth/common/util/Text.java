package com.pethealth.common.util;

/**
 * 字符串归一化的小工具：把「空串 / 纯空白 / null 一律当没有」这条口径收在一处。
 *
 * <p>为什么要有这个类：这个形状原先在档案、文件、AI 三个模块各写了一份（`trimToNull` 两份、
 * `trim(value, max)` 一份）。三份的差别只在于「空串算不算值」，而这类差别**必须是有意为之**——
 * 各写一份的结果是后来者分不清哪一份是刻意的。
 *
 * <p>与「校验」的分工：这里只做归一化，**不做校验**。长度/范围该由 DTO 注解或值对象
 * （如 {@code Weight}）拒绝，归一化只是把「用户填了空白」翻译成「没填」。
 */
public final class Text {

    private Text() {
    }

    /**
     * 去掉首尾空白；结果是空串则返回 {@code null}。
     *
     * <p>用在「可选字段」上：前端把输入框清空后提交的往往是 {@code ""}，而库里该存 null——
     * 存 {@code ""} 会让「没填」与「填了空」变成两种数据，查询与展示都要各自判断一次。
     *
     * @param value 原始值，允许 null
     * @return 归一化后的值；{@code null} 或纯空白输入都得到 {@code null}
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 截断到长度上限（**校验之外的兜底**，不是校验）。
     *
     * <p>用于留痕字段：留痕不该让主链路失败，所以「上游给了超长文本」时截断而不是拒绝；
     * 真正的长度约束由契约（{@code maxLength}）与 DTO 校验负责。
     *
     * @param value 原始值，允许 null
     * @param max   保留的最大字符数；值本身就是 null 时原样返回 null
     */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
