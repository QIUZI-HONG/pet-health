package com.pethealth.api.order;

import jakarta.validation.constraints.Size;

/**
 * 报工提交，对应 contract/provider.yaml 的 {@code OrderReportRequest}。
 *
 * <p>提交即完成服务：订单从「履约中」推进到「已完成」（ADR-0038 第一节）。
 * **三道照片墙是硬约束**：三个槽位各至少一张照片才允许提交，由服务端拒绝
 * （ADR-0040 第四节 / 交付文档 2.5），被拒时 message 里点名缺哪一道。
 *
 * <p>待澄清（ADR-0048）：报工的时间戳从哪来——「平均服务时长」要由报工的时间戳算出来
 * （ADR-0039 第二节），但 ADR 没说这两个时间戳是服务者手填还是由核销与报工两个时刻自动记，
 * 所以这里只收 {@code remark}，时刻取服务端当前时间。
 */
public record OrderReportRequest(

        @Size(max = 255, message = "报工说明最长 255 个字符")
        String remark) {
}
