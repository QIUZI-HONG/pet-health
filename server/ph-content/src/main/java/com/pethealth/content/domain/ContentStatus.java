package com.pethealth.content.domain;

/**
 * 内容审核状态——三种社区内容（卡片 / 提问 / 回答）共用这一套状态码。
 *
 * <pre>
 *   0 待审 → 1 已发布（运营通过）
 *          ↘ 2 已驳回（机审命中自动落这里，运营也可以在下架时把已发布的打到 2）
 * </pre>
 *
 * <p>三条口径（ADR-0041 第一节 + ADR-0037 第一节）：
 *
 * <ul>
 *   <li><b>默认待审</b>：新建的内容一律 {@code 0}，公开列表只读 {@code 1}
 *       ——「待审内容不出现在公开列表」是这一块对外最直接的承诺；
 *   <li><b>机审在创建时就出结果</b>：命中敏感词直接落 {@code 2}（不是留在待审里等人看），
 *       但运营**可以改判**——词表误伤是常态，而「误伤了一个真实用户的内容且没人能改」比漏放一条更伤；
 *   <li><b>驳回与下架是同一个状态</b>：对用户的效果一样（不再出现），合并成一次迁移
 *       （{@code → 2}）比分成两个状态少一套「谁在前谁在后」的争论。
 * </ul>
 *
 * <p>状态迁移一律是**条件更新**（把源状态写进 WHERE），不是先查后判：
 * 两个运营同时处置同一条内容时，只有一个的迁移能成（ADR-0044 的并发教训）。
 */
public final class ContentStatus {

    public static final int PENDING = 0;
    public static final int PUBLISHED = 1;
    public static final int REJECTED = 2;

    private ContentStatus() {
    }

    /** 中文名；状态为空时给「未知」而不是抛异常（展示不该因为一个脏值整个失败）。 */
    public static String name(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "待审核";
            case PUBLISHED -> "已发布";
            case REJECTED -> "已驳回";
            default -> "未知";
        };
    }
}
