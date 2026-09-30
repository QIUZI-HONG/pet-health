package com.pethealth.api.app;

/**
 * 一次签到的结果，对应 {@code contract/app.yaml} 的 {@code PointSignInView}。
 *
 * <p>**重复签到不是错误**：{@code awarded=false}、{@code points=0}、余额不变。
 * 契约里把这三个字段写成必填，就是为了让客户端不必去猜「没加分是不是失败了」——
 * 今天签过了与刚签上都返回同一张形状，只有 {@code awarded} 不同。
 *
 * @param awarded 本次是否真的记上了（今天已签过、或该行为被运营停用时为 false）
 * @param points  本次得分（重复签到为 0）
 * @param balance 签到后的积分余额
 * @param notice  给用户看的一句话；与信封的 message 是同一句
 */
public record PointSignInView(boolean awarded, int points, long balance, String notice) {
}
