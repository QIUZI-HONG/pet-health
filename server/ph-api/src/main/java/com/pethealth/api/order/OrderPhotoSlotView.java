package com.pethealth.api.order;

import com.pethealth.api.app.FileView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 照片墙里的一个槽位，对应 contract/app.yaml 与 contract/provider.yaml 的
 * {@code OrderPhotoSlotView}。
 *
 * <p>{@code slot}：1 接宠检查 / 2 服务防护 / 3 取宠对比。{@code slotName} 由服务端给，
 * 前端不要把这三个名字抄一遍——名字改了要改两处。
 *
 * <p>{@code photos} 是**全量集合**（PUT 整体替换的语义，见 {@link OrderPhotoSlotRequest}）：
 * 传 {@code []} 就把这一道清空了，清空之后 {@code satisfied=false}、整个墙的
 * {@code reportable} 也变回 false。
 *
 * <p>{@code satisfied} 的口径是**服务端记录的计数**，不是这里数组的长度：
 * 判据必须只有一处，否则「照片被删了但计数还在」这类不一致会以「报工被拒却看不出缺哪道」暴露。
 */
public record OrderPhotoSlotView(
        Integer slot,
        String slotName,
        List<FileView> photos,
        String remark,
        boolean satisfied,
        LocalDateTime updatedAt) {
}
