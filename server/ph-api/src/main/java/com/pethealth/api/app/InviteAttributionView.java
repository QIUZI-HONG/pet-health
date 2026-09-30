package com.pethealth.api.app;

/**
 * 归因结果，对应 contract/app.yaml 的 {@code InviteAttributionView}。
 *
 * <p>{@code attributed=false} **不是报错**：注册照常成功，只是没建立邀请关系
 * （ADR-0046 第一节：不能因为邀请码可疑就不让用户注册）。{@code reason} 是给排查用的判据名，
 * **给用户看的那句话是 {@code notice}**。
 *
 * <p><b>为什么那句话在 {@code notice} 而不在信封的 {@code message} 里</b>：契约规定
 * {@code code=0} 时调用方拿到的是 {@code data}（共享请求层只解包 {@code data}），
 * 信封的 {@code message} 到不了页面。把文案挂在 {@code message} 上的结果是前端只能按
 * {@code reason} 自己拼一句，同一句话就有了两份实现——服务端改一句，前端不知道
 * （见 contract/common.yaml 的 {@code ApiResponse.message}）。本接口的控制器把同一句
 * 也放进 {@code message}（两处同一个来源，便于日志与排查），但**前端以 {@code notice} 为准**。
 *
 * @param attributed     是否建立了邀请关系
 * @param notice         **给用户看的那句话，前端直接展示它**（不要在调用方按 {@code reason} 拼文案）。
 *                       三种取值：归因成功 / 已归因 / 码不存在或已失效 / 反作弊拦下（笼统一句）
 * @param reason         未建立时的原因：{@code CODE_NOT_FOUND} / {@code ALREADY_ATTRIBUTED} /
 *                       {@code SELF_INVITE} / {@code SAME_DEVICE} / {@code SAME_IP_SEGMENT}；
 *                       建立成功时为 null。{@code NO_ACTIVITY_24H} 不会在这里出现——
 *                       它由结算批算在观察窗结束后判（ADR-0046 第二、三节）。
 *                       **这是判据名，不是给用户看的文案**
 * @param inviteCode     回显本次提交的邀请码（**只回显，不代表关系已建立**）
 * @param relationStatus 关系状态：1 待生效（刚归因完一定是它）/ 2 有效 / 3 无效；
 *                       本次没建立关系时为空
 */
public record InviteAttributionView(
        Boolean attributed,
        String notice,
        String reason,
        String inviteCode,
        Integer relationStatus) {
}
