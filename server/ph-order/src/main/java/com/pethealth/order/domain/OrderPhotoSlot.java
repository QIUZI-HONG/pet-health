package com.pethealth.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pethealth.common.persistence.BaseEntity;

/**
 * 照片墙的一个槽位（表 {@code order_photo_slot}，迁移 V30；决策见 ADR-0040 第四节）。
 *
 * <p>三个槽位**固定**：接宠检查 / 服务防护 / 取宠对比，三者缺一不可——
 * 「缺一道则系统拒绝报工」是交付文档 2.5 的验收标准，判据就是这个类的
 * {@code photoCount}（服务端记录的计数），而不是去数 JSON 数组的长度。
 *
 * <p>槽位行与照片行分开存（照片在 {@code order_photo}）的原因是**备注不随照片走**：
 * 门店可以先写「皮肤有红点，已拍照留痕」再传照片，也可能清空照片但留着说明。
 * 合成一列 JSON 的话，清空就变成了「把说明一起删掉」——那是两个动作。
 *
 * <p>PUT 是**整体替换**：{@code fileIds} 是这一槽位的全量集合，传空数组即清空
 * （清空之后 {@code reportable} 变回 false，报工会被服务端拒绝）。
 */
@TableName("order_photo_slot")
public class OrderPhotoSlot extends BaseEntity {

    /** 1 接宠检查。 */
    public static final int SLOT_INTAKE_CHECK = 1;
    /** 2 服务防护。 */
    public static final int SLOT_PROTECTION = 2;
    /** 3 取宠对比。 */
    public static final int SLOT_PICKUP_COMPARE = 3;

    /** 三个槽位，顺序就是界面上的顺序（接宠 → 防护 → 取宠）。 */
    private static final int[] ALL_SLOTS = {SLOT_INTAKE_CHECK, SLOT_PROTECTION, SLOT_PICKUP_COMPARE};

    /** 一个槽位最多几张（docs/conventions.md 的图片规格：单图 ≤ 10MB、最多 9 张）。 */
    public static final int MAX_PHOTOS_PER_SLOT = 9;

    private Long orderId;
    private Integer slot;
    private Integer photoCount;
    private String remark;

    /** 三个槽位（含未上传的：契约要求未上传的槽位也在，`satisfied=false`）。 */
    public static int[] allSlots() {
        return ALL_SLOTS.clone();
    }

    /** 槽位名由服务端给（契约里写明），前端不要把这三个名字抄一遍。 */
    public static String slotName(int slot) {
        return switch (slot) {
            case SLOT_INTAKE_CHECK -> "接宠检查";
            case SLOT_PROTECTION -> "服务防护";
            case SLOT_PICKUP_COMPARE -> "取宠对比";
            default -> throw new IllegalArgumentException("未知的照片槽位：" + slot);
        };
    }

    /** 槽位取值是否合法（路径参数是 1–3；越界按参数错误处理）。 */
    public static boolean isValidSlot(int slot) {
        return slot >= SLOT_INTAKE_CHECK && slot <= SLOT_PICKUP_COMPARE;
    }

    /** 这一槽位是否已至少有一张照片——报工硬校验的最小单位。 */
    public boolean isSatisfied() {
        return photoCount != null && photoCount > 0;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public Integer getPhotoCount() {
        return photoCount;
    }

    public void setPhotoCount(Integer photoCount) {
        this.photoCount = photoCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
