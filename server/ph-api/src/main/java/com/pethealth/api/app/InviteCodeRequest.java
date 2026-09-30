package com.pethealth.api.app;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 生成邀请码，对应 contract/app.yaml 的 {@code InviteCodeRequest}。
 *
 * <p>{@code channel} 只记录**首次生成时**的入口（1 分享链接 / 2 注册表单手工填，ADR-0049 §八），
 * **不参与归因与任何统计口径**——归因只看注册那一刻用户填的码（ADR-0039 第一节）。
 * 一人一码：已经生成过就直接返回那一条，所以渠道标签不会被后来的调用改写。
 *
 * <p>{@code device_id} 是**反作弊判据**（{@code SAME_DEVICE} 的比较基准），与渠道一样只记首次：
 * 它是客户端声明的值（桌面 Web 没有更硬的设备号），所以它的作用不是「证明是谁」，
 * 而是「两台设备是不是同一台」——刷量的成本因此从「换账号」变成「换设备」。
 * 不传时判据不成立（与归因侧同一个取舍），也不会因此拒绝请求。
 *
 * @param channel   渠道标签，可空
 * @param deviceId  本机生成的设备标识（与注册归因用的是同一个值），可空
 */
public record InviteCodeRequest(

        @Min(value = 1, message = "渠道只能是 1 分享链接 / 2 注册表单手工填 / 3 其他")
        @Max(value = 3, message = "渠道只能是 1 分享链接 / 2 注册表单手工填 / 3 其他")
        Integer channel,

        @Size(max = 128, message = "设备标识最长 128 字符")
        String deviceId) {
}
