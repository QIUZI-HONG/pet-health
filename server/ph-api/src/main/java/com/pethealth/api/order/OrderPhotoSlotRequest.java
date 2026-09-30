package com.pethealth.api.order;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存一个照片槽位（照片 + 备注），对应 contract/provider.yaml 的 {@code OrderPhotoSlotRequest}。
 *
 * <p>**整体替换**：{@code fileIds} 是这一槽位的**全量集合**，传空数组 = 清空该槽位。
 * 「传两个 id 就只留这两张」比「追加 / 删除」两个接口更不容易出现「前端以为加了、
 * 后端以为换了」这类分歧——照片墙不是流水，它的状态就是当下这一组。
 *
 * <p>{@code fileIds} 里的 id 来自文件域的上传凭证（`biz_type=care`，ADR-0020）：
 * 不存在的、没落定的、不是 care 用途的、或已经挂在别的订单上的 → 40400。
 * 单槽位最多 9 张、同一个 id 不能传两次（40001，docs/conventions.md 的图片规格）。
 *
 * <p>没有 {@code @NotNull}：契约的 {@code required} 里没有它，而且「不传」与「传空数组」
 * 在语义上都只能是「清空」——多一条必填只会让前端多传一个空数组。
 */
public record OrderPhotoSlotRequest(

        @Size(max = 9, message = "单个槽位最多 9 张照片")
        List<Long> fileIds,

        @Size(max = 255, message = "备注最长 255 个字符")
        String remark) {
}
