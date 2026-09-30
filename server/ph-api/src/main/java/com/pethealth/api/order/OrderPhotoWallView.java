package com.pethealth.api.order;

import java.util.List;

/**
 * 三道照片墙（ADR-0040 第四节）：接宠检查 / 服务防护 / 取宠对比，**三者缺一不可**。
 * 对应 contract/app.yaml 与 contract/provider.yaml 的 {@code OrderPhotoWallView}。
 *
 * <p>订单进入「履约中」后开放；**三个槽位各至少一张照片才允许报工**——这条硬约束在服务端，
 * 前端把按钮禁掉不算数（交付文档 2.5 的验收标准「缺一道则系统拒绝报工」）。
 *
 * <p>{@code slots} 恒为三个（没上传的槽位也在，{@code satisfied=false}）；
 * {@code reportable} 与 {@code missingSlots} 都是**服务端算出来的**，前端不要自己数数组长度。
 * 报工被拒时界面要能说清「缺哪一道」，不能只说「报工失败」——{@code missingSlots} 就是给它的。
 *
 * <p>照片 {@code biz_type=care}、**归属订单**，不进用户的档案照片墙（ADR-0040 / ADR-0030）。
 */
public record OrderPhotoWallView(
        List<OrderPhotoSlotView> slots,
        boolean reportable,
        List<Integer> missingSlots) {
}
